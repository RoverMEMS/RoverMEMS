package com.roverspi.memsgauge.protocol

import com.roverspi.memsgauge.logging.DebugLog as Log
import kotlinx.coroutines.delay

/**
 * Ports librosco's protocol.c command sequences onto a [ByteTransport].
 * The link is half-duplex: every command byte sent is expected to be
 * echoed back by the ECU before any further response data follows.
 */
class MemsProtocol(private val transport: ByteTransport) {

    private companion object {
        const val TAG = "RoverMEMS"
        const val INIT_BYTE_A = 0xCA
        const val INIT_BYTE_B = 0x75
        const val ID_REQUEST = 0xD0
        const val ID_RESPONSE_SIZE = 4

        // エコー不一致の後、残りのバイトが届くのを待つ時間(診断用)
        const val MISMATCH_LINGER_MS = 500L

        const val HEARTBEAT_FOLLOW_UP_TIMEOUT_MS = 1000L

        const val BURST_PROBE_LISTEN_MS = 1500L

        const val FRAME_7D_EVERY = 2

        // フレームの先頭バイト(全体のバイト数)として許す範囲。0x80は少なくとも
        // dtc1(14バイト目)まで、0x7Dは先頭バイトだけあれば読み始められる。
        const val MIN_FRAME_80 = 14
        const val MIN_FRAME_7D = 2
        const val MAX_FRAME_SIZE = 64
        const val GIVE_UP_7D_AFTER = 2
    }

    /**
     * The raw 4-byte 0xD0 response from the most recent [initLink] call, kept
     * around so the UI can show it when [EcuVersion.fromD0Response] can't
     * match it to a known version -- lets us capture what a real, unrecognized
     * ECU actually sends instead of guessing.
     */
    var lastEcuIdBytes: ByteArray? = null
        private set

    /**
     * Sends the ECU startup sequence (0xCA, 0x75, heartbeat, 0xD0) and
     * returns the detected ECU version from the 0xD0 response, or null if
     * any step of the handshake failed.
     */
    suspend fun initLink(): EcuVersion? {
        // つなぎ直した後は、前の接続の0x7Dを使い回さない
        cachedFrame7d = null
        readCount = 0
        got7dEver = false
        consecutive7dFailures = 0
        skip7d = false
        if (!sendCommand(INIT_BYTE_A)) {
            Log.w(TAG, "initLink: INIT_BYTE_A (0xCA) got no valid echo")
            return null
        }
        if (!sendCommand(INIT_BYTE_B)) {
            Log.w(TAG, "initLink: INIT_BYTE_B (0x75) got no valid echo")
            return null
        }
        if (!sendCommand(MemsDataCommand.HEARTBEAT.byte)) {
            Log.w(TAG, "initLink: heartbeat command got no valid echo")
            return null
        }
        // One byte follows the heartbeat echo; discard it, as librosco does.
        // BLE(本物のHM-10)の実車テスト(09-26)では、0xCA/0x75/0xF4のエコーは
        // 正しく返るのにこの1バイト(本来0x00)だけが届かなかった。ここで
        // 諦めず0xD0まで進め、ECUが型番応答を返すかで「会話は成立していて
        // 0x00だけ落ちている」のかを切り分ける。短めの待ち時間にしている。
        if (transport.readExactly(1, HEARTBEAT_FOLLOW_UP_TIMEOUT_MS) == null) {
            Log.w(TAG, "initLink: heartbeat follow-up byte never arrived -- continuing to 0xD0 anyway")
        }
        if (!sendCommand(ID_REQUEST)) {
            Log.w(TAG, "initLink: ID_REQUEST (0xD0) got no valid echo")
            return null
        }
        val idResponse = transport.readExactly(ID_RESPONSE_SIZE)
        if (idResponse == null) {
            Log.w(TAG, "initLink: ECU ID response never arrived")
            return null
        }
        lastEcuIdBytes = idResponse
        val version = EcuVersion.fromD0Response(idResponse)
        Log.d(TAG, "initLink: succeeded, ecuVersion=$version")
        return version
    }

    /**
     * 診断用: [initLink]失敗後に呼ぶ。0xCA/0x75/0xF4を1回の書き込みでまとめて
     * 送り(ECUには間を置かず連続で届く＝USB有線に近いタイミング)、続けて
     * 0xD0を単独で送って、それぞれ届いたバイトを全部ログに残す。
     *
     * 09-26の実車テストで「エコーは完璧に返るがECUの応答データは1バイトも
     * 来ない」状態になったための切り分け:
     * - CA 75 F4 00 が返る → ECUはコマンド間隔が短ければ応答する(タイミング説)
     * - CA だけ返る → モジュールが連続受信の2バイト目以降を落としている
     * - CA 75 F4 だけ返る → エコーは返るがECUは応答しない(タイミング以外)
     */
    suspend fun burstProbe() {
        Log.d(TAG, "burstProbe: writing CA 75 F4 as one burst")
        transport.flushStaleBytes()
        if (!transport.write(byteArrayOf(INIT_BYTE_A.toByte(), INIT_BYTE_B.toByte(), MemsDataCommand.HEARTBEAT.byte.toByte()))) {
            Log.w(TAG, "burstProbe: burst write failed")
            return
        }
        delay(BURST_PROBE_LISTEN_MS)
        transport.flushStaleBytes()
        Log.d(TAG, "burstProbe: writing D0")
        if (!transport.write(byteArrayOf(ID_REQUEST.toByte()))) {
            Log.w(TAG, "burstProbe: D0 write failed")
            return
        }
        delay(BURST_PROBE_LISTEN_MS)
        transport.flushStaleBytes()
        Log.d(TAG, "burstProbe: done")
    }

    /**
     * Sends a single command byte and confirms the ECU echoes it back.
     * Any additional response data must be read separately.
     */
    suspend fun sendCommand(cmd: Int): Boolean {
        // 09-25に入れた送信前50ms待機は撤去(09-26実車で効果なしと確認)。
        // 09-27の盗聴テストで、ECUはエコーを返すのに応答データを一切
        // 出していないと判明し、コマンド間隔(BLEだとCA→75が約113ms、
        // USB有線は数ms)が長すぎてECUが初期化を受け付けていない疑いが
        // 出たため、余計な待ちは入れない。
        // Clear out anything left over from an earlier call that timed out --
        // otherwise those stale bytes get read as this command's echo/response
        // and every subsequent read stays shifted out of alignment.
        transport.flushStaleBytes()
        if (!transport.write(byteArrayOf(cmd.toByte()))) {
            Log.w(TAG, "sendCommand(0x%02X): write failed".format(cmd))
            return false
        }
        val echo = transport.readExactly(1)
        if (echo == null) {
            Log.w(TAG, "sendCommand(0x%02X): no echo within timeout".format(cmd))
            return false
        }
        val echoByte = echo[0].toInt() and 0xFF
        if (echoByte != cmd) {
            Log.w(TAG, "sendCommand(0x%02X): echo mismatch, got 0x%02X".format(cmd, echoByte))
            // 失敗後すぐ切断されると、誤った1バイトの後ろに続くデータ(もしあれば)
            // を見られない。少し待って、届いた残りをflushStaleBytes()のログに出す。
            delay(MISMATCH_LINGER_MS)
            transport.flushStaleBytes()
            return false
        }
        return true
    }

    // 0x7D(ラムダ・燃料トリム等)は0x80ほど速く変わらないので FRAME_7D_EVERY 回に
    // 1回だけ取り、間は前回の値を使う。メーターに使う0x80の更新回数が増える
    // (BLEは1往復が重いので効果が大きい)。
    private var cachedFrame7d: MemsFrame7d? = null
    private var readCount = 0

    // 0x7Dに答えない(または形が違う)ECUのための状態。一度も0x7Dが取れていないまま
    // 連続で失敗したら、その接続中は0x7Dを諦めて0x80だけで表示を続ける。
    private var got7dEver = false
    private var consecutive7dFailures = 0
    private var skip7d = false

    /**
     * コマンドを送り、エコーの後ろの「先頭1バイト=フレーム全体のバイト数」に従って
     * 返事を最後まで読む。フレーム長を28/32に決め打ちすると、長さの違うECU
     * (92年式で「データ待機中」のまま動かない報告、10-12)で永遠に読み終わらないため。
     * 参考: 有料版Rover MEMS ECU Diagnosticも先頭バイトの長さで読んでいる。
     * 期待より短ければ0で埋め、長ければ切り捨てて[expectedSize]にそろえる。
     */
    private suspend fun readFrame(cmd: Int, expectedSize: Int, minSize: Int): ByteArray? {
        if (!sendCommand(cmd)) return null
        val first = transport.readExactly(1)
        if (first == null) {
            Log.w(TAG, "readFrame(0x%02X): size byte never arrived".format(cmd))
            return null
        }
        val size = first[0].toInt() and 0xFF
        if (size < minSize || size > MAX_FRAME_SIZE) {
            Log.w(TAG, "readFrame(0x%02X): implausible frame size %d".format(cmd, size))
            return null
        }
        val rest = if (size > 1) transport.readExactly(size - 1) else ByteArray(0)
        if (rest == null) {
            Log.w(TAG, "readFrame(0x%02X): body never arrived (size=%d)".format(cmd, size))
            return null
        }
        if (size != expectedSize) {
            Log.w(TAG, "readFrame(0x%02X): frame size %d differs from the usual %d".format(cmd, size, expectedSize))
        }
        return (first + rest).copyOf(expectedSize)
    }

    /** Requests the data frames and merges them into a [MemsData] snapshot. */
    suspend fun readData(ecuVersion: EcuVersion): MemsData? {
        val bytes80 = readFrame(MemsDataCommand.REQ_DATA_80.byte, MemsFrame80.FRAME_SIZE, MIN_FRAME_80) ?: return null
        val frame80 = MemsFrameParser.parseFrame80(bytes80, ecuVersion)

        var frame7d = cachedFrame7d
        var frame7dAvailable = true
        if (!skip7d && (frame7d == null || readCount % FRAME_7D_EVERY == 0)) {
            val bytes7d = readFrame(MemsDataCommand.REQ_DATA_7D.byte, MemsFrame7d.FRAME_SIZE, MIN_FRAME_7D)
            if (bytes7d != null) {
                frame7d = MemsFrameParser.parseFrame7d(bytes7d, ecuVersion)
                cachedFrame7d = frame7d
                got7dEver = true
                consecutive7dFailures = 0
            } else {
                consecutive7dFailures++
                if (!got7dEver && consecutive7dFailures >= GIVE_UP_7D_AFTER) {
                    skip7d = true
                    Log.w(TAG, "readData: ECU does not answer 0x7D -- continuing with the 0x80 frame only")
                }
            }
        }
        if (frame7d == null) {
            // 0x7Dが取れない間も、0x80のデータは表示する(取れない項目は中立値)。
            frame7d = MemsFrame7d.UNAVAILABLE
            frame7dAvailable = false
        }
        readCount++

        return MemsData.fromFrames(frame80, frame7d, ecuVersion, frame7dAvailable)
    }

    /** Sends the fault-code-clear command and confirms the ECU's one-byte reply. */
    suspend fun clearFaults(): Boolean {
        if (!sendCommand(MemsDataCommand.CLEAR_FAULTS.byte)) return false
        return transport.readExactly(1) != null
    }

    /** Simple ping to check the link is still alive. */
    suspend fun heartbeat(): Boolean {
        if (!sendCommand(MemsDataCommand.HEARTBEAT.byte)) return false
        return transport.readExactly(1) != null
    }

    /** Reads the current idle air control motor position. */
    suspend fun readIacPosition(): Int? {
        if (!sendCommand(MemsDataCommand.GET_IAC_POSITION.byte)) return null
        val response = transport.readExactly(1) ?: return null
        return response[0].toInt() and 0xFF
    }

    /**
     * Runs an actuator test (fuel pump, relays, IAC, etc.) and returns the
     * single data byte the ECU sends back. Not used by the v1 UI, but ready
     * for a v2 actuator-test screen.
     */
    suspend fun testActuator(cmd: MemsActuatorCommand): Int? {
        if (!sendCommand(cmd.byte)) return null
        val response = transport.readExactly(1) ?: return null
        return response[0].toInt() and 0xFF
    }
}

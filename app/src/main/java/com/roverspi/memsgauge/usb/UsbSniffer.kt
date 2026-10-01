package com.roverspi.memsgauge.usb

import android.content.Context
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.roverspi.memsgauge.logging.DebugLog as Log
import com.roverspi.memsgauge.logging.describeBytes
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 診断用の「盗聴器」。BLE接続中に、USBシリアルケーブル(PL2303等)を受信専用
 * として開き、ECUの白線(ECU TX)に実際に流れているバイトを全部ログに残す。
 * ケーブルの受信線(RXD)とGNDだけをECU側に並列につなぎ、送信線(TXD)は
 * つながない前提 -- こちらからは一切書き込まない。
 *
 * 09-26、BLE経由では「エコーは完璧に返るがECUの応答データが1バイトも
 * 来ない」状態になった。ECUが実際に応答しているのにBLE受信側で消えて
 * いるのか、ECUがそもそも応答していないのかを、実績のある有線ケーブルで
 * 直接見て切り分ける。
 *
 * USBケーブルがつながっていなければ何もしない(通常のBLE利用には影響なし)。
 */
class UsbSniffer(context: Context) {

    private val scanner = UsbDeviceScanner(context)
    private var port: UsbSerialPort? = null
    private var executor = Executors.newSingleThreadExecutor()
    @Volatile private var reading = false

    suspend fun start() {
        stop()
        val driver = scanner.listAvailableDrivers().firstOrNull() ?: return
        if (!scanner.requestPermission(driver.device)) {
            Log.w(TAG, "USB sniff: permission denied")
            return
        }
        val connection = scanner.openConnection(driver.device)
        val newPort = driver.ports.firstOrNull()
        if (connection == null || newPort == null) {
            Log.w(TAG, "USB sniff: no USB connection/port available")
            return
        }
        try {
            newPort.open(connection)
            newPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            Log.w(TAG, "USB sniff: open failed", e)
            return
        }
        port = newPort
        reading = true
        Log.d(TAG, "USB sniff: listening on $newPort")
        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val buffer = ByteArray(256)
            while (reading) {
                val count = try {
                    newPort.read(buffer, READ_POLL_TIMEOUT_MS)
                } catch (e: IOException) {
                    Log.w(TAG, "USB sniff: read failed, stopping", e)
                    reading = false
                    0
                }
                if (count > 0) {
                    Log.d(TAG, "USB sniff rx ($count bytes): ${describeBytes(buffer.copyOf(count))}")
                }
            }
        }
    }

    fun stop() {
        if (port == null) return
        reading = false
        executor.shutdown()
        runCatching { port?.close() }
        port = null
        Log.d(TAG, "USB sniff: stopped")
    }

    private companion object {
        const val TAG = "RoverMEMS"
        // 短めにして、受信したバイトをなるべく届いた時刻に近いタイミングでログに出す
        const val READ_POLL_TIMEOUT_MS = 20
    }
}

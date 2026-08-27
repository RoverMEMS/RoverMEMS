package com.roverspi.memsgauge.datasource

import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsActuatorCommand
import com.roverspi.memsgauge.protocol.MemsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/**
 * Generates plausible-looking fake [MemsData] so the UI can be built and
 * exercised before any BLE hardware exists (and so the gauges have
 * something lively to show in screenshots/demos). Simulates a whole drive,
 * not just a static idle:
 *
 * 1. Key-on/cranking (first few seconds): engine not running yet.
 * 2. ~1 minute warmup: fast idle settling down to normal idle while coolant
 *    temp climbs from cold to operating temperature, car stationary.
 * 3. Driving: a repeating pull-away/cruise/overtake/decelerate cycle once
 *    warmed up, so RPM/MAP/throttle keep moving like an actual drive
 *    instead of sitting at a fixed idle number.
 */
class MockEcuDataSource : EcuDataSource {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _ecuVersion = MutableStateFlow(EcuVersion.UNKNOWN)
    override val ecuVersion: StateFlow<EcuVersion> = _ecuVersion.asStateFlow()

    private val _ecuIdRaw = MutableStateFlow<String?>(null)
    override val ecuIdRaw: StateFlow<String?> = _ecuIdRaw.asStateFlow()

    private val _latestData = MutableStateFlow<MemsData?>(null)
    override val latestData: StateFlow<MemsData?> = _latestData.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var simulationJob: Job? = null

    // デモ用の水温センサーエラー(下記FAULT_ON_AFTER_S参照)の状態。一度発生
    // したら、ユーザーが「エラーコードクリア」を押してclearFaults()が呼ばれる
    // まで立ちっぱなしにする(押すとその後は再発しない、実際のクリア操作を
    // 試せるように)。
    private var faultHasOccurred = false
    private var faultActive = false

    override suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING
        delay(CONNECT_DELAY_MS)
        _ecuVersion.value = EcuVersion.MEMS_1_3
        _ecuIdRaw.value = "99 00 03 03"
        _connectionState.value = ConnectionState.CONNECTED
        faultHasOccurred = false
        faultActive = false
        simulationJob?.cancel()
        simulationJob = scope.launch { runSimulation() }
    }

    override suspend fun clearFaults(): Boolean {
        delay(SIMULATED_COMMAND_DELAY_MS)
        faultActive = false
        return true
    }

    override suspend fun runActuatorTest(command: MemsActuatorCommand): Boolean {
        delay(SIMULATED_COMMAND_DELAY_MS)
        return true
    }

    override fun disconnect() {
        simulationJob?.cancel()
        simulationJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _ecuVersion.value = EcuVersion.UNKNOWN
        _ecuIdRaw.value = null
        _latestData.value = null
    }

    private suspend fun runSimulation() {
        val startTimeMs = System.currentTimeMillis()
        while (true) {
            val elapsedS = (System.currentTimeMillis() - startTimeMs) / 1000.0
            _latestData.value = simulatedFrame(elapsedS)
            delay(TICK_MS)
        }
    }

    /** Steps through [steps] one value every [stepSeconds], looping. Used by the calibration test modes. */
    private fun <T> testStepValue(sinceStartS: Double, steps: List<T>, stepSeconds: Double): T {
        val index = (sinceStartS / stepSeconds).toInt() % steps.size
        return steps[index]
    }

    private fun simulatedFrame(elapsedS: Double): MemsData {
        val running = elapsedS >= ENGINE_START_DELAY_S
        val sinceStartS = (elapsedS - ENGINE_START_DELAY_S).coerceAtLeast(0.0)
        val warmupProgress = (sinceStartS / WARMUP_SECONDS).coerceIn(0.0, 1.0)
        val coolant = if (WATER_TEMP_CALIBRATION_TEST_MODE && running) {
            // 針キャリブレーション検証用: 90〜120を10度刻みで一定時間ごとに切り替える。
            testStepValue(sinceStartS, WATER_TEMP_TEST_STEPS, WATER_TEMP_TEST_STEP_SECONDS)
        } else {
            (COLD_TEMP_C + warmupProgress * (OPERATING_TEMP_C - COLD_TEMP_C)).toInt()
        }
        val isDriving = sinceStartS > WARMUP_SECONDS

        // load: 0 = closed throttle/idle, 1 = full throttle. Drives RPM, MAP,
        // throttle position and ignition advance together so they move as a
        // believable set instead of independently.
        val (load, baseRpm) = when {
            !running -> 0.0 to 0.0
            !isDriving -> {
                // Fast idle right after startup, settling to normal idle as
                // warmup completes -- matches how MEMS raises idle when cold.
                val fastIdle = FAST_IDLE_RPM - (FAST_IDLE_RPM - IDLE_RPM) * warmupProgress
                0.05 to fastIdle
            }
            else -> {
                val cyclePos = ((sinceStartS - WARMUP_SECONDS) % DRIVE_CYCLE_S) / DRIVE_CYCLE_S
                driveCycleProfile(cyclePos)
            }
        }

        val rpm = when {
            !running -> 0
            // アナログメーターの針キャリブレーション検証用: 500〜1300を200刻みで
            // 一定時間ごとに切り替える(ノイズなしの固定値なので、実測との誤差を
            // 正確に測れる)。検証が終わったら false に戻して通常のドライブ
            // サイクルシミュレーションに戻すこと。
            RPM_CALIBRATION_TEST_MODE -> testStepValue(sinceStartS, RPM_TEST_STEPS, RPM_TEST_STEP_SECONDS)
            else -> (baseRpm + IDLE_RPM_NOISE * sin(elapsedS * 2.0) + Random.nextInt(-10, 10)).toInt().coerceAtLeast(0)
        }
        val mapKpa = if (MAP_CALIBRATION_TEST_MODE && running) {
            // 針キャリブレーション検証用: 40〜100を10刻みで一定時間ごとに切り替える。
            testStepValue(sinceStartS, MAP_TEST_STEPS, MAP_TEST_STEP_SECONDS)
        } else if (running) {
            (IDLE_MAP_KPA + load * (WOT_MAP_KPA - IDLE_MAP_KPA)).toInt()
        } else {
            100
        }
        val throttle = (0.35f + load.toFloat() * 3.9f).coerceIn(0.3f, 4.5f)
        val throttleAngle = (throttle - 0.3f) * 20.0f
        val battery = if (BATTERY_CALIBRATION_TEST_MODE && running) {
            // 針キャリブレーション検証用: 11〜15Vを0.5刻みで一定時間ごとに切り替える。
            testStepValue(sinceStartS, BATTERY_TEST_STEPS, BATTERY_TEST_STEP_SECONDS)
        } else if (running) {
            RUNNING_VOLTAGE
        } else {
            KEY_ON_VOLTAGE
        }
        // Advance backs off under load (like real ignition maps retarding
        // timing as throttle opens) and idles higher when off-throttle.
        val advance = if (running) {
            (8.0f + 14.0f * (1.0 - load).toFloat() * (0.7f + 0.3f * sin(elapsedS * 0.5).toFloat()))
        } else {
            0.0f
        }
        val afr = if (running) (14.7f + 0.4f * sin(elapsedS * 1.7)).toFloat() else 0.0f

        if (running && sinceStartS >= FAULT_ON_AFTER_S && !faultHasOccurred) {
            faultHasOccurred = true
            faultActive = true
        }

        return MemsData(
            engineRpm = rpm,
            coolantTempC = coolant,
            ambientTempC = 18,
            intakeAirTempC = 20,
            fuelTempC = 20,
            mapKpa = mapKpa,
            batteryVoltage = battery,
            throttlePotVoltage = throttle,
            throttleAngleDeg = throttleAngle,
            airFuelRatio = afr,
            idleSwitch = running && rpm in 1..1200,
            parkNeutralSwitch = true,
            coolantTempSensorFault = faultActive,
            intakeAirTempSensorFault = false,
            fuelPumpCircuitFault = false,
            throttlePotCircuitFault = false,
            iacPosition = 45,
            idleSpeedDeviation = 0,
            idleError = 0,
            idleBasePos = 40,
            ignitionAdvanceDeg = advance,
            coilTimeMs = if (running) 3.0f else 0.0f,
            lambdaVoltageMv = if (running) 450 else 0,
            lambdaSensorFrequency = 0,
            lambdaSensorDutyCycle = 0,
            lambdaSensorStatus = running,
            closedLoop = running,
            longTermFuelTrim = 0,
            shortTermFuelTrim = 0,
            carbonCanisterDutyCycle = 0,
            ecuVersion = EcuVersion.MEMS_1_3
        )
    }

    /**
     * One repeating "drive" cycle once warmup is done: pull away from idle,
     * cruise with light throttle noise, a brief overtake rev, decelerate
     * back down (closed throttle, like engine braking), then a short idle
     * before it repeats. Returns (load 0..1, targetRpm) at [cyclePos] (0..1
     * through the cycle).
     */
    private fun driveCycleProfile(cyclePos: Double): Pair<Double, Double> = when {
        cyclePos < 0.15 -> { // pulling away
            val t = cyclePos / 0.15
            t to (IDLE_RPM + t * (3200.0 - IDLE_RPM))
        }
        cyclePos < 0.45 -> { // cruising, gentle throttle variation
            val t = (cyclePos - 0.15) / 0.30
            (0.35 + 0.1 * sin(t * Math.PI * 2)) to (2200.0 + 150 * sin(t * Math.PI * 2))
        }
        cyclePos < 0.60 -> { // short overtake rev
            val t = (cyclePos - 0.45) / 0.15
            (0.35 + t * 0.55) to (2200.0 + t * (3800.0 - 2200.0))
        }
        cyclePos < 0.90 -> { // closed-throttle deceleration
            val t = (cyclePos - 0.60) / 0.30
            0.05 to (3800.0 - t * (3800.0 - 900.0))
        }
        else -> { // brief idle before the cycle repeats
            val t = (cyclePos - 0.90) / 0.10
            0.05 to (900.0 - t * (900.0 - IDLE_RPM))
        }
    }

    private companion object {
        const val CONNECT_DELAY_MS = 1000L
        const val SIMULATED_COMMAND_DELAY_MS = 150L
        const val TICK_MS = 200L
        const val ENGINE_START_DELAY_S = 3.0
        const val WARMUP_SECONDS = 60.0
        const val DRIVE_CYCLE_S = 20.0
        const val COLD_TEMP_C = 15.0
        const val OPERATING_TEMP_C = 90.0
        const val IDLE_RPM = 850.0
        const val FAST_IDLE_RPM = 1300.0
        const val IDLE_RPM_NOISE = 25
        const val IDLE_MAP_KPA = 35.0
        const val WOT_MAP_KPA = 95.0
        const val KEY_ON_VOLTAGE = 12.4f
        const val RUNNING_VOLTAGE = 14.2f

        // 針キャリブレーション検証用(有効時は通常のシミュレーションの代わりに
        // こちらが使われる)。較正作業は完了したので通常のドライブサイクル
        // シミュレーションに復帰済み(次に較正をやり直すときだけ true に戻す)。
        const val RPM_CALIBRATION_TEST_MODE = false
        val RPM_TEST_STEPS = listOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500, 7000, 7500, 8000)
        const val RPM_TEST_STEP_SECONDS = 5.0

        const val WATER_TEMP_CALIBRATION_TEST_MODE = false
        val WATER_TEMP_TEST_STEPS = listOf(90, 100, 110, 120)
        const val WATER_TEMP_TEST_STEP_SECONDS = 5.0

        const val BATTERY_CALIBRATION_TEST_MODE = false
        val BATTERY_TEST_STEPS = listOf(11.0f, 11.5f, 12.0f, 12.5f, 13.0f, 13.5f, 14.0f, 14.5f, 15.0f)
        const val BATTERY_TEST_STEP_SECONDS = 5.0

        const val MAP_CALIBRATION_TEST_MODE = false
        val MAP_TEST_STEPS = listOf(40, 50, 60, 70, 80, 90, 100)
        const val MAP_TEST_STEP_SECONDS = 5.0

        // デモ用: しばらく走った後に水温センサーエラーを意図的に発生させ、
        // エラーバナー/詳細画面のエラー表示/エラーコードクリア機能を
        // 実際に試せるようにする(エンジン始動から90秒後、街乗りが始まって
        // 少し経ったタイミング)。
        const val FAULT_ON_AFTER_S = 90.0
    }
}

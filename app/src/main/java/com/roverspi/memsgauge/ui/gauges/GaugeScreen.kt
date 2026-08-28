package com.roverspi.memsgauge.ui.gauges

import android.Manifest
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roverspi.memsgauge.NightModeManager
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.datasource.ConnectionState
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.protocol.EcuVersion
import com.roverspi.memsgauge.protocol.MemsData
import com.roverspi.memsgauge.ui.LanguageToggleButton
import com.roverspi.memsgauge.ui.NightModeToggleButton
import com.roverspi.memsgauge.ui.theme.RoverMemsTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val FaultRed = Color(0xFFE53935)
private val OkGreen = Color(0xFF43A047)
private val LampOffGray = Color(0xFF9E9E9E)

private enum class DisplayMode { SIMPLE, DETAILED, CHARTS, ANALOG }

@Composable
fun GaugeScreen(
    dataSource: EcuDataSource,
    onDisconnect: () -> Unit,
    onOpenActuatorTests: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: GaugeViewModel = viewModel(
        factory = GaugeViewModel.Factory(dataSource, context.applicationContext)
    )
    val connectionState by viewModel.connectionState.collectAsState()
    val ecuVersion by viewModel.ecuVersion.collectAsState()
    val ecuIdRaw by viewModel.ecuIdRaw.collectAsState()
    val data by viewModel.latestData.collectAsState()
    val history by viewModel.history.collectAsState()
    val clearFaultsMessage by viewModel.clearFaultsMessage.collectAsState()
    val isLogging by viewModel.isLogging.collectAsState()
    val logFilePath by viewModel.logFilePath.collectAsState()
    val onReconnect: () -> Unit = { viewModel.reconnect() }

    // Only API 26-28 need this permission (see DataLogger); API 29+ writes
    // through MediaStore instead, which needs no runtime permission.
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.toggleLogging() }

    GaugeScreenContent(
        connectionState = connectionState,
        ecuVersion = ecuVersion,
        ecuIdRaw = ecuIdRaw,
        data = data,
        history = history,
        clearFaultsMessage = clearFaultsMessage,
        onClearFaults = { viewModel.clearFaults() },
        onClearFaultsMessageShown = { viewModel.clearFaultsMessageShown() },
        onOpenActuatorTests = onOpenActuatorTests,
        onOpenLogs = onOpenLogs,
        isLogging = isLogging,
        logFilePath = logFilePath,
        onToggleLogging = {
            val needsLegacyStoragePermission = !isLogging && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            val alreadyGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (needsLegacyStoragePermission && !alreadyGranted) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                viewModel.toggleLogging()
            }
        },
        onDisconnect = {
            viewModel.disconnect()
            onDisconnect()
        },
        onReconnect = onReconnect
    )
}

/**
 * Stateless UI content, separated from [GaugeScreen] so Android Studio's
 * @Preview can render it instantly without a real [EcuDataSource] (which
 * needs a live Context/Bluetooth/USB stack that the preview renderer can't
 * provide). Two switchable layouts:
 * - シンプル: a small card grid of the handful of values most useful while
 *   driving, modeled after the "Rover MEMS ECU Diagnostic" app's GRIDVIEW tab.
 * - 詳細: a scrollable "label: value" list of every field the protocol
 *   exposes, modeled after MEMSFCR's "Live data (text)" screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GaugeScreenContent(
    connectionState: ConnectionState,
    ecuVersion: EcuVersion,
    data: MemsData?,
    onDisconnect: () -> Unit,
    ecuIdRaw: String? = null,
    history: List<MemsData> = emptyList(),
    clearFaultsMessage: String? = null,
    onClearFaults: () -> Unit = {},
    onClearFaultsMessageShown: () -> Unit = {},
    onOpenActuatorTests: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    isLogging: Boolean = false,
    logFilePath: String? = null,
    onToggleLogging: () -> Unit = {},
    onReconnect: () -> Unit = {},
    initialMode: DisplayMode = DisplayMode.SIMPLE
) {
    var mode by remember { mutableStateOf(initialMode) }

    if (clearFaultsMessage != null) {
        LaunchedEffect(clearFaultsMessage) {
            delay(2000)
            onClearFaultsMessageShown()
        }
    }

    // アナログモードはダイヤルを画面いっぱいに見せたいので、運転中は使わない
    // ステータス表示・操作ボタン群・ヘッダーバーまで全部隠し、シンプルモードへ
    // 戻る小さいボタン1つだけを残す。横画面専用機能なので、入っている間だけ
    // Activityの向きを横に固定し、抜けたら元に戻す。
    val isAnalogMode = mode == DisplayMode.ANALOG
    val activity = LocalContext.current.findActivity()

    LaunchedEffect(isAnalogMode) {
        activity?.requestedOrientation = if (isAnalogMode) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    if (isAnalogMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (data != null) {
                AnalogGaugeGrid(data, modifier = Modifier.fillMaxSize())
                AnalogFaultIndicators(
                    data = data,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 4.dp, end = 8.dp)
                )
            } else {
                Text(
                    stringResource(R.string.gauge_waiting_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Button(
                onClick = { mode = DisplayMode.SIMPLE },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.35f),
                    contentColor = Color.White
                )
            ) {
                Text(stringResource(R.string.gauge_mode_simple), style = MaterialTheme.typography.labelSmall)
            }
            // 時計は画面上部中央に固定 -- メーターグリッド内の「真ん中の隙間」
            // はタブレット(2x2)とスマホ(2x1)で形が違って被ることがあるので
            // グリッドの外に出し、右上(電池・アンテナ等のステータスバー)を
            // 避けて中央に。statusBarsPaddingでステータスバーの高さ分は自動で
            // 避ける。
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RetroClock()
                AnalogNightModeButton()
            }
            // アナログモードは接続状態バッジを持たないので、通信が詰まって
            // 値が更新されなくなった時だけアイコンを出す。運転中でも
            // モードを抜けずに異常に気づけ、タップで即座に再接続できる。
            if (connectionState == ConnectionState.RECONNECTING || connectionState == ConnectionState.ERROR) {
                AnalogReconnectIndicator(
                    onClick = onReconnect,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.gauge_live_data_title)) },
                actions = {
                    LanguageToggleButton()
                    NightModeToggleButton()
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            EcuStatusBadge(connectionState, ecuVersion, ecuIdRaw, modifier = Modifier.padding(top = 12.dp))
            data?.let { FaultSummaryBanner(it, modifier = Modifier.padding(top = 8.dp)) }

            Row(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { mode = DisplayMode.SIMPLE }) { Text(stringResource(R.string.gauge_mode_simple)) }
                Button(onClick = { mode = DisplayMode.DETAILED }) { Text(stringResource(R.string.gauge_mode_detailed)) }
                Button(onClick = { mode = DisplayMode.CHARTS }) { Text(stringResource(R.string.gauge_mode_charts)) }
                Button(onClick = { mode = DisplayMode.ANALOG }) { Text(stringResource(R.string.gauge_mode_analog)) }
            }

            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onClearFaults) { Text(stringResource(R.string.gauge_clear_faults)) }
                Button(onClick = onOpenActuatorTests) { Text(stringResource(R.string.gauge_actuator_tests)) }
            }

            Row(
                modifier = Modifier.padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onToggleLogging) {
                    Text(stringResource(if (isLogging) R.string.gauge_log_stop else R.string.gauge_log_start))
                }
                Button(onClick = onOpenLogs) { Text(stringResource(R.string.gauge_log_list)) }
            }

            if (isLogging && logFilePath != null) {
                Text(
                    stringResource(R.string.gauge_recording, logFilePath),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            clearFaultsMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            }

            if (data != null) {
                when (mode) {
                    DisplayMode.SIMPLE -> SimpleGaugeGrid(data, modifier = Modifier.weight(1f))
                    DisplayMode.DETAILED -> DetailedDataList(data, modifier = Modifier.weight(1f))
                    DisplayMode.CHARTS -> ChartsView(history, modifier = Modifier.weight(1f))
                    DisplayMode.ANALOG -> Unit // handled above, full-screen, before this Scaffold
                }
            } else {
                Text(
                    stringResource(R.string.gauge_waiting_data),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }

            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(stringResource(R.string.gauge_disconnect))
            }
        }
    }
}

/** Walks up the Context wrapper chain to find the hosting Activity, so orientation can be locked/unlocked. */
private tailrec fun android.content.Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun EcuStatusBadge(
    state: ConnectionState,
    version: EcuVersion,
    ecuIdRaw: String? = null,
    modifier: Modifier = Modifier
) {
    val label = when (state) {
        ConnectionState.DISCONNECTED -> stringResource(R.string.ecu_status_disconnected)
        ConnectionState.CONNECTING -> stringResource(R.string.ecu_status_connecting)
        ConnectionState.CONNECTED -> when (version) {
            EcuVersion.MEMS_1_3 -> stringResource(R.string.ecu_status_mems13)
            EcuVersion.MEMS_1_6 -> stringResource(R.string.ecu_status_mems16)
            // Show the raw ID bytes too so an unrecognized real ECU's
            // response can just be read off the screen instead of guessed at.
            EcuVersion.UNKNOWN -> {
                val idSuffix = ecuIdRaw?.let { stringResource(R.string.ecu_status_unknown_id_suffix, it) } ?: ""
                stringResource(R.string.ecu_status_unknown, idSuffix)
            }
        }
        ConnectionState.RECONNECTING -> stringResource(R.string.ecu_status_reconnecting)
        ConnectionState.ERROR -> stringResource(R.string.ecu_status_error)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * Overall warning banner, visible in both シンプル and 詳細 modes, so a fault
 * is obvious without having to open the detailed fault list. Mirrors the
 * red LED indicators MEMSGauge's Qt UI used for the same four fault bits.
 */
@Composable
private fun FaultSummaryBanner(data: MemsData, modifier: Modifier = Modifier) {
    val hasFault = data.coolantTempSensorFault || data.intakeAirTempSensorFault ||
        data.fuelPumpCircuitFault || data.throttlePotCircuitFault

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (hasFault) FaultRed else OkGreen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FaultLamp(isOn = hasFault, litColor = Color.White)
            Text(
                text = stringResource(if (hasFault) R.string.fault_banner_has_fault else R.string.fault_banner_ok),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Small round lamp, lit ([litColor]) or unlit (gray) -- same idea as MEMSGauge's QLedIndicator widgets. */
@Composable
private fun FaultLamp(isOn: Boolean, litColor: Color = FaultRed) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .background(color = if (isOn) litColor else LampOffGray, shape = CircleShape)
    )
}

/**
 * Small card grid of the values most useful at a glance while driving.
 * Field selection and order mirror the "Rover MEMS ECU Diagnostic" app's
 * GRIDVIEW tab: RPM, MAP, TPS, Coolant, Inlet, Battery, IgnAdv, Lambda, FuelTrim.
 */
@Composable
private fun SimpleGaugeGrid(data: MemsData, modifier: Modifier = Modifier) {
    // MemsData.longTermFuelTrim is already centered on zero (raw - 128); undo
    // that here so 100% means "no trim", matching the reference app's display.
    val fuelTrimPercent = ((data.longTermFuelTrim + 128) / 128.0f) * 100f

    val gauges = listOf(
        GaugeMetric.RPM to "${data.engineRpm}",
        GaugeMetric.MAP to "${data.mapKpa}",
        GaugeMetric.TPS to "%.2f".format(data.throttlePotVoltage),
        GaugeMetric.COOLANT to "${data.coolantTempC}",
        GaugeMetric.INTAKE to "${data.intakeAirTempC}",
        GaugeMetric.BATTERY to "%.2f".format(data.batteryVoltage),
        GaugeMetric.IGNITION to "%.1f".format(data.ignitionAdvanceDeg),
        GaugeMetric.LAMBDA to "${data.lambdaVoltageMv}",
        GaugeMetric.FUEL_TRIM to "%.0f".format(fuelTrimPercent)
    )
    var infoDialogFor by remember { mutableStateOf<GaugeMetric?>(null) }

    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = modifier) {
        items(gauges) { (metric, value) ->
            Card(modifier = Modifier.padding(6.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(SIMPLE_GAUGE_METRIC_INFO.getValue(metric).labelRes), style = MaterialTheme.typography.labelMedium)
                        InfoButton(onClick = { infoDialogFor = metric })
                    }
                    Text(value, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }

    infoDialogFor?.let { metric ->
        GaugeInfoDialog(metric = metric, onDismiss = { infoDialogFor = null })
    }
}

private data class AnalogGaugeSpec(
    val key: GaugeMetric,
    val faceRes: Int,
    val value: Float,
    val minValue: Float,
    val maxValue: Float,
    val valueText: String,
    // null = plain linear scale (min→0f, max→1f). Set only when a gauge
    // needs a non-linear/expanded scale, e.g. 水温's hot zone.
    val scalePoints: List<GaugeScalePoint>? = null,
    // 文字盤に焼き込まれた黒い数値窓の位置(ダイヤル一辺の長さに対する割合)。
    // 4枚とも別々に生成された画像なので、位置・高さが微妙に違う
    // (特に水温は作り直した影響で他より下寄り・大きめ)。ピクセル計測して
    // ゲージごとに個別に指定する。
    val digitalBoxTop: Float = 0.629f,
    val digitalBoxHeight: Float = 0.076f,
    val digitalBoxWidth: Float = 0.29f
)

/** Tablet breakpoint: at/above this width, a 2x2 grid fits comfortably; below it, a 2x1 row. */
private val TABLET_WIDTH_BREAKPOINT = 600.dp

/**
 * Needle-style analog dials rendered from the [AnalogGauge] face+needle
 * images, laid directly on the wood dashboard photo (no card chrome, no info
 * popup -- this screen is meant to be glanced at while driving, not read).
 * The screen is always split evenly among however many slots fit (2x2 on a
 * tablet, 2x1 on a phone) so every gauge gets a large, equal share of the
 * screen. Each slot can show any of the 4 metrics -- drag a gauge onto
 * another slot to swap positions (like rearranging phone home screen
 * icons), or tap the small button at its bottom-right corner to pick which
 * metric that slot shows (the only way to bring in a metric that isn't
 * currently visible, e.g. swapping MAP into a phone's 2-slot layout). Both
 * the slot assignment and the swap are persisted via [GaugeLayoutPrefs].
 */
@Composable
private fun AnalogGaugeGrid(data: MemsData, modifier: Modifier = Modifier) {
    val allMetrics = listOf(
        AnalogGaugeSpec(
            key = GaugeMetric.RPM,
            faceRes = R.drawable.gauge_rpm,
            value = data.engineRpm.toFloat(),
            minValue = 0f,
            maxValue = 8000f,
            valueText = "${data.engineRpm} rpm",
            // gauge_rpm.pngの実際の目盛り角度をピクセル計測して補正。低回転側
            // (0〜3000)ほど理想の均等割りより目盛りが手前に寄っていて、
            // 単純な線形だとアイドリング付近で針が実際の目盛りより最大
            // 150〜200rpm分先を指してしまっていた(実機で複数の実測値から確認)。
            // 5000〜7000は実機テストモードの2回の実測データを正しい方向で
            // 合成して再計算(前回の補正は合成の向きを取り違えており誤差を
            // 悪化させていたため修正)。
            scalePoints = listOf(
                GaugeScalePoint(0f, -0.0367f),
                GaugeScalePoint(1000f, 0.1000f),
                GaugeScalePoint(2000f, 0.2408f),
                GaugeScalePoint(3000f, 0.3692f),
                GaugeScalePoint(4000f, 0.5008f),
                GaugeScalePoint(5000f, 0.6336f),
                GaugeScalePoint(6000f, 0.7649f),
                GaugeScalePoint(7000f, 0.8940f),
                GaugeScalePoint(8000f, 1.0325f)
            )
        ),
        AnalogGaugeSpec(
            key = GaugeMetric.COOLANT,
            faceRes = R.drawable.gauge_coolant,
            value = data.coolantTempC.toFloat(),
            minValue = 40f,
            maxValue = 120f,
            valueText = "${data.coolantTempC} °C",
            // gauge_coolant.png(v3)は40〜120・10刻みの太い目盛り(40,60,80,
            // 100,120に数字)が均等間隔で描かれている(実測誤差2°以内)ので、
            // 他のゲージと同じ単純な線形スケール(デフォルト)のままでよい。
            // 黒い数値窓も他の3枚より下寄り・大きめに描かれている(実測)。
            digitalBoxTop = 0.648f,
            digitalBoxHeight = 0.095f,
            digitalBoxWidth = 0.27f
        ),
        AnalogGaugeSpec(
            key = GaugeMetric.BATTERY,
            faceRes = R.drawable.gauge_battery,
            value = data.batteryVoltage,
            minValue = 8f,
            maxValue = 16f,
            valueText = "%.1f V".format(data.batteryVoltage),
            // gauge_battery.pngの実際の目盛り角度をピクセル計測して補正
            // (単純な線形だと8V/16V付近で最大10°近くズレていたため)。
            // 11〜15Vは実機テストモードの2回の実測データを正しい方向で
            // 合成して再計算(前回の補正は合成の向きを取り違えており誤差を
            // 悪化させていたため修正)。
            scalePoints = listOf(
                GaugeScalePoint(8f, 0.0478f),
                GaugeScalePoint(10f, 0.2637f),
                GaugeScalePoint(11.0f, 0.3700f),
                GaugeScalePoint(11.5f, 0.4341f),
                GaugeScalePoint(12.0f, 0.4998f),
                GaugeScalePoint(12.5f, 0.5606f),
                GaugeScalePoint(13.0f, 0.6275f),
                GaugeScalePoint(13.5f, 0.6890f),
                GaugeScalePoint(14.0f, 0.7538f),
                GaugeScalePoint(14.5f, 0.8136f),
                GaugeScalePoint(15.0f, 0.8735f),
                GaugeScalePoint(16f, 0.9605f)
            ),
            digitalBoxWidth = 0.21f
        ),
        AnalogGaugeSpec(
            key = GaugeMetric.MAP,
            faceRes = R.drawable.gauge_map,
            value = data.mapKpa.toFloat(),
            minValue = 0f,
            maxValue = 100f,
            valueText = "${data.mapKpa} kPa",
            // gauge_map.pngの実際の目盛り角度をピクセル計測して補正
            // (単純な線形だと最大8°程度ズレていたため)。40〜100は実機テスト
            // モードの2回の実測データを正しい方向で合成して再計算(前回の
            // 補正は合成の向きを取り違えており誤差を悪化させていたため修正)。
            scalePoints = listOf(
                GaugeScalePoint(0f, -0.0031f),
                GaugeScalePoint(40f, 0.3829f),
                GaugeScalePoint(50f, 0.4978f),
                GaugeScalePoint(60f, 0.6146f),
                GaugeScalePoint(70f, 0.7285f),
                GaugeScalePoint(80f, 0.8503f),
                GaugeScalePoint(90f, 0.9633f),
                GaugeScalePoint(100f, 1.0680f)
            ),
            digitalBoxWidth = 0.21f
        )
    )

    val context = LocalContext.current
    val layoutPrefs = remember { GaugeLayoutPrefs(context) }
    var pickerForSlot by remember { mutableStateOf<Int?>(null) }

    BoxWithConstraints(modifier = modifier) {
        // 画面の向きに関係ない「端末そのものの短辺サイズ」で判定する。
        // アナログ画面は横画面固定なので、現在の幅(maxWidth)で判定すると
        // スマホでも横向きなら長辺が600dpを超えて常にタブレット扱いになって
        // しまう(smallestScreenWidthDpなら回転に影響されない)。
        val isTablet = LocalConfiguration.current.smallestScreenWidthDp >= TABLET_WIDTH_BREAKPOINT.value
        val columns = 2
        val rows = if (isTablet) 2 else 1
        val slotCount = columns * rows // タブレット4分割、スマホ2分割 -- 常に全枠が埋まる
        val storageKey = if (isTablet) "tablet" else "phone"

        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        // 各枠(セル)いっぱいに大きく収まるサイズ -- 数値表示はダイヤルに重ねる
        // ので、外側に別枠を確保せずセルサイズのほぼ全部をダイヤルに使える。
        val gaugeSizeDp = minOf(maxWidth / columns, maxHeight / rows) * 0.92f
        val gaugeWidthPx = with(density) { gaugeSizeDp.toPx() }
        val gaugeHeightPx = gaugeWidthPx

        val slotCenters = remember(columns, rows, containerWidthPx, containerHeightPx) {
            (0 until slotCount).map { i ->
                val col = i % columns
                val row = i / columns
                Offset(
                    (col + 0.5f) / columns * containerWidthPx,
                    (row + 0.5f) / rows * containerHeightPx
                )
            }
        }

        var slotAssignment by remember(storageKey, slotCount) {
            val saved = layoutPrefs.getSlotAssignment(storageKey, allMetrics.map { it.key.name }, slotCount)
            mutableStateOf(
                saved.mapIndexed { i, key ->
                    key?.let { runCatching { GaugeMetric.valueOf(it) }.getOrNull() } ?: allMetrics[i % allMetrics.size].key
                }
            )
        }

        Image(
            painter = painterResource(R.drawable.wood_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        var draggingSlot by remember { mutableStateOf<Int?>(null) }
        var dragCenterPx by remember { mutableStateOf(Offset.Zero) }

        fun assignMetric(slotIndex: Int, metricKey: GaugeMetric) {
            val newAssignment = slotAssignment.toMutableList()
            val existingSlot = newAssignment.indexOf(metricKey)
            if (existingSlot >= 0 && existingSlot != slotIndex) {
                newAssignment[existingSlot] = newAssignment[slotIndex]
            }
            newAssignment[slotIndex] = metricKey
            slotAssignment = newAssignment
            layoutPrefs.saveSlotAssignment(storageKey, newAssignment.map { it.name })
        }

        slotAssignment.forEachIndexed { slotIndex, metricKey ->
            val metric = allMetrics.first { it.key == metricKey }
            val targetCenter = slotCenters[slotIndex]
            val animatedCenter = remember(slotIndex) { Animatable(targetCenter, Offset.VectorConverter) }
            val isDragging = draggingSlot == slotIndex

            LaunchedEffect(targetCenter, isDragging) {
                if (!isDragging) animatedCenter.animateTo(targetCenter)
            }

            val currentCenter = if (isDragging) dragCenterPx else animatedCenter.value

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (currentCenter.x - gaugeWidthPx / 2f).roundToInt(),
                            (currentCenter.y - gaugeHeightPx / 2f).roundToInt()
                        )
                    }
                    .width(gaugeSizeDp)
                    .pointerInput(slotIndex) {
                        detectDragGestures(
                            onDragStart = {
                                draggingSlot = slotIndex
                                dragCenterPx = targetCenter
                            },
                            onDragEnd = {
                                val nearestSlot = slotCenters.indices.minByOrNull {
                                    (slotCenters[it] - dragCenterPx).getDistanceSquared()
                                } ?: slotIndex
                                if (nearestSlot != slotIndex) {
                                    val newAssignment = slotAssignment.toMutableList()
                                    val tmp = newAssignment[nearestSlot]
                                    newAssignment[nearestSlot] = newAssignment[slotIndex]
                                    newAssignment[slotIndex] = tmp
                                    slotAssignment = newAssignment
                                    layoutPrefs.saveSlotAssignment(storageKey, newAssignment.map { it.name })
                                }
                                draggingSlot = null
                            },
                            onDragCancel = { draggingSlot = null }
                        ) { change, dragAmount ->
                            change.consume()
                            dragCenterPx = Offset(
                                (dragCenterPx.x + dragAmount.x).coerceIn(0f, containerWidthPx),
                                (dragCenterPx.y + dragAmount.y).coerceIn(0f, containerHeightPx)
                            )
                        }
                    }
            ) {
                Box {
                    AnalogGauge(
                        faceRes = metric.faceRes,
                        value = metric.value,
                        minValue = metric.minValue,
                        maxValue = metric.maxValue,
                        sizeDp = gaugeSizeDp,
                        scalePoints = metric.scalePoints ?: listOf(
                            GaugeScalePoint(metric.minValue, 0f),
                            GaugeScalePoint(metric.maxValue, 1f)
                        )
                    )
                    // デジタル数値: 文字盤に焼き込み済みの黒い窓にぴったり重ねる。
                    // 窓の位置・大きさは画像ごとに実測(4枚それぞれ生成が別なので
                    // 微妙に違う、特に水温は他より下寄り・大きめ)。窓の背景は
                    // 画像側にあるので、ここでは白文字だけを窓の高さいっぱいに
                    // 近いサイズで載せる。
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = gaugeSizeDp * metric.digitalBoxTop)
                            .width(gaugeSizeDp * metric.digitalBoxWidth)
                            .height(gaugeSizeDp * metric.digitalBoxHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            metric.valueText,
                            color = Color.White,
                            fontSize = with(LocalDensity.current) { (gaugeSizeDp * metric.digitalBoxHeight * 0.8f).toSp() },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    // メーター選択ボタン: ダイヤル右下の余白に小さく配置。
                    // タップで、この枠に表示するメーターを選び直せる。
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(30.dp)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .clickable { pickerForSlot = slotIndex },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⋮", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        pickerForSlot?.let { slotIndex ->
            MetricPickerDialog(
                options = allMetrics,
                onSelect = { metricKey ->
                    assignMetric(slotIndex, metricKey)
                    pickerForSlot = null
                },
                onDismiss = { pickerForSlot = null }
            )
        }
    }
}

/** Popup listing all 4 metrics so the user can pick which one a slot shows. */
@Composable
private fun MetricPickerDialog(
    options: List<AnalogGaugeSpec>,
    onSelect: (GaugeMetric) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(R.string.dialog_select_gauge)) },
        text = {
            Column {
                options.forEach { option ->
                    TextButton(
                        onClick = { onSelect(option.key) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(SIMPLE_GAUGE_METRIC_INFO.getValue(option.key).labelRes),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    )
}

/**
 * A small digital clock synced to the device's system time, styled like a
 * retro dashboard clock. Docked in the screen's top-right corner (alongside
 * the "シンプル" button in the top-left) so it never collides with the gauge
 * grid, whose middle gap differs in shape between the tablet (2x2, an open
 * cross) and phone (2x1, a thin seam) layouts.
 */
@Composable
private fun RetroClock(modifier: Modifier = Modifier) {
    var timeText by remember { mutableStateOf(formatClockTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = formatClockTime()
            delay(1000)
        }
    }
    Box(
        modifier = modifier
            .background(Color(0xFF16130F), RoundedCornerShape(8.dp))
            .border(1.5.dp, Color(0xFF9E9E9E), RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            timeText,
            color = Color.White,
            fontSize = 26.sp,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        )
    }
}

private fun formatClockTime(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

/**
 * Night-mode toggle for the analog screen, styled to match [RetroClock]'s
 * dashboard badge look since this screen doesn't use the TopAppBar (and
 * therefore not [com.roverspi.memsgauge.ui.NightModeToggleButton]) at all.
 * Placed right next to the clock since analog mode is the screen actually
 * used while driving, where the brightness toggle matters most.
 */
@Composable
private fun AnalogNightModeButton(modifier: Modifier = Modifier) {
    val effectiveNight by NightModeManager.effectiveNight.collectAsState()
    Box(
        modifier = modifier
            .size(40.dp)
            .background(Color(0xFF16130F), CircleShape)
            .border(1.5.dp, Color(0xFF9E9E9E), CircleShape)
            .clickable { NightModeManager.toggleOverride() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (effectiveNight) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = stringResource(R.string.night_mode_toggle),
            tint = Color.White
        )
    }
}

/**
 * Tappable "communication is stuck" badge for the analog screen -- sized 1.5x
 * [AnalogNightModeButton] since it needs to register at a glance while
 * driving, unlike the night toggle which is only tapped deliberately.
 * Tapping it calls straight into [GaugeViewModel.reconnect] to retry the
 * link immediately instead of waiting for the poll loop's own auto-recovery
 * timers.
 */
@Composable
private fun AnalogReconnectIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(60.dp)
            .background(Color(0xFF16130F), CircleShape)
            .border(1.5.dp, FaultRed, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.SyncProblem,
            contentDescription = stringResource(R.string.ecu_status_reconnecting),
            tint = FaultRed,
            modifier = Modifier.size(36.dp)
        )
    }
}

/**
 * Small round "pilot lamp" strip in the analog screen's top-right corner,
 * one per sensor fault flag (icon instead of text, to match the retro
 * dashboard mood), so a problem is visible at a glance while driving without
 * needing to switch to シンプル/詳細 to see it (unlike [FaultSummaryBanner],
 * which only shows a single combined "any fault" state).
 */
@Composable
private fun AnalogFaultIndicators(data: MemsData, modifier: Modifier = Modifier) {
    val lamps = listOf(
        Triple(Icons.Filled.Thermostat, stringResource(R.string.analog_fault_coolant), data.coolantTempSensorFault),
        Triple(Icons.Filled.Air, stringResource(R.string.analog_fault_intake), data.intakeAirTempSensorFault),
        Triple(Icons.Filled.LocalGasStation, stringResource(R.string.analog_fault_fuel), data.fuelPumpCircuitFault),
        Triple(Icons.Filled.Bolt, stringResource(R.string.analog_fault_throttle), data.throttlePotCircuitFault)
    )
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        lamps.forEach { (icon, description, isFaulty) ->
            Box(
                modifier = Modifier
                    .size(33.dp)
                    .background(color = if (isFaulty) FaultRed else OkGreen, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

/** Small round "？" button that opens the explanation popup for one gauge. */
@Composable
private fun InfoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(20.dp)
            .background(color = MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("？", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun GaugeInfoDialog(metric: GaugeMetric, onDismiss: () -> Unit) {
    val info = SIMPLE_GAUGE_METRIC_INFO.getValue(metric)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        title = { Text(stringResource(info.labelRes)) },
        text = {
            Column {
                Text(stringResource(info.descriptionRes))
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(info.normalRangeRes), style = MaterialTheme.typography.labelMedium)
            }
        }
    )
}

/** One "label: value" row, matching MEMSFCR's live-data text layout. */
@Composable
private fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * One fault row with a red/gray lamp, so a faulty sensor is obvious at a
 * glance. [icon] matches the pilot lamp shown for the same sensor on the
 * アナログ screen ([AnalogFaultIndicators]), so the two screens reinforce
 * each other instead of each using an unrelated symbol.
 */
@Composable
private fun FaultRow(icon: ImageVector, label: String, isFaulty: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (isFaulty) R.string.fault_row_has_error else R.string.fault_row_normal),
                style = MaterialTheme.typography.bodyLarge
            )
            FaultLamp(isOn = isFaulty)
        }
    }
}

/** Full field list, modeled after MEMSFCR's "Live data (text)" screen. */
@Composable
private fun DetailedDataList(data: MemsData, modifier: Modifier = Modifier) {
    val enabledText = stringResource(R.string.status_enabled)
    val disabledText = stringResource(R.string.status_disabled)
    val rows = listOf(
        stringResource(R.string.detail_rpm) to "${data.engineRpm} rpm",
        stringResource(R.string.detail_coolant) to "${data.coolantTempC} °C",
        stringResource(R.string.detail_intake_temp) to "${data.intakeAirTempC} °C",
        stringResource(R.string.detail_map) to "${data.mapKpa} kPa",
        stringResource(R.string.detail_throttle_voltage) to "%.2f V".format(data.throttlePotVoltage),
        stringResource(R.string.detail_throttle_angle) to "%.0f °".format(data.throttleAngleDeg),
        stringResource(R.string.detail_idle_deviation) to "${data.idleSpeedDeviation}",
        stringResource(R.string.detail_lambda_voltage) to "%.0f mV".format(data.lambdaVoltageMv.toFloat()),
        stringResource(R.string.detail_iac_position) to "${data.iacPosition}",
        stringResource(R.string.detail_battery_voltage) to "%.1f V".format(data.batteryVoltage),
        stringResource(R.string.detail_afr) to "%.1f".format(data.airFuelRatio),
        stringResource(R.string.detail_lambda_freq) to "${data.lambdaSensorFrequency}",
        stringResource(R.string.detail_lambda_duty) to "${data.lambdaSensorDutyCycle}",
        stringResource(R.string.detail_lambda_status) to if (data.lambdaSensorStatus) enabledText else disabledText,
        stringResource(R.string.detail_closed_loop) to if (data.closedLoop) enabledText else disabledText,
        stringResource(R.string.detail_idle_base_pos) to "${data.idleBasePos}",
        stringResource(R.string.detail_idle_error) to "${data.idleError}",
        stringResource(R.string.metric_ignition) to "%.1f °".format(data.ignitionAdvanceDeg),
        stringResource(R.string.detail_fuel_trim_long) to "${data.longTermFuelTrim}",
        stringResource(R.string.detail_fuel_trim_short) to "${data.shortTermFuelTrim}",
        stringResource(R.string.detail_canister_duty) to "${data.carbonCanisterDutyCycle}",
        stringResource(R.string.detail_idle_switch) to if (data.idleSwitch) "ON" else "OFF",
        stringResource(R.string.detail_park_neutral) to if (data.parkNeutralSwitch) "ON" else "OFF",
        stringResource(R.string.detail_coil_time) to "%.0f µs".format(data.coilTimeMs * 1000f)
    )

    val faultRows = listOf(
        Triple(Icons.Filled.Thermostat, stringResource(R.string.fault_coolant_sensor), data.coolantTempSensorFault),
        Triple(Icons.Filled.Air, stringResource(R.string.fault_intake_sensor), data.intakeAirTempSensorFault),
        Triple(Icons.Filled.LocalGasStation, stringResource(R.string.fault_fuel_pump_circuit), data.fuelPumpCircuitFault),
        Triple(Icons.Filled.Bolt, stringResource(R.string.fault_throttle_pot_circuit), data.throttlePotCircuitFault)
    )

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(rows) { (label, value) -> DataRow(label, value) }
        item {
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.detail_fault_section_title), style = MaterialTheme.typography.titleMedium)
        }
        items(faultRows) { (icon, label, isFaulty) ->
            FaultRow(icon, label, isFaulty)
        }
    }
}

/**
 * Per-metric line charts over recent history, modeled after MEMSFCR's
 * Charts screen. [history] is the rolling in-memory sample window kept by
 * [GaugeViewModel] -- nothing is read from disk, so this only shows trend
 * since the app was connected, not the full log file.
 */
@Composable
private fun ChartsView(history: List<MemsData>, modifier: Modifier = Modifier) {
    if (history.size < 2) {
        Text(
            stringResource(R.string.charts_not_enough_data),
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier
        )
        return
    }

    val metrics = listOf(
        stringResource(R.string.chart_rpm) to history.map { it.engineRpm.toFloat() },
        stringResource(R.string.chart_coolant) to history.map { it.coolantTempC.toFloat() },
        stringResource(R.string.chart_intake) to history.map { it.intakeAirTempC.toFloat() },
        stringResource(R.string.chart_map) to history.map { it.mapKpa.toFloat() },
        stringResource(R.string.chart_battery) to history.map { it.batteryVoltage },
        stringResource(R.string.chart_ignition) to history.map { it.ignitionAdvanceDeg },
        stringResource(R.string.chart_lambda) to history.map { it.lambdaVoltageMv.toFloat() }
    )

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(metrics) { (label, values) ->
            Card(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    LineChart(values = values)
                }
            }
        }
    }
}

private val PREVIEW_SAMPLE_DATA = MemsData(
    engineRpm = 870,
    coolantTempC = 62,
    ambientTempC = -55,
    intakeAirTempC = 20,
    fuelTempC = -55,
    mapKpa = 35,
    batteryVoltage = 14.2f,
    throttlePotVoltage = 0.47f,
    throttleAngleDeg = 8.0f,
    airFuelRatio = 14.7f,
    idleSwitch = true,
    parkNeutralSwitch = true,
    coolantTempSensorFault = false,
    intakeAirTempSensorFault = false,
    fuelPumpCircuitFault = false,
    throttlePotCircuitFault = false,
    iacPosition = 45,
    idleSpeedDeviation = 0,
    idleError = 0,
    idleBasePos = 40,
    ignitionAdvanceDeg = 12.4f,
    coilTimeMs = 3.0f,
    lambdaVoltageMv = 450,
    lambdaSensorFrequency = 0,
    lambdaSensorDutyCycle = 0,
    lambdaSensorStatus = true,
    closedLoop = true,
    longTermFuelTrim = 0,
    shortTermFuelTrim = 0,
    carbonCanisterDutyCycle = 0,
    ecuVersion = EcuVersion.MEMS_1_3
)

@Preview(showBackground = true, name = "ライブデータ画面 (シンプル)")
@Composable
private fun GaugeScreenConnectedPreview() {
    RoverMemsTheme {
        GaugeScreenContent(
            connectionState = ConnectionState.CONNECTED,
            ecuVersion = EcuVersion.MEMS_1_3,
            data = PREVIEW_SAMPLE_DATA,
            onDisconnect = {}
        )
    }
}

@Preview(showBackground = true, name = "ライブデータ画面 (詳細)")
@Composable
private fun GaugeScreenDetailedPreview() {
    RoverMemsTheme {
        GaugeScreenContent(
            connectionState = ConnectionState.CONNECTED,
            ecuVersion = EcuVersion.MEMS_1_3,
            data = PREVIEW_SAMPLE_DATA,
            onDisconnect = {},
            initialMode = DisplayMode.DETAILED
        )
    }
}

@Preview(showBackground = true, name = "ライブデータ画面 (グラフ)")
@Composable
private fun GaugeScreenChartsPreview() {
    val sampleHistory = (0 until 40).map { i ->
        PREVIEW_SAMPLE_DATA.copy(
            engineRpm = (850 + 60 * kotlin.math.sin(i / 3.0)).toInt(),
            coolantTempC = 62 + i / 4,
            batteryVoltage = 14.2f + 0.1f * kotlin.math.sin(i / 5.0).toFloat()
        )
    }
    RoverMemsTheme {
        GaugeScreenContent(
            connectionState = ConnectionState.CONNECTED,
            ecuVersion = EcuVersion.MEMS_1_3,
            data = PREVIEW_SAMPLE_DATA,
            history = sampleHistory,
            onDisconnect = {},
            initialMode = DisplayMode.CHARTS
        )
    }
}

@Preview(showBackground = true, name = "ライブデータ画面 (アナログ)")
@Composable
private fun GaugeScreenAnalogPreview() {
    RoverMemsTheme {
        GaugeScreenContent(
            connectionState = ConnectionState.CONNECTED,
            ecuVersion = EcuVersion.MEMS_1_3,
            data = PREVIEW_SAMPLE_DATA,
            onDisconnect = {},
            initialMode = DisplayMode.ANALOG
        )
    }
}

@Preview(showBackground = true, name = "ライブデータ画面 (センサーエラーあり)")
@Composable
private fun GaugeScreenFaultPreview() {
    RoverMemsTheme {
        GaugeScreenContent(
            connectionState = ConnectionState.CONNECTED,
            ecuVersion = EcuVersion.MEMS_1_3,
            data = PREVIEW_SAMPLE_DATA.copy(
                coolantTempSensorFault = true,
                throttlePotCircuitFault = true
            ),
            onDisconnect = {},
            initialMode = DisplayMode.DETAILED
        )
    }
}

@Preview(showBackground = true, name = "ライブデータ画面 (接続前)")
@Composable
private fun GaugeScreenWaitingPreview() {
    RoverMemsTheme {
        GaugeScreenContent(
            connectionState = ConnectionState.CONNECTING,
            ecuVersion = EcuVersion.UNKNOWN,
            data = null,
            onDisconnect = {}
        )
    }
}

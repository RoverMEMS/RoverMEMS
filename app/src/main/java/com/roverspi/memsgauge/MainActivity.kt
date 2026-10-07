package com.roverspi.memsgauge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.roverspi.memsgauge.datasource.ConnectionState
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.ui.actuators.ActuatorTestScreen
import com.roverspi.memsgauge.ui.connect.ConnectScreen
import com.roverspi.memsgauge.ui.gauges.GaugeScreen
import com.roverspi.memsgauge.ui.logs.LogChartScreen
import com.roverspi.memsgauge.ui.logs.LogListScreen
import com.roverspi.memsgauge.ui.theme.RoverMemsTheme

private const val ROUTE_CONNECT = "connect"
private const val ROUTE_GAUGES = "gauges"
private const val ROUTE_ACTUATORS = "actuators"
private const val ROUTE_LOGS = "logs"
private const val ROUTE_LOG_CHART = "log_chart"

// ECUとの通信が無い状態がこの時間続いたら、画面つけっぱなしを解除する
private const val NO_SIGNAL_SCREEN_OFF_MS = 60_000L

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onResume() {
        super.onResume()
        // Re-syncs to the clock every time the app comes back to the
        // foreground (see NightModeManager.resetOverride's doc) so a sun/moon
        // tap from earlier doesn't stay stuck for the rest of the phone's
        // (possibly many-hours-long) process lifetime.
        NightModeManager.resetOverride()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate() -- shows the app icon on its brand
        // color (see Theme.RoverMEMS.Splash) from process start until the
        // first Compose frame draws, instead of a blank white window during
        // the several-second cold start on older/slower devices.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // This is a live dashboard meant to be glanced at while driving, not
        // interacted with -- without this, Android's normal screen timeout
        // would turn the display off mid-drive since nothing ever touches it.
        // The flag is released again when the ECU link has been gone for a
        // while (see the LaunchedEffect below), so the phone can sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as RoverMemsApp
        setContent {
            RoverMemsTheme {
                val navController = rememberNavController()
                var activeSource by remember { mutableStateOf<EcuDataSource>(app.mockDataSource) }

                // 車のキーを切ってATOMの電源が落ちた等で、ECUとの通信が一定時間
                // (NO_SIGNAL_SCREEN_OFF_MS)戻らなければ、画面つけっぱなしを解除する。
                // 解除後は端末の通常の画面消灯設定に従う(触っている間は消えない)。
                // 通信が戻れば(接続中・再接続中を含む)またつけっぱなしにする。
                LaunchedEffect(activeSource) {
                    activeSource.connectionState.collectLatest { state ->
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING) {
                            delay(NO_SIGNAL_SCREEN_OFF_MS)
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                }

                val effectiveNight by NightModeManager.effectiveNight.collectAsState()
                LaunchedEffect(effectiveNight) {
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (effectiveNight) {
                            NightModeManager.NIGHT_BRIGHTNESS
                        } else {
                            NightModeManager.DAY_BRIGHTNESS
                        }
                    }
                }

                NavHost(navController = navController, startDestination = ROUTE_CONNECT) {
                    composable(ROUTE_CONNECT) {
                        ConnectScreen(
                            app = app,
                            onConnected = { source ->
                                activeSource = source
                                navController.navigate(ROUTE_GAUGES)
                            },
                            onOpenLogs = { navController.navigate(ROUTE_LOGS) }
                        )
                    }
                    composable(ROUTE_GAUGES) {
                        GaugeScreen(
                            dataSource = activeSource,
                            onDisconnect = { navController.popBackStack() },
                            onOpenActuatorTests = { navController.navigate(ROUTE_ACTUATORS) },
                            onOpenLogs = { navController.navigate(ROUTE_LOGS) }
                        )
                    }
                    composable(ROUTE_ACTUATORS) {
                        ActuatorTestScreen(
                            dataSource = activeSource,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(ROUTE_LOGS) {
                        LogListScreen(
                            onBack = { navController.popBackStack() },
                            onOpenChart = { file ->
                                val encodedUri = Uri.encode(file.uri.toString())
                                val encodedName = Uri.encode(file.displayName)
                                navController.navigate("$ROUTE_LOG_CHART/$encodedUri/$encodedName")
                            }
                        )
                    }
                    composable(
                        route = "$ROUTE_LOG_CHART/{uri}/{fileName}",
                        arguments = listOf(
                            navArgument("uri") { type = NavType.StringType },
                            navArgument("fileName") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val uriArg = backStackEntry.arguments?.getString("uri").orEmpty()
                        val fileNameArg = backStackEntry.arguments?.getString("fileName").orEmpty()
                        LogChartScreen(
                            uri = Uri.parse(Uri.decode(uriArg)),
                            fileName = Uri.decode(fileNameArg),
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}

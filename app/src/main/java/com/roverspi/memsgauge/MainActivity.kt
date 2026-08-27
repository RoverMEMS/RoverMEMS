package com.roverspi.memsgauge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This is a live dashboard meant to be glanced at while driving, not
        // interacted with -- without this, Android's normal screen timeout
        // would turn the display off mid-drive since nothing ever touches it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as RoverMemsApp
        setContent {
            RoverMemsTheme {
                val navController = rememberNavController()
                var activeSource by remember { mutableStateOf<EcuDataSource>(app.mockDataSource) }

                val effectiveNight by NightModeManager.effectiveNight.collectAsState()
                LaunchedEffect(effectiveNight) {
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (effectiveNight) {
                            NightModeManager.NIGHT_BRIGHTNESS
                        } else {
                            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
                            }
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

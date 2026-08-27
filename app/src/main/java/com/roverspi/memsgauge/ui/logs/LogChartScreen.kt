package com.roverspi.memsgauge.ui.logs

import android.net.Uri
import androidx.compose.foundation.layout.Column
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.logging.LogFileParser
import com.roverspi.memsgauge.logging.LogSeries
import com.roverspi.memsgauge.ui.LanguageToggleButton
import com.roverspi.memsgauge.ui.gauges.LineChart

private val COLUMN_LABELS: Map<String, Int> = mapOf(
    "engineSpeed" to R.string.col_engine_speed,
    "waterTemp" to R.string.col_water_temp,
    "intakeAirTemp" to R.string.col_intake_air_temp,
    "throttleVoltage" to R.string.col_throttle_voltage,
    "manifoldPressure" to R.string.col_manifold_pressure,
    "idleBypassPos" to R.string.col_iac_position,
    "mainVoltage" to R.string.col_battery_voltage,
    "lambdaVoltage_mV" to R.string.col_lambda_voltage
)

/** Replays a saved CSV log as per-column charts -- the "look back at a past log" counterpart to グラフ's live view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogChartScreen(uri: Uri, fileName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var series by remember { mutableStateOf<List<LogSeries>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        series = LogFileParser.parse(context, uri)
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                actions = { LanguageToggleButton() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            when {
                !loaded -> Text(
                    stringResource(R.string.logchart_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(top = 12.dp)
                )
                series.isEmpty() -> Text(
                    stringResource(R.string.logchart_no_data),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(top = 12.dp)
                )
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(series) { s -> LogSeriesCard(s) }
                }
            }

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text(stringResource(R.string.action_back))
            }
        }
    }
}

@Composable
private fun LogSeriesCard(series: LogSeries) {
    Card(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val labelRes = COLUMN_LABELS[series.columnName]
            Text(
                if (labelRes != null) stringResource(labelRes) else series.columnName,
                style = MaterialTheme.typography.labelMedium
            )
            LineChart(values = series.values, timeAxisLabels = timeAxisLabels(series.timestampsMs))
        }
    }
}

// 30分単位くらいの粗さで十分読めればいいので、細かい目盛りは作らず
// 開始・中間2点・終了の4点だけラベルを打つ(ログが長くても短くても
// 均等に割り振られる)。
private const val TIME_AXIS_LABEL_COUNT = 4
private val TIME_AXIS_FORMAT = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun timeAxisLabels(timestampsMs: List<Long>): List<String> {
    if (timestampsMs.size < 2) return emptyList()
    return (0 until TIME_AXIS_LABEL_COUNT).map { i ->
        val fraction = i.toFloat() / (TIME_AXIS_LABEL_COUNT - 1)
        val index = (fraction * (timestampsMs.size - 1)).toInt()
        TIME_AXIS_FORMAT.format(Date(timestampsMs[index]))
    }
}

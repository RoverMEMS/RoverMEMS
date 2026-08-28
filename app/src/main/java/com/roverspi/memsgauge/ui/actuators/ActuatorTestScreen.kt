package com.roverspi.memsgauge.ui.actuators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.ui.LanguageToggleButton
import com.roverspi.memsgauge.ui.theme.RoverMemsTheme
import kotlinx.coroutines.delay

@Composable
fun ActuatorTestScreen(dataSource: EcuDataSource, onBack: () -> Unit) {
    val viewModel: ActuatorTestViewModel = viewModel(factory = ActuatorTestViewModel.Factory(dataSource))
    val engineRpm by viewModel.engineRpm.collectAsState()
    val result by viewModel.lastResult.collectAsState()

    ActuatorTestScreenContent(
        engineRpm = engineRpm,
        result = result,
        onRun = { control, turnOn -> viewModel.runCommand(control, turnOn) },
        onMessageShown = { viewModel.clearResultMessage() },
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActuatorTestScreenContent(
    engineRpm: Int?,
    result: ActuatorResult?,
    onRun: (ActuatorControl, Boolean) -> Unit,
    onMessageShown: () -> Unit,
    onBack: () -> Unit
) {
    // Only allow actuator tests while the engine is stopped, matching
    // MEMSGauge's and MEMSFCR's safety behavior. engineRpm == null means no
    // data has arrived yet, so tests stay disabled until we know for sure.
    val testsEnabled = engineRpm == 0

    if (result != null) {
        LaunchedEffect(result) {
            delay(2000)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.actuator_title)) },
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
            Card(modifier = Modifier.padding(vertical = 12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.actuator_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(if (testsEnabled) R.string.actuator_can_test else R.string.actuator_cannot_test),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (testsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            result?.let {
                val message = stringResource(
                    if (it.success) R.string.actuator_result_success else R.string.actuator_result_failure,
                    stringResource(it.control.labelRes)
                )
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(ACTUATOR_CONTROLS) { control ->
                    ActuatorRow(control, enabled = testsEnabled, onRun = onRun)
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
private fun ActuatorRow(control: ActuatorControl, enabled: Boolean, onRun: (ActuatorControl, Boolean) -> Unit) {
    val enabled = enabled && !control.alwaysDisabled
    Card(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(control.labelRes), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (control.offCommand == null) {
                    Button(onClick = { onRun(control, true) }, enabled = enabled) {
                        Text(stringResource(R.string.actuator_run_test))
                    }
                } else {
                    Button(onClick = { onRun(control, true) }, enabled = enabled) {
                        Text(stringResource(R.string.actuator_on))
                    }
                    Button(onClick = { onRun(control, false) }, enabled = enabled) {
                        Text(stringResource(R.string.actuator_off))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "部品テスト画面 (エンジン停止中)")
@Composable
private fun ActuatorTestScreenStoppedPreview() {
    RoverMemsTheme {
        ActuatorTestScreenContent(
            engineRpm = 0,
            result = null,
            onRun = { _, _ -> },
            onMessageShown = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, name = "部品テスト画面 (エンジン動作中)")
@Composable
private fun ActuatorTestScreenRunningPreview() {
    RoverMemsTheme {
        ActuatorTestScreenContent(
            engineRpm = 870,
            result = null,
            onRun = { _, _ -> },
            onMessageShown = {},
            onBack = {}
        )
    }
}

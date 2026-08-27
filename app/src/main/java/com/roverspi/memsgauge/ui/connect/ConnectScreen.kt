package com.roverspi.memsgauge.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.RoverMemsApp
import com.roverspi.memsgauge.ble.BlePermissions
import com.roverspi.memsgauge.ble.BleScanner
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.ui.LanguageToggleButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(app: RoverMemsApp, onConnected: (EcuDataSource) -> Unit) {
    val context = LocalContext.current
    val bleScanner = remember { BleScanner(context) }
    val viewModel: ConnectViewModel = viewModel(
        factory = ConnectViewModel.Factory(app.mockDataSource, app.bleDataSource, bleScanner, app.usbDataSource)
    )
    val mode by viewModel.sourceMode.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val usbDrivers by viewModel.usbDrivers.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            viewModel.selectMode(SourceMode.BLUETOOTH)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connect_title)) },
                actions = { LanguageToggleButton() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.connect_select_source), style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { viewModel.selectMode(SourceMode.SIMULATOR) }) {
                    Text(stringResource(R.string.connect_demo_mode))
                }
                Button(onClick = { viewModel.selectMode(SourceMode.USB) }) {
                    Text(stringResource(R.string.connect_usb))
                }
                Button(onClick = {
                    if (BlePermissions.hasRequiredPermissions(context)) {
                        viewModel.selectMode(SourceMode.BLUETOOTH)
                    } else {
                        permissionLauncher.launch(BlePermissions.requiredPermissions())
                    }
                }) {
                    Text(stringResource(R.string.connect_bluetooth))
                }
            }

            when (mode) {
                SourceMode.SIMULATOR -> {
                    Text(stringResource(R.string.connect_demo_description))
                    Button(
                        onClick = { viewModel.connectSimulator(onConnected) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Text(stringResource(if (connecting) R.string.connect_connecting else R.string.connect_start_demo))
                    }
                }
                SourceMode.USB -> {
                    if (usbDrivers.isEmpty()) {
                        Text(stringResource(R.string.connect_usb_not_found))
                    } else {
                        Text(stringResource(R.string.connect_usb_tap_device))
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            items(usbDrivers) { driver ->
                                TextButton(onClick = { viewModel.connectUsbDriver(driver, onConnected) }) {
                                    Text(driver.device.deviceName)
                                }
                            }
                        }
                    }
                }
                SourceMode.BLUETOOTH -> {
                    Text(stringResource(R.string.connect_ble_tap_device))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        items(devices) { device ->
                            TextButton(onClick = { viewModel.connectToDevice(device, onConnected) }) {
                                Text(device.name ?: device.address)
                            }
                        }
                    }
                }
            }

            if (connecting) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

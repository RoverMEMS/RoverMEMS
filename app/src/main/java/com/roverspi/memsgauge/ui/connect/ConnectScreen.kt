package com.roverspi.memsgauge.ui.connect

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.RoverMemsApp
import com.roverspi.memsgauge.ble.BlePermissions
import com.roverspi.memsgauge.ble.BleDevice
import com.roverspi.memsgauge.ble.BleScanner
import com.roverspi.memsgauge.ble.LastBleDevicePrefs
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.ui.LanguageToggleButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    app: RoverMemsApp,
    onConnected: (EcuDataSource) -> Unit,
    onOpenLogs: () -> Unit = {}
) {
    val context = LocalContext.current
    val bleScanner = remember { BleScanner(context) }
    val viewModel: ConnectViewModel = viewModel(
        factory = ConnectViewModel.Factory(app.mockDataSource, app.bleDataSource, bleScanner, app.usbDataSource)
    )
    val mode by viewModel.sourceMode.collectAsState()
    val connecting by viewModel.connecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    val usbDrivers by viewModel.usbDrivers.collectAsState()

    var autoConnect by remember { mutableStateOf(LastBleDevicePrefs.isAutoConnectEnabled(context)) }

    // つながったら機器を覚える(次回起動時の自動接続用)。
    fun connectBle(device: BleDevice) {
        viewModel.connectToDevice(device) { source ->
            LastBleDevicePrefs.save(context, device.address)
            onConnected(source)
        }
    }

    // アプリ起動後、最初の接続画面でだけ: 前回の機器が見えたら自動で接続する。
    // 見つからないまま・失敗した場合は今までどおり一覧から選ぶ。
    LaunchedEffect(Unit) {
        if (!LastBleDevicePrefs.autoConnectTried &&
            autoConnect &&
            LastBleDevicePrefs.lastAddress(context) != null &&
            BlePermissions.hasRequiredPermissions(context)
        ) {
            viewModel.selectMode(SourceMode.BLUETOOTH)
        }
    }
    LaunchedEffect(devices) {
        val last = LastBleDevicePrefs.lastAddress(context)
        if (!LastBleDevicePrefs.autoConnectTried && autoConnect && mode == SourceMode.BLUETOOTH && !connecting && last != null) {
            devices.firstOrNull { it.address == last }?.let {
                LastBleDevicePrefs.autoConnectTried = true
                connectBle(it)
            }
        }
    }

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
                title = { Text(stringResource(R.string.connect_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            Column {
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
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(
                                checked = autoConnect,
                                onCheckedChange = {
                                    autoConnect = it
                                    LastBleDevicePrefs.setAutoConnectEnabled(context, it)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.connect_auto_connect))
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            items(devices) { device ->
                                TextButton(onClick = { LastBleDevicePrefs.autoConnectTried = true; connectBle(device) }) {
                                    Text(device.name ?: device.address)
                                }
                            }
                        }
                    }
                }

                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }

                if (connectionError && !connecting) {
                    Text(
                        stringResource(R.string.connect_error_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            // 保存済みログは接続しなくても見られるように(以前はデモモードで
            // 接続してからメニューで開く必要があった)。
            OutlinedButton(
                onClick = onOpenLogs,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text(stringResource(R.string.connect_open_logs))
            }
        }
    }
}

package com.roverspi.memsgauge.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.roverspi.memsgauge.ble.BleDevice
import com.roverspi.memsgauge.ble.BleScanner
import com.roverspi.memsgauge.datasource.BleEcuDataSource
import com.roverspi.memsgauge.datasource.ConnectionState
import com.roverspi.memsgauge.datasource.EcuDataSource
import com.roverspi.memsgauge.datasource.MockEcuDataSource
import com.roverspi.memsgauge.datasource.UsbEcuDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SourceMode { SIMULATOR, BLUETOOTH, USB }

class ConnectViewModel(
    private val mockDataSource: MockEcuDataSource,
    private val bleDataSource: BleEcuDataSource,
    private val bleScanner: BleScanner,
    private val usbDataSource: UsbEcuDataSource
) : ViewModel() {

    private val _sourceMode = MutableStateFlow(SourceMode.SIMULATOR)
    val sourceMode: StateFlow<SourceMode> = _sourceMode.asStateFlow()

    val discoveredDevices: StateFlow<List<BleDevice>> = bleScanner.discoveredDevices

    private val _usbDrivers = MutableStateFlow<List<UsbSerialDriver>>(emptyList())
    val usbDrivers: StateFlow<List<UsbSerialDriver>> = _usbDrivers.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    fun selectMode(mode: SourceMode) {
        _sourceMode.value = mode
        bleScanner.stopScan()
        when (mode) {
            SourceMode.BLUETOOTH -> bleScanner.startScan()
            SourceMode.USB -> _usbDrivers.value = usbDataSource.listAvailableDrivers()
            SourceMode.SIMULATOR -> Unit
        }
    }

    fun connectSimulator(onConnected: (EcuDataSource) -> Unit) {
        _connecting.value = true
        viewModelScope.launch {
            mockDataSource.connect()
            _connecting.value = false
            onConnected(mockDataSource)
        }
    }

    fun connectToDevice(bleDevice: BleDevice, onConnected: (EcuDataSource) -> Unit) {
        _connecting.value = true
        bleScanner.stopScan()
        bleDataSource.setDevice(bleDevice.device)
        viewModelScope.launch {
            bleDataSource.connect()
            _connecting.value = false
            if (bleDataSource.connectionState.value == ConnectionState.CONNECTED) {
                onConnected(bleDataSource)
            }
        }
    }

    fun connectUsbDriver(driver: UsbSerialDriver, onConnected: (EcuDataSource) -> Unit) {
        _connecting.value = true
        usbDataSource.setDriver(driver)
        viewModelScope.launch {
            usbDataSource.connect()
            _connecting.value = false
            if (usbDataSource.connectionState.value == ConnectionState.CONNECTED) {
                onConnected(usbDataSource)
            }
        }
    }

    override fun onCleared() {
        bleScanner.stopScan()
    }

    class Factory(
        private val mockDataSource: MockEcuDataSource,
        private val bleDataSource: BleEcuDataSource,
        private val bleScanner: BleScanner,
        private val usbDataSource: UsbEcuDataSource
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ConnectViewModel(mockDataSource, bleDataSource, bleScanner, usbDataSource) as T
    }
}

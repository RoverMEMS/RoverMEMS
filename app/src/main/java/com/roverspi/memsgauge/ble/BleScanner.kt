package com.roverspi.memsgauge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BleDevice(val name: String?, val address: String, val device: BluetoothDevice)

/**
 * Wraps the platform BLE scanner. Scans unfiltered by service UUID, since
 * cheap HM-10/HC-08 clones don't reliably advertise it -- the connect screen
 * lets the user pick a discovered device by name/MAC address instead.
 * Callers must confirm [BlePermissions.hasRequiredPermissions] before
 * calling [startScan].
 */
class BleScanner(private val context: Context) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = BleDevice(result.device.name, result.device.address, result.device)
            val current = _discoveredDevices.value
            if (current.none { it.address == device.address }) {
                _discoveredDevices.value = current + device
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        _discoveredDevices.value = emptyList()
        adapter?.bluetoothLeScanner?.startScan(callback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(callback)
    }
}

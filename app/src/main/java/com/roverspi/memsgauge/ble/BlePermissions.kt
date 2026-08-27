package com.roverspi.memsgauge.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralizes the split between the pre-Android-12 Bluetooth permission set
 * (BLUETOOTH/BLUETOOTH_ADMIN/ACCESS_FINE_LOCATION) and the Android 12+ set
 * (BLUETOOTH_SCAN/BLUETOOTH_CONNECT), so the rest of the app can ask one
 * question: "do I have what I need to scan and connect right now?"
 */
object BlePermissions {

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    fun hasRequiredPermissions(context: Context): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
}

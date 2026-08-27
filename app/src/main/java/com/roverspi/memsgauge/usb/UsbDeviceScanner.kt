package com.roverspi.memsgauge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.roverspi.memsgauge.USB_PERMISSION"

/**
 * Lists attached USB-serial adapters (FTDI/CP210x/CH340/PL2303) and requests
 * runtime permission to use one -- the wired-cable equivalent of [com.roverspi.memsgauge.ble.BleScanner].
 * No manifest permission is needed for USB host mode; the user grants access
 * via the system dialog that [requestPermission] triggers.
 */
class UsbDeviceScanner(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun listAvailableDrivers(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (continuation.isActive) continuation.resume(granted)
                }
            }

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }

            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_USB_PERMISSION), piFlags
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    fun openConnection(device: UsbDevice): UsbDeviceConnection? = usbManager.openDevice(device)
}

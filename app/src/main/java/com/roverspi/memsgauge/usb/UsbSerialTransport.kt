package com.roverspi.memsgauge.usb

import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.roverspi.memsgauge.protocol.ByteTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executors

/**
 * [ByteTransport] implementation over a wired USB-serial adapter (FTDI,
 * CP210x, CH340, PL2303 -- whichever chip the user's existing cable uses,
 * auto-detected by usb-serial-for-android). Matches the wiring the Windows
 * MEMSGauge app uses: 9600 baud, 8 data bits, no parity, 1 stop bit, no flow
 * control (see librosco/src/setup.c).
 */
class UsbSerialTransport(private val port: UsbSerialPort) : ByteTransport {

    private val incomingBytes = Channel<Byte>(capacity = Channel.UNLIMITED)
    private val readExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var reading = false
    private var consecutiveErrors = 0

    /**
     * Fired when the read loop gives up after repeated I/O errors (cable
     * unplugged, driver fault) -- previously such errors were swallowed
     * silently and the loop just spun forever polling a dead port.
     */
    var onFatalError: (() -> Unit)? = null

    fun open(connection: UsbDeviceConnection) {
        port.open(connection)
        port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        Log.d(TAG, "USB port opened: $port")
        reading = true
        consecutiveErrors = 0
        readExecutor.execute {
            val buffer = ByteArray(256)
            while (reading) {
                val count = try {
                    port.read(buffer, READ_POLL_TIMEOUT_MS)
                } catch (e: IOException) {
                    // A benign poll timeout returns 0, it doesn't throw --
                    // an actual exception here means a real I/O fault.
                    consecutiveErrors++
                    Log.w(TAG, "USB read failed (consecutiveErrors=$consecutiveErrors)", e)
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        reading = false
                        Log.e(TAG, "USB read loop giving up after $MAX_CONSECUTIVE_ERRORS consecutive errors")
                        onFatalError?.invoke()
                    }
                    0
                }
                if (count > 0) consecutiveErrors = 0
                for (i in 0 until count) {
                    incomingBytes.trySendBlocking(buffer[i])
                }
            }
        }
    }

    override suspend fun write(bytes: ByteArray): Boolean =
        try {
            port.write(bytes, WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            Log.w(TAG, "USB write failed", e)
            false
        }

    override suspend fun readExactly(count: Int, timeoutMs: Long): ByteArray? =
        withTimeoutOrNull(timeoutMs) {
            ByteArray(count) { incomingBytes.receive() }
        }

    override fun flushStaleBytes() {
        while (incomingBytes.tryReceive().isSuccess) {
            // discard -- draining whatever is already queued
        }
    }

    fun close() {
        Log.d(TAG, "USB port closing")
        reading = false
        readExecutor.shutdownNow()
        try {
            port.close()
        } catch (e: Exception) {
            // already closed / device unplugged -- nothing more to do
        }
    }

    private companion object {
        const val TAG = "RoverMEMS"
        const val READ_POLL_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MS = 500
        const val MAX_CONSECUTIVE_ERRORS = 3
    }
}

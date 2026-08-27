package com.roverspi.memsgauge.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.roverspi.memsgauge.protocol.MemsData
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A subfolder name inside the public Downloads collection, used on every API level. */
private const val LOG_SUBFOLDER = "RoverMEMS"

/**
 * Writes a CSV log of live ECU data, one row per sample, to the public
 * Downloads/RoverMEMS folder -- visible in a file manager and over USB/MTP
 * from a PC, unlike app-private storage (Android/data/...), which recent
 * Android versions hide from both. Column set and header mirror MEMSGauge's
 * logger.cpp so existing spreadsheet workflows transfer directly.
 *
 * Android 10+ (API 29+) writes through MediaStore's Downloads collection,
 * which needs no storage permission. Android 8-9 (API 26-28, e.g. this
 * project's first real test tablet, API 27) write a plain file into the
 * public Downloads directory, which needs the legacy WRITE_EXTERNAL_STORAGE
 * permission (requested by the caller before calling [start]).
 */
class DataLogger(private val context: Context) {

    private val repository = LogFileRepository(context)

    private var writer: Writer? = null
    private var currentDisplayPath: String? = null
    private var currentUri: Uri? = null

    private val sampleTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val isLogging: Boolean
        get() = writer != null

    /** Human-readable location of the current (or last) log file, for display in the UI. */
    val currentLogPath: String?
        get() = currentDisplayPath

    fun start(): Boolean {
        if (isLogging) return true
        val fileName = "memsgauge_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"

        return try {
            val opened = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openViaMediaStore(fileName)
            } else {
                openViaLegacyFile(fileName)
            } ?: return false

            opened.writer.appendLine(HEADER)
            opened.writer.flush()
            writer = opened.writer
            currentDisplayPath = opened.displayPath
            currentUri = opened.uri
            repository.enforceRetention(MAX_LOG_FILES)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun logSample(data: MemsData) {
        val fileWriter = writer ?: return
        try {
            fileWriter.appendLine(
                listOf(
                    sampleTimeFormat.format(Date()),
                    data.engineRpm,
                    data.coolantTempC,
                    data.intakeAirTempC,
                    data.throttlePotVoltage,
                    data.mapKpa,
                    data.iacPosition,
                    data.batteryVoltage,
                    data.idleSwitch,
                    data.closedLoop,
                    data.lambdaVoltageMv
                ).joinToString(",")
            )
            fileWriter.flush()
        } catch (e: Exception) {
            // Keep the logging session alive even if a single write fails
            // (e.g. transient storage hiccup) -- the next sample will retry.
        }
    }

    fun stop() {
        try {
            writer?.close()
        } catch (e: Exception) {
            // already closed / file handle gone -- nothing more to do
        }
        writer = null
        currentUri = null
    }

    private class OpenedLog(val writer: Writer, val displayPath: String, val uri: Uri?)

    private fun openViaMediaStore(fileName: String): OpenedLog? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$LOG_SUBFOLDER")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val outputStream = resolver.openOutputStream(uri) ?: return null
        return OpenedLog(
            writer = OutputStreamWriter(outputStream),
            displayPath = "${context.getString(com.roverspi.memsgauge.R.string.download_folder_name)}/$LOG_SUBFOLDER/$fileName",
            uri = uri
        )
    }

    private fun openViaLegacyFile(fileName: String): OpenedLog? {
        @Suppress("DEPRECATION")
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            LOG_SUBFOLDER
        )
        if (!downloadsDir.exists() && !downloadsDir.mkdirs()) return null
        val file = File(downloadsDir, fileName)
        return OpenedLog(
            writer = FileWriter(file, true),
            displayPath = file.absolutePath,
            uri = null
        )
    }

    private companion object {
        // Matches MEMSGauge's logger.cpp column order exactly.
        const val HEADER = "#time,engineSpeed,waterTemp,intakeAirTemp,throttleVoltage," +
            "manifoldPressure,idleBypassPos,mainVoltage,idleswitch,closedloop,lambdaVoltage_mV"
        const val MAX_LOG_FILES = 20
    }
}

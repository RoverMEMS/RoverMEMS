package com.roverspi.memsgauge.logging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.roverspi.memsgauge.BuildConfig
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Drop-in replacement for [android.util.Log] -- every call site does
 * `import com.roverspi.memsgauge.logging.DebugLog as Log` instead of
 * `import android.util.Log`, so `Log.d(TAG, "...")` etc. keep working
 * unchanged everywhere, but every line also gets appended to a persistent
 * text file in Downloads/RoverMEMS/debug/ (one file per calendar day).
 *
 * `adb logcat`'s ring buffer is tiny (256KB on the test phone, measured with
 * `adb logcat -g`) and gets overwritten by ordinary system chatter within
 * minutes to hours -- useless for reviewing a connection problem from an
 * actual drive after the fact, since nobody has a laptop plugged in on the
 * road. This survives (until [MAX_DEBUG_LOG_FILES] retention prunes it), so
 * the exact sequence of connect/reconnect/error events from a drive can
 * still be read once back home, the same way [DataLogger]'s CSV already
 * survives for the sensor data itself.
 */
object DebugLog {
    private const val LOG_SUBFOLDER = "RoverMEMS/debug"
    private const val MAX_DEBUG_LOG_FILES = 14 // ~2 weeks at one file/day

    private lateinit var appContext: Context
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val lineTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Every call site here runs on whatever thread happened to hit an error
    // (USB read thread, BLE callback thread, coroutine dispatchers...) --
    // funnel all file I/O through one dedicated thread so writes never block
    // the caller and never race each other.
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var openDay: String? = null
    private var writer: Writer? = null

    /**
     * Call once from Application.onCreate(), before anything else can log.
     * A no-op outside debug builds -- release users shouldn't get files
     * silently written to their public Downloads folder for a diagnostic
     * feature they didn't ask for. [append] checks [appContext] before doing
     * any I/O, so leaving it uninitialized here is enough to disable
     * everything below; `Log.d/w/e` calls at the 39 call sites still reach
     * plain `android.util.Log` either way.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        appContext = context.applicationContext
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append('D', tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append('W', tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
        append('W', tag, "$msg - $tr")
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append('E', tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
        append('E', tag, "$msg - $tr")
    }

    private fun append(level: Char, tag: String, msg: String) {
        if (!::appContext.isInitialized) return
        val line = "${lineTimeFormat.format(Date())} $level/$tag: $msg"
        ioExecutor.execute {
            try {
                val w = ensureWriterForToday()
                w.appendLine(line)
                w.flush()
            } catch (e: Exception) {
                // Best-effort -- losing a debug line must never crash the app it's diagnosing.
            }
        }
    }

    private fun ensureWriterForToday(): Writer {
        val today = dayFormat.format(Date())
        val current = writer
        if (openDay == today && current != null) return current

        current?.let { runCatching { it.close() } }
        // 保持数の整理は今日のファイルを作る「前」に、かつ今日のファイルを
        // 除外して行う。以前は作った直後に整理していたため、まだ
        // DATE_MODIFIEDが入っていない新規ファイルが「最古」と誤判定されて
        // 自分で削除され、その日のログが丸ごと消えていた(09-26実機で確認)。
        runCatching { enforceRetention(excludeFileName = "debug_$today.txt") }
        val fresh = openTodayFile(today)
        writer = fresh
        openDay = today
        return fresh
    }

    private fun openTodayFile(day: String): Writer {
        val fileName = "debug_$day.txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openViaMediaStore(fileName)
        } else {
            openViaLegacyFile(fileName)
        }
    }

    /** Finds today's file if a previous app run already created it, so multiple runs append to one file per day. */
    private fun openViaMediaStore(fileName: String): Writer {
        val resolver = appContext.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LOG_SUBFOLDER/"
        val existingId = resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(relativePath, fileName),
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        val uri = if (existingId != null) {
            Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existingId.toString())
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("could not create $fileName")
        }
        // "wa" = write + append, so re-opening the same day's file across
        // process restarts doesn't truncate what's already there.
        val stream = resolver.openOutputStream(uri, "wa") ?: error("could not open $fileName")
        return OutputStreamWriter(stream)
    }

    private fun openViaLegacyFile(fileName: String): Writer {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_SUBFOLDER)
        if (!dir.exists()) dir.mkdirs()
        return FileWriter(File(dir, fileName), true)
    }

    /** Deletes debug log files beyond [MAX_DEBUG_LOG_FILES], oldest first, so the folder doesn't grow forever. */
    private fun enforceRetention(excludeFileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LOG_SUBFOLDER/"
            val entries = mutableListOf<Pair<Long, Uri>>() // (dateModified, uri)
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_MODIFIED),
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} != ?",
                arrayOf(relativePath, excludeFileName),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                    entries.add(cursor.getLong(dateCol) to uri)
                }
            }
            // 今日の分は除外済みなので、残す枠は1つ減らす
            entries.sortedByDescending { it.first }.drop(MAX_DEBUG_LOG_FILES - 1).forEach { (_, uri) ->
                runCatching { resolver.delete(uri, null, null) }
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), LOG_SUBFOLDER)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") && f.name != excludeFileName } ?: return
            files.sortedByDescending { it.lastModified() }.drop(MAX_DEBUG_LOG_FILES - 1).forEach { it.delete() }
        }
    }
}

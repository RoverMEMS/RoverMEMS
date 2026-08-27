package com.roverspi.memsgauge.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Lists the CSV logs [DataLogger] has written to Downloads/RoverMEMS, on either storage API. */
class LogFileRepository(private val context: Context) {

    fun listLogFiles(): List<LogFileEntry> {
        val files = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listViaMediaStore()
        } else {
            listViaLegacyFile()
        }
        return files.sortedByDescending { it.lastModifiedMs }
    }

    /** Deletes the oldest log files beyond [maxCount], so the log folder doesn't grow forever. */
    fun enforceRetention(maxCount: Int) {
        val files = listLogFiles() // newest first
        files.drop(maxCount).forEach { deleteFile(it) }
    }

    private fun deleteFile(entry: LogFileEntry) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(entry.uri, null, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    LOG_SUBFOLDER
                )
                File(downloadsDir, entry.displayName).delete()
            }
        } catch (e: Exception) {
            // Best-effort cleanup -- a failed delete just means one extra
            // file lingers until the next enforceRetention() call.
        }
    }

    private fun listViaMediaStore(): List<LogFileEntry> {
        val results = mutableListOf<LogFileEntry>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$LOG_SUBFOLDER/")
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                results.add(
                    LogFileEntry(
                        displayName = cursor.getString(nameCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        lastModifiedMs = cursor.getLong(dateCol) * 1000,
                        uri = uri
                    )
                )
            }
        }
        return results
    }

    private fun listViaLegacyFile(): List<LogFileEntry> {
        @Suppress("DEPRECATION")
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            LOG_SUBFOLDER
        )
        val files = downloadsDir.listFiles { file -> file.isFile && file.name.endsWith(".csv") }
            ?: return emptyList()
        return files.map { file ->
            LogFileEntry(
                displayName = file.name,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
                uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            )
        }
    }

    private companion object {
        const val LOG_SUBFOLDER = "RoverMEMS"
    }
}

package com.roverspi.memsgauge.logging

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Locale

data class LogSeries(val columnName: String, val values: List<Float>, val timestampsMs: List<Long>)

/**
 * Reads a CSV log written by [DataLogger] back out as per-column series, so
 * a saved log can be charted the same way live data is -- now with each
 * sample's actual clock time alongside its value, so [LogChartScreen] can
 * label the X axis with real times instead of a meaningless sample index.
 * Only numeric columns are parsed; idleswitch/closedloop are written as
 * "true"/"false" and skipped here.
 */
object LogFileParser {
    // Matches DataLogger's HEADER column order (minus the leading #time).
    private val NUMERIC_COLUMNS = listOf(
        "engineSpeed", "waterTemp", "intakeAirTemp", "throttleVoltage",
        "manifoldPressure", "idleBypassPos", "mainVoltage", "lambdaVoltage_mV"
    )

    // Matches DataLogger's sampleTimeFormat exactly ("HH:mm:ss.SSS").
    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun parse(context: Context, uri: Uri): List<LogSeries> {
        val rows = mutableListOf<List<String>>()
        var headerColumns: List<String>? = null

        try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("#") -> headerColumns = line.removePrefix("#").split(",")
                        headerColumns != null && line.isNotBlank() -> rows.add(line.split(","))
                    }
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val header = headerColumns ?: return emptyList()
        val timeIndex = header.indexOf("time")

        // ログは時刻(時分秒)だけで日付を持たないので、深夜0時をまたいだ場合は
        // 前のサンプルより時刻が巻き戻って見える -- そのときだけ+24時間して
        // 単調増加になるよう補正する(1回のログ記録中に日をまたぐのは稀だが念のため)。
        var dayOffsetMs = 0L
        var lastAdjusted = -1L
        val rowTimestamps: List<Long?> = rows.map { row ->
            val raw = row.getOrNull(timeIndex)?.let { runCatching { TIME_FORMAT.parse(it)?.time }.getOrNull() }
            if (raw == null) {
                null
            } else {
                if (lastAdjusted >= 0 && raw + dayOffsetMs < lastAdjusted) dayOffsetMs += 86_400_000L
                val adjusted = raw + dayOffsetMs
                lastAdjusted = adjusted
                adjusted
            }
        }

        return NUMERIC_COLUMNS.mapNotNull { name ->
            val colIndex = header.indexOf(name)
            if (colIndex < 0) return@mapNotNull null
            val values = mutableListOf<Float>()
            val timestamps = mutableListOf<Long>()
            rows.forEachIndexed { i, row ->
                val v = row.getOrNull(colIndex)?.toFloatOrNull()
                val t = rowTimestamps.getOrNull(i)
                if (v != null && t != null) {
                    values.add(v)
                    timestamps.add(t)
                }
            }
            if (values.isEmpty()) null else LogSeries(name, values, timestamps)
        }
    }
}

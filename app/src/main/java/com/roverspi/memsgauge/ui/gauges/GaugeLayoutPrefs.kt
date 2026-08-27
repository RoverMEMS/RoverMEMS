package com.roverspi.memsgauge.ui.gauges

import android.content.Context

private const val PREFS_NAME = "analog_gauge_layout"

/**
 * Persists which grid slot each analog gauge sits in -- like rearranging
 * icons on a phone home screen, a gauge only ever occupies one of a fixed
 * set of slot positions, never an arbitrary point. Stored per screen class
 * ([storageKey], e.g. "tablet" vs "phone") since the slot grid itself
 * differs by screen size.
 */
class GaugeLayoutPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns one gauge key (or null for an empty slot) per slot index, for
     * a grid of [totalSlots] slots. Falls back to [defaultOrder] (gauge keys
     * in slot-index order, defaults for the rest empty) the first time, or
     * whenever the slot count itself has changed (e.g. rotation/device swap).
     */
    fun getSlotAssignment(storageKey: String, defaultOrder: List<String>, totalSlots: Int): List<String?> {
        val saved = prefs.getString("slots_$storageKey", null)
        if (saved != null) {
            val parts = if (saved.isEmpty()) emptyList() else saved.split(",").map { it.ifEmpty { null } }
            if (parts.size == totalSlots) return parts
        }
        return List(totalSlots) { i -> defaultOrder.getOrNull(i) }
    }

    fun saveSlotAssignment(storageKey: String, assignment: List<String?>) {
        prefs.edit()
            .putString("slots_$storageKey", assignment.joinToString(",") { it ?: "" })
            .apply()
    }
}

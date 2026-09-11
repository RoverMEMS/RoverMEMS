package com.roverspi.memsgauge

import android.content.Context

/**
 * Manual "which way is up" preference for the アナログ screen's landscape
 * lock. Different people habitually mount/hold the tablet with the USB
 * port to the left or to the right, so this is a standing per-app setting
 * (like [LocaleManager]), not a per-session override (unlike
 * [NightModeManager]) -- once someone picks their orientation they expect
 * it to stay picked on the next drive.
 *
 * A SENSOR_LANDSCAPE (auto-follow-the-accelerometer) version of this was
 * tried first, but turned out to be more annoying in practice than a fixed
 * manual choice -- a mounted tablet/phone isn't perfectly level, and small
 * bumps/vibration while driving could nudge the sensor's orientation guess.
 * Back to an explicit, remembered choice.
 */
object OrientationManager {
    private const val PREFS_NAME = "app_orientation"
    private const val KEY_REVERSED = "analog_landscape_reversed"

    fun isReversed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REVERSED, false)

    /** Flips the stored preference and returns the new value. */
    fun toggle(context: Context): Boolean {
        val next = !isReversed(context)
        prefs(context).edit().putBoolean(KEY_REVERSED, next).apply()
        return next
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

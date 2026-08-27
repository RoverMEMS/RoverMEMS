package com.roverspi.memsgauge

import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the app's screen-brightness dimming (see MainActivity), not a color
 * theme -- night mode here means "same UI, dimmer screen" so it stays useful
 * while driving after dark. Defaults to auto-switching by time of day, with a
 * per-session manual override the driver can flip via a moon/sun button.
 * Unlike [LocaleManager], the override is intentionally in-memory only: it's
 * meant as a "just for now" correction, not a standing preference, so the
 * next app launch always re-evaluates from the clock.
 */
object NightModeManager {
    private const val NIGHT_START_HOUR = 18
    private const val NIGHT_END_HOUR = 6
    const val NIGHT_BRIGHTNESS = 0.12f
    private const val TICK_INTERVAL_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var override: Boolean? = null

    private val _effectiveNight = MutableStateFlow(isAutoNight())
    val effectiveNight: StateFlow<Boolean> = _effectiveNight.asStateFlow()

    init {
        // Re-checks the clock every minute so a long drive that crosses the
        // 18:00/6:00 boundary switches on its own, without relying on an
        // Activity lifecycle callback that a screen-locked phone might skip.
        scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                if (override == null) {
                    _effectiveNight.value = isAutoNight()
                }
            }
        }
    }

    /** Flips whatever mode is currently in effect, for this app session only. */
    fun toggleOverride() {
        val next = !_effectiveNight.value
        override = next
        _effectiveNight.value = next
    }

    private fun isAutoNight(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean =
        hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
}

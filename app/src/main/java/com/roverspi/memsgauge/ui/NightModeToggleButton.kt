package com.roverspi.memsgauge.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.roverspi.memsgauge.NightModeManager
import com.roverspi.memsgauge.R

/**
 * Moon/sun button for a TopAppBar's actions slot. Shows a moon while the
 * screen is at normal brightness (tap to dim it) and a sun while dimmed
 * (tap to brighten it), overriding [NightModeManager]'s time-of-day default
 * for the rest of this app session.
 */
@Composable
fun NightModeToggleButton() {
    val effectiveNight by NightModeManager.effectiveNight.collectAsState()
    IconButton(onClick = { NightModeManager.toggleOverride() }) {
        Icon(
            imageVector = if (effectiveNight) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = stringResource(R.string.night_mode_toggle)
        )
    }
}

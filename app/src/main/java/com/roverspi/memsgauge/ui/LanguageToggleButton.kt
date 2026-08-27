package com.roverspi.memsgauge.ui

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.roverspi.memsgauge.LocaleManager
import com.roverspi.memsgauge.R

/**
 * "EN"/"JA" switch for a TopAppBar's actions slot, styled with a visible
 * outline so it reads clearly as a tappable button rather than plain text.
 * Flips the saved language ([LocaleManager]) and recreates the Activity so
 * every screen's resources reload under the new locale.
 */
@Composable
fun LanguageToggleButton() {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            LocaleManager.toggleLanguage(context)
            (context as? Activity)?.recreate()
        },
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    ) {
        Text(stringResource(R.string.language_toggle))
    }
}

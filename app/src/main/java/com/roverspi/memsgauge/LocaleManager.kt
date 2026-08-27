package com.roverspi.memsgauge

import android.content.Context
import android.content.ContextWrapper
import java.util.Locale

/**
 * Manual per-app language switch, independent of the device's system
 * language. Persists the choice in SharedPreferences and wraps a Context's
 * Configuration with the chosen Locale -- applied in both [RoverMemsApp] and
 * [MainActivity]'s attachBaseContext so every resource lookup (including
 * Compose's stringResource) resolves against values/ (Japanese, default) or
 * values-en/ accordingly. Default language is Japanese.
 */
object LocaleManager {
    private const val PREFS_NAME = "app_language"
    private const val KEY_LANGUAGE = "language"
    const val JAPANESE = "ja"
    const val ENGLISH = "en"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, JAPANESE) ?: JAPANESE
    }

    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }

    fun toggleLanguage(context: Context): String {
        val next = if (getLanguage(context) == JAPANESE) ENGLISH else JAPANESE
        setLanguage(context, next)
        // Activity.recreate() (called by the toggle button right after this)
        // re-runs MainActivity.attachBaseContext, which fixes up the
        // recreated Activity's own resources. But RoverMemsApp.attachBaseContext
        // only ever runs once, at process start -- so anything holding an
        // applicationContext captured back then (GaugeViewModel, DataLogger)
        // would otherwise keep resolving strings in the stale language until
        // the whole process restarts. Mutate the shared applicationContext
        // Resources in place so those already-captured references pick up
        // the new language immediately too.
        updateSharedResources(context.applicationContext)
        return next
    }

    /** Wraps [base] with a Configuration overridden to the saved language. Call from attachBaseContext. */
    fun applyLocale(base: Context): ContextWrapper {
        val language = getLanguage(base)
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = base.resources.configuration
        config.setLocale(locale)
        val newContext = base.createConfigurationContext(config)
        return ContextWrapper(newContext)
    }

    @Suppress("DEPRECATION")
    private fun updateSharedResources(appContext: Context) {
        val locale = Locale(getLanguage(appContext))
        Locale.setDefault(locale)
        val resources = appContext.resources
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}

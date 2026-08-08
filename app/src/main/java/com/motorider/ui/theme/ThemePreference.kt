package com.motorider.ui.theme

import android.content.Context

enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * Persisted Light/Dark/System choice, so a rider can force dark at dusk regardless
 * of the system's own dark-mode timing. Plain SharedPreferences - the app already
 * uses this pattern for osmdroid config (see MotoRiderApplication), and a single
 * enum value doesn't warrant adding a DataStore dependency.
 */
object ThemePreference {
    private const val PREFS_NAME = "motorider_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    fun get(context: Context): ThemeMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, null) ?: return ThemeMode.SYSTEM
        return try {
            ThemeMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }
}

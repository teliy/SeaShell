package com.teliy.seashell

import android.content.Context

/**
 * Central place for the list of themes and their resources.
 */
object ThemeManager {

    private const val PREFS = "ui_prefs"
    private const val KEY_THEME = "theme"

    val themeIds = listOf("ocean", "graphite", "sunset", "forest", "amethyst", "rose")

    fun current(context: Context): String {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, null)
        return if (v in themeIds) v!! else "ocean"
    }

    fun set(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, id).apply()
    }

    fun styleRes(id: String): Int = when (id) {
        "graphite" -> R.style.Theme_SeaShell_Graphite
        "sunset" -> R.style.Theme_SeaShell_Sunset
        "forest" -> R.style.Theme_SeaShell_Forest
        "amethyst" -> R.style.Theme_SeaShell_Amethyst
        "rose" -> R.style.Theme_SeaShell_Rose
        else -> R.style.Theme_SeaShell_Ocean
    }

    fun nameRes(id: String): Int = when (id) {
        "graphite" -> R.string.theme_graphite
        "sunset" -> R.string.theme_sunset
        "forest" -> R.string.theme_forest
        "amethyst" -> R.string.theme_amethyst
        "rose" -> R.string.theme_rose
        else -> R.string.theme_ocean
    }

    fun cursorColorRes(id: String): Int = when (id) {
        "graphite" -> R.color.term_cursor_graphite
        "sunset" -> R.color.term_cursor_sunset
        "forest" -> R.color.term_cursor_forest
        "amethyst" -> R.color.term_cursor_amethyst
        "rose" -> R.color.term_cursor_rose
        else -> R.color.term_cursor_ocean
    }
}

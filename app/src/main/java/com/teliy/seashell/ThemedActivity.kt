package com.teliy.seashell

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Base activity that applies the user-selected color theme
 * before any view is inflated.
 */
abstract class ThemedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.styleRes(ThemeManager.current(this)))
        super.onCreate(savedInstanceState)
    }
}

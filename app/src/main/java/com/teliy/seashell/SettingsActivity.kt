package com.teliy.seashell

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.teliy.seashell.databinding.ActivitySettingsBinding
import java.util.Locale

class SettingsActivity : ThemedActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        buildThemeChips()
        buildLanguageChips()
        binding.btnClearKnownHosts.setOnClickListener { confirmClearKnownHosts() }
        bindAbout()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildThemeChips() {
        val current = ThemeManager.current(this)
        binding.themeGroup.removeAllViews()
        for (id in ThemeManager.themeIds) {
            val chip = Chip(this)
            chip.text = getString(ThemeManager.nameRes(id))
            chip.isCheckable = true
            chip.id = View.generateViewId()
            chip.isChecked = id == current
            chip.tag = id
            binding.themeGroup.addView(chip)
        }
        binding.themeGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checked) ?: return@setOnCheckedStateChangeListener
            val id = chip.tag as? String ?: return@setOnCheckedStateChangeListener
            if (id != ThemeManager.current(this)) {
                ThemeManager.set(this, id)
                recreate()
            }
        }
    }

    private fun buildLanguageChips() {
        val languages = listOf<Pair<String?, String>>(
            null to getString(R.string.lang_system),
            "en" to Locale.forLanguageTag("en").getDisplayLanguage(Locale.forLanguageTag("en")),
            "ru" to Locale.forLanguageTag("ru").getDisplayLanguage(Locale.forLanguageTag("ru")),
            "fr" to Locale.forLanguageTag("fr").getDisplayLanguage(Locale.forLanguageTag("fr")),
            "zh" to Locale.forLanguageTag("zh").getDisplayLanguage(Locale.forLanguageTag("zh")),
            "ar" to Locale.forLanguageTag("ar").getDisplayLanguage(Locale.forLanguageTag("ar")),
            "es" to Locale.forLanguageTag("es").getDisplayLanguage(Locale.forLanguageTag("es"))
        )
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        binding.langGroup.removeAllViews()
        for ((tag, label) in languages) {
            val chip = Chip(this)
            chip.text = label
            chip.isCheckable = true
            chip.id = View.generateViewId()
            chip.tag = tag
            val selected = if (tag == null) current.isEmpty() else current.startsWith(tag)
            chip.isChecked = selected
            binding.langGroup.addView(chip)
        }
        binding.langGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = group.findViewById<Chip>(checked) ?: return@setOnCheckedStateChangeListener
            val tag = chip.tag as? String
            val locales = if (tag == null) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            if (locales.toLanguageTags() != AppCompatDelegate.getApplicationLocales().toLanguageTags()) {
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
    }

    private fun confirmClearKnownHosts() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_known_hosts)
            .setMessage(R.string.clear_known_hosts_confirm)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                getSharedPreferences("known_hosts", Context.MODE_PRIVATE)
                    .edit().clear().apply()
                Toast.makeText(this, R.string.known_hosts_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun bindAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        binding.textVersion.text = getString(R.string.about_text, version)
    }
}

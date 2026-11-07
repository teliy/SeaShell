package com.teliy.seashell

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.teliy.seashell.databinding.ActivityMainBinding
import java.util.UUID

class MainActivity : ThemedActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SessionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = SessionAdapter(
            onClick = { openTerminal(it) },
            onLongClick = { showItemMenu(it) }
        )
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener {
            startActivity(Intent(this, SessionEditorActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = SessionStore.all(this)
        adapter.submit(list)
        binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openTerminal(session: SshSession) {
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putExtra(TerminalActivity.EXTRA_SESSION_ID, session.id)
        startActivity(intent)
    }

    private fun showItemMenu(session: SshSession) {
        val items = arrayOf(
            getString(R.string.menu_edit),
            getString(R.string.menu_duplicate),
            getString(R.string.menu_delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(session.displayName())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val i = Intent(this, SessionEditorActivity::class.java)
                        i.putExtra(SessionEditorActivity.EXTRA_SESSION_ID, session.id)
                        startActivity(i)
                    }
                    1 -> {
                        val copy = session.copy(
                            id = UUID.randomUUID().toString(),
                            name = session.name.ifBlank { session.host } + " " + getString(R.string.copy_suffix),
                            lastConnected = 0
                        )
                        SessionStore.upsert(this, copy)
                        refresh()
                    }
                    2 -> confirmDelete(session)
                }
            }
            .show()
    }

    private fun confirmDelete(session: SshSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, session.displayName()))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                SessionStore.delete(this, session.id)
                refresh()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_about -> {
            showAbout()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_text, version))
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }
}

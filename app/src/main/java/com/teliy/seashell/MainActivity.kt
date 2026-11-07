package com.teliy.seashell

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.teliy.seashell.databinding.ActivityMainBinding
import com.teliy.seashell.databinding.DialogQuickConnectBinding
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
        binding.btnQuickConnect.setOnClickListener { showQuickConnect() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        UpdateManager.onResume(this)
        UpdateManager.autoCheck(this)
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

    /**
     * Quick connect: the user enters just an address. The login (if not part
     * of the address) and the password are asked manually while connecting -
     * nothing is saved unless the user chooses to afterwards.
     */
    private fun showQuickConnect() {
        val dialogBinding = DialogQuickConnectBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_connect)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_connect, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            val input = dialogBinding.inputQuickAddress
            input.requestFocus()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                val parsed = parseQuickAddress(raw)
                if (parsed == null) {
                    dialogBinding.layoutQuickAddress.error =
                        getString(R.string.error_address_invalid)
                } else {
                    dialog.dismiss()
                    val (user, host, port) = parsed
                    if (user.isNullOrBlank()) {
                        askLogin(host, port)
                    } else {
                        startQuickSession(user, host, port)
                    }
                }
            }
            input.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                    true
                } else {
                    false
                }
            }
        }
        dialog.show()
    }

    /** Asks for the login name when the address did not include one. */
    private fun askLogin(host: String, port: Int) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        container.setPadding(pad, pad / 2, pad, 0)
        val til = TextInputLayout(this)
        til.hint = getString(R.string.field_username)
        val et = TextInputEditText(til.context)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        et.maxLines = 1
        til.addView(et)
        container.addView(til)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.prompt_login, host))
            .setView(container)
            .setPositiveButton(R.string.btn_connect, null)
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog.setOnShowListener {
            et.requestFocus()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val user = et.text?.toString()?.trim().orEmpty()
                if (user.isEmpty()) {
                    til.error = getString(R.string.error_username_required)
                } else {
                    dialog.dismiss()
                    startQuickSession(user, host, port)
                }
            }
        }
        dialog.show()
    }

    private fun startQuickSession(user: String, host: String, port: Int) {
        val session = SshSession(
            id = UUID.randomUUID().toString(),
            name = "",
            host = host,
            port = port,
            username = user,
            authType = SshSession.AUTH_PASSWORD,
            password = "" // asked manually during connect
        )
        val intent = Intent(this, TerminalActivity::class.java)
        intent.putExtra(TerminalActivity.EXTRA_SESSION, session)
        startActivity(intent)
    }

    /**
     * Parses "user@host:port", "host:port", "host", "[ipv6]" and
     * "user@[ipv6]:port". Returns null when the address is invalid.
     */
    private fun parseQuickAddress(raw: String): Triple<String?, String, Int>? {
        if (raw.isBlank()) return null
        var rest = raw.trim()
        if (!rest.contains("://") && rest.startsWith("ssh://", ignoreCase = true)) {
            rest = rest.substring(6)
        }

        var user: String? = null
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            user = rest.substring(0, at).trim()
            rest = rest.substring(at + 1).trim()
            if (user.isEmpty()) return null
        }

        var host: String
        var port = 22
        if (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) return null
            host = rest.substring(1, close)
            val tail = rest.substring(close + 1)
            if (tail.startsWith(":")) {
                port = tail.substring(1).toIntOrNull() ?: return null
            } else if (tail.isNotEmpty()) {
                return null
            }
        } else {
            val colon = rest.lastIndexOf(':')
            host = if (colon >= 0) {
                val p = rest.substring(colon + 1)
                if (p.isEmpty() || p.toIntOrNull() == null) return null
                port = p.toInt()
                rest.substring(0, colon)
            } else {
                rest
            }
        }
        host = host.trim()
        if (host.isEmpty() || port !in 1..65535) return null
        // reject values that are clearly not hostnames/addresses
        if (host.contains('/') || host.contains(' ') || host.contains('@')) return null
        return Triple(user, host, port)
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
        R.id.action_quick_connect -> {
            showQuickConnect()
            true
        }
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

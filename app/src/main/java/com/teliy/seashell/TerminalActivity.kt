package com.teliy.seashell

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.teliy.seashell.databinding.ActivityTerminalBinding
import org.json.JSONObject
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Interactive SSH terminal built on JSch and an embedded
 * xterm.js terminal rendered in a WebView.
 */
class TerminalActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION = "session_extra"
        private const val CONNECT_TIMEOUT = 15000
    }

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var session: SshSession
    private var sshSession: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    private val running = AtomicBoolean(false)
    private var fontSize = 14

    // Sticky modifiers for the extra keys bar (also read from the JS bridge thread)
    @Volatile private var ctrlArmed = false
    @Volatile private var ctrlLocked = false
    @Volatile private var altArmed = false
    @Volatile private var altLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        @Suppress("DEPRECATION")
        val direct =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent.getSerializableExtra(EXTRA_SESSION, SshSession::class.java)
            } else {
                intent.getSerializableExtra(EXTRA_SESSION) as? SshSession
            }
        val loaded = direct ?: id?.let { SessionStore.get(this, it) }
        if (loaded == null) {
            finish()
            return
        }
        session = loaded
        fontSize = session.fontSize

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestNotificationPermission()

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { confirmDisconnect() }
        binding.toolbar.title = session.displayName()
        binding.toolbar.subtitle = session.address()
        applyTerminalChrome()

        binding.btnRetry.setOnClickListener { retry() }
        binding.btnClose.setOnClickListener { finish() }

        setupWebView()
        setupExtraKeys()
        connect()
    }

    private fun themeAttrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun applyTerminalChrome() {
        val bg = themeAttrColor(R.attr.termBackground)
        binding.toolbar.setBackgroundColor(bg)
        binding.toolbar.setTitleTextColor(Color.WHITE)
        binding.toolbar.setSubtitleTextColor(0xB3FFFFFF.toInt())
        window.statusBarColor = bg
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.allowFileAccess = true
        ws.textZoom = 100
        binding.webView.addJavascriptInterface(Bridge(), "SeaShellBridge")
        binding.webView.webChromeClient = object : WebChromeClient() {}
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.requestFocus()
            }
        }
        binding.webView.loadUrl("file:///android_asset/term/terminal.html")
    }

    private fun showProgress(message: String) {
        binding.progressView.visibility = View.VISIBLE
        binding.textProgress.text = message
        binding.errorView.visibility = View.GONE
    }

    private fun hideProgress() {
        binding.progressView.visibility = View.GONE
    }

    private fun showError(message: String, closed: Boolean) {
        binding.progressView.visibility = View.GONE
        binding.errorView.visibility = View.VISIBLE
        binding.textError.text =
            if (closed) getString(R.string.term_closed) else getString(R.string.term_error, message)
    }

    private fun retry() {
        binding.errorView.visibility = View.GONE
        binding.webView.reload()
        connect()
    }

    private fun connect() {
        showProgress(getString(R.string.term_connecting, session.address()))
        val appContext = applicationContext
        thread(name = "ssh-session") {
            try {
                val jsch = JSch()

                // Private key authentication
                if (session.authType == SshSession.AUTH_KEY && session.keyUri.isNotBlank()) {
                    val bytes = contentResolver.openInputStream(Uri.parse(session.keyUri))
                        ?.use { it.readBytes() }
                    if (bytes == null || bytes.isEmpty()) {
                        throw IllegalStateException(getString(R.string.key_file_missing))
                    }
                    val pass = session.keyPassphrase
                    if (pass.isEmpty()) {
                        jsch.addIdentity("seashell-key", bytes, null, null)
                    } else {
                        jsch.addIdentity("seashell-key", bytes, null, pass.toByteArray())
                    }
                }

                val s = jsch.getSession(session.username, session.host, session.port)
                sshSession = s
                s.setUserInfo(ShellUserInfo(this@TerminalActivity))
                s.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
                s.setConfig("StrictHostKeyChecking", "ask")
                s.setHostKeyRepository(KnownHostsRepository(appContext))
                if (session.authType == SshSession.AUTH_PASSWORD && session.password.isNotEmpty()) {
                    s.setPassword(session.password)
                }
                // Empty stored password => JSch asks via UserInfo (prompt dialog).
                s.setServerAliveInterval(30000)
                // Tolerate a few lost probes instead of dropping the link after one
                s.setServerAliveCountMax(6)
                s.connect(CONNECT_TIMEOUT)

                // Remember successful connection time (only for saved sessions)
                SessionStore.get(appContext, session.id)?.let {
                    it.lastConnected = System.currentTimeMillis()
                    SessionStore.upsert(appContext, it)
                }

                // A quick-connect session is not stored - offer to save it
                if (SessionStore.get(appContext, session.id) == null) {
                    runOnUiThread { offerSaveSession() }
                }

                // Persist the host key after first acceptance
                try {
                    val repo = KnownHostsRepository(appContext)
                    val hk = s.hostKey
                    if (hk != null) {
                        val rawKey = android.util.Base64.decode(hk.key, android.util.Base64.NO_WRAP)
                        if (repo.check(hk.host, rawKey) == HostKeyRepository.NOT_INCLUDED) {
                            repo.add(hk, null)
                        }
                    }
                } catch (e: Exception) {
                    // non-fatal
                }

                val ch = s.openChannel("shell") as ChannelShell
                channel = ch
                ch.setPty(true)
                ch.setPtyType("xterm-256color")
                val input = ch.inputStream
                out = ch.outputStream
                ch.connect()

                runOnUiThread {
                    hideProgress()
                    // Keep the link alive with the screen off (wake locks)
                    TerminalService.start(this@TerminalActivity)
                }
                running.set(true)

                // Streaming UTF-8 decoder keeps state between chunks
                val dec = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction_REPLACE())
                    .onUnmappableCharacter(CodingErrorAction_REPLACE())
                val buf = ByteArray(8192)
                while (running.get()) {
                    val n = input.read(buf)
                    if (n > 0) {
                        val text = dec.decode(ByteBuffer.wrap(buf, 0, n)).toString()
                        if (text.isNotEmpty()) {
                            val js = JSONObject.quote(text)
                            runOnUiThread {
                                if (running.get()) {
                                    binding.webView.evaluateJavascript(
                                        "window.terminalWrite($js)", null
                                    )
                                }
                            }
                        }
                    } else if (n < 0) {
                        break
                    }
                }
                if (running.getAndSet(false)) {
                    TerminalService.stop(this@TerminalActivity)
                    runOnUiThread { showError("", closed = true) }
                }
            } catch (e: Exception) {
                if (running.getAndSet(false)) {
                    // was connected and the stream died - surface the broken link
                    TerminalService.stop(this@TerminalActivity)
                    runOnUiThread { showError("", closed = true) }
                } else {
                    val msg = friendlyError(e)
                    runOnUiThread { showError(msg, closed = false) }
                }
            } finally {
                try { channel?.disconnect() } catch (e: Exception) {}
                try { sshSession?.disconnect() } catch (e: Exception) {}
            }
        }
    }

    /** Offers to add a quick-connect session to the saved list. */
    private fun offerSaveSession() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_connection_title)
            .setMessage(getString(R.string.save_connection_msg, session.address()))
            .setPositiveButton(R.string.btn_save) { _, _ ->
                if (session.name.isBlank()) {
                    session.name = session.host
                }
                SessionStore.upsert(this, session)
                Toast.makeText(this, R.string.session_saved, Toast.LENGTH_SHORT).show()
                binding.toolbar.subtitle = session.address()
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    /** Translates low-level JSch messages into user-friendly strings. */
    private fun friendlyError(e: Exception): String {
        val raw = e.message ?: e.javaClass.simpleName
        return when {
            raw.contains("Auth cancel", ignoreCase = true) ->
                getString(R.string.auth_cancelled)
            raw.contains("Auth fail", ignoreCase = true) ||
                raw.contains("USERAUTH fail", ignoreCase = true) ->
                getString(R.string.auth_failed)
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("timed out", ignoreCase = true) ->
                getString(R.string.error_timeout)
            raw.contains("UnknownHost", ignoreCase = true) ||
                e is java.net.UnknownHostException ->
                getString(R.string.error_unknown_host)
            raw.contains("Connection refused", ignoreCase = true) ||
                raw.contains("Network is unreachable", ignoreCase = true) ||
                raw.contains("ECONNREFUSED", ignoreCase = true) ->
                getString(R.string.error_refused)
            else -> raw
        }
    }

    private fun CodingErrorAction_REPLACE(): java.nio.charset.CodingErrorAction =
        java.nio.charset.CodingErrorAction.REPLACE

    // region Extra keys bar

    private fun ctrlActive() = ctrlArmed || ctrlLocked
    private fun altActive() = altArmed || altLocked

    /** Wires all keys of the extra keys bar. */
    private fun setupExtraKeys() {
        bindModifier(binding.keyCtrl, ctrl = true)
        bindModifier(binding.keyAlt, ctrl = false)

        binding.keyEsc.setOnClickListener { v -> tapKey(v) { sendRaw("\u001B") } }
        binding.keyTab.setOnClickListener { v -> tapKey(v) { sendRaw("\t") } }
        binding.keyHome.setOnClickListener { v -> tapKey(v) { sendRaw("\u001B[H") } }
        binding.keyEnd.setOnClickListener { v -> tapKey(v) { sendRaw("\u001B[F") } }
        binding.keyPgUp.setOnClickListener { v -> tapKey(v) { sendRaw("\u001B[5~") } }
        binding.keyPgDn.setOnClickListener { v -> tapKey(v) { sendRaw("\u001B[6~") } }

        binding.keyLeft.setOnClickListener { v -> tapKey(v) { sendArrow('D') } }
        binding.keyUp.setOnClickListener { v -> tapKey(v) { sendArrow('A') } }
        binding.keyDown.setOnClickListener { v -> tapKey(v) { sendArrow('B') } }
        binding.keyRight.setOnClickListener { v -> tapKey(v) { sendArrow('C') } }
    }

    /** Haptic feedback + action for a plain key tap. */
    private inline fun tapKey(v: View, action: () -> Unit) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        action()
    }

    /** Ctrl / Alt are one-shot on tap and latch (lock) on long-press. */
    private fun bindModifier(view: View, ctrl: Boolean) {
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (ctrl) {
                when {
                    ctrlLocked -> { ctrlLocked = false; ctrlArmed = false }
                    ctrlArmed -> ctrlArmed = false
                    else -> ctrlArmed = true
                }
            } else {
                when {
                    altLocked -> { altLocked = false; altArmed = false }
                    altArmed -> altArmed = false
                    else -> altArmed = true
                }
            }
            refreshModifierVisuals()
        }
        view.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (ctrl) {
                ctrlLocked = true; ctrlArmed = true
            } else {
                altLocked = true; altArmed = true
            }
            refreshModifierVisuals()
            true
        }
    }

    private fun refreshModifierVisuals() {
        binding.keyCtrl.isSelected = ctrlActive()
        binding.keyAlt.isSelected = altActive()
        binding.keyCtrl.text = if (ctrlLocked) "CTRL •" else "CTRL"
        binding.keyAlt.text = if (altLocked) "ALT •" else "ALT"
    }

    /** One-shot modifiers release after the next key press. */
    private fun consumeOneShot() {
        var changed = false
        if (ctrlArmed && !ctrlLocked) { ctrlArmed = false; changed = true }
        if (altArmed && !altLocked) { altArmed = false; changed = true }
        if (changed) runOnUiThread { refreshModifierVisuals() }
    }

    /** Sends raw bytes to the SSH channel. */
    private fun sendRaw(text: String) {
        val o = out ?: return
        try {
            synchronized(o) {
                o.write(text.toByteArray(Charsets.UTF_8))
                o.flush()
            }
        } catch (e: Exception) {
            // Broken pipe etc. - stop writing into a dead socket silently
            onLinkBroken()
        }
    }

    /** The TCP link is dead: stop the keep-alive service and show Reconnect. */
    private fun onLinkBroken() {
        if (running.getAndSet(false)) {
            TerminalService.stop(this)
            runOnUiThread { showError("", closed = true) }
        }
    }

    /** Asks for notification access on Android 13+ (foreground service notice). */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001
            )
        }
    }

    /** Arrow keys, honoring armed Ctrl/Alt (xterm modifiers 5=Ctrl, 3=Alt). */
    private fun sendArrow(code: Char) {
        val seq = when {
            ctrlActive() -> "\u001B[1;5$code"
            altActive() -> "\u001B[1;3$code"
            else -> "\u001B[$code"
        }
        sendRaw(seq)
        consumeOneShot()
    }

    /**
     * Applies armed modifiers to a single character typed on the soft
     * keyboard: Ctrl maps letters to control codes, Alt prefixes ESC (Meta).
     */
    private fun transformInput(data: String): String {
        if (data.length != 1) return data
        val c = data[0]
        val ctrl = ctrlActive()
        val alt = altActive()
        if (!ctrl && !alt) return data

        var result = data
        if (ctrl) {
            result = when {
                c in 'a'..'z' -> (c.code - 96).toChar().toString()
                c in 'A'..'Z' -> (c.code - 64).toChar().toString()
                c == ' ' || c == '@' -> "\u0000"
                c == '[' -> "\u001B"
                c == '\\' -> "\u001C"
                c == ']' -> "\u001D"
                c == '^' -> "\u001E"
                c == '_' -> "\u001F"
                c == '?' -> "\u007F"
                else -> data
            }
        }
        if (alt) result = "\u001B$result"
        consumeOneShot()
        return result
    }

    // endregion

    private inner class Bridge {
        @JavascriptInterface
        fun getConfig(): String {
            val themeId = ThemeManager.current(this@TerminalActivity)
            val cursor = String.format("#%06X", 0xFFFFFF and getColor(ThemeManager.cursorColorRes(themeId)))
            return JSONObject()
                .put("fontSize", fontSize)
                .put("cursor", cursor)
                .put("cursorBlink", true)
                .toString()
        }

        @JavascriptInterface
        fun onData(data: String) {
            val payload = transformInput(data)
            sendRaw(payload)
        }

        @JavascriptInterface
        fun onResize(cols: Int, rows: Int) {
            try {
                channel?.setPtySize(cols, rows, 0, 0)
            } catch (e: Exception) {
                // channel may not be ready yet
            }
        }
    }

    private fun pasteClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        val o = out ?: return
        thread {
            try {
                synchronized(o) {
                    o.write(text.toByteArray(Charsets.UTF_8))
                    o.flush()
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun changeFont(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(8, 28)
        session.fontSize = fontSize
        SessionStore.upsert(this, session)
        binding.webView.evaluateJavascript("window.terminalFontSize($fontSize)", null)
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disconnect_title)
            .setMessage(R.string.disconnect_confirm)
            .setPositiveButton(R.string.btn_disconnect) { _, _ -> finish() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_terminal, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_paste -> { pasteClipboard(); true }
        R.id.action_font_up -> { changeFont(1); true }
        R.id.action_font_down -> { changeFont(-1); true }
        R.id.action_disconnect -> { confirmDisconnect(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        running.set(false)
        TerminalService.stop(this)
        try { channel?.disconnect() } catch (e: Exception) {}
        try { sshSession?.disconnect() } catch (e: Exception) {}
        super.onDestroy()
    }
}

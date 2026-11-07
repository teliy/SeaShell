package com.teliy.seashell

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
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
 * Interactive SSH terminal (PuTTY-style) built on JSch and an embedded
 * xterm.js terminal rendered in a WebView.
 */
class TerminalActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val CONNECT_TIMEOUT = 15000
    }

    private lateinit var binding: ActivityTerminalBinding
    private lateinit var session: SshSession
    private var sshSession: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    private val running = AtomicBoolean(false)
    private var fontSize = 14

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        val loaded = id?.let { SessionStore.get(this, it) }
        if (loaded == null) {
            finish()
            return
        }
        session = loaded
        fontSize = session.fontSize

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { confirmDisconnect() }
        binding.toolbar.title = session.displayName()
        binding.toolbar.subtitle = session.address()
        applyTerminalChrome()

        binding.btnRetry.setOnClickListener { retry() }
        binding.btnClose.setOnClickListener { finish() }

        setupWebView()
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
                // Empty stored password => JSch asks via UserInfo (PuTTY-like prompt).
                s.setServerAliveInterval(30000)
                s.connect(CONNECT_TIMEOUT)

                // Remember successful connection time
                SessionStore.get(appContext, session.id)?.let {
                    it.lastConnected = System.currentTimeMillis()
                    SessionStore.upsert(appContext, it)
                }

                // Persist the host key after first acceptance (PuTTY-like)
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

                runOnUiThread { hideProgress() }
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
                    runOnUiThread { showError("", closed = true) }
                }
            } catch (e: Exception) {
                if (running.getAndSet(false)) {
                    // was connected and stream died - ignore, already handled
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
            val o = out ?: return
            try {
                synchronized(o) {
                    o.write(data.toByteArray(Charsets.UTF_8))
                    o.flush()
                }
            } catch (e: Exception) {
                // connection may be closing
            }
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
        try { channel?.disconnect() } catch (e: Exception) {}
        try { sshSession?.disconnect() } catch (e: Exception) {}
        super.onDestroy()
    }
}

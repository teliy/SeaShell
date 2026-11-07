package com.teliy.seashell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

/**
 * In-app self-update: checks GitHub Releases of this project, offers to
 * download the new APK and launches the system installer.
 */
object UpdateManager {

    private const val LATEST_URL = "https://api.github.com/repos/teliy/SeaShell/releases/latest"
    private const val PREFS = "update_prefs"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_PENDING_APK = "pending_apk"
    private const val AUTO_CHECK_INTERVAL_MS = 8 * 60 * 60 * 1000L // 8 hours

    /** Information about the latest published release. */
    data class ReleaseInfo(
        val version: String,
        val notes: String?,
        val apkUrl: String,
        val apkName: String
    )

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * Silent auto-check used from MainActivity. Runs at most once per
     * [AUTO_CHECK_INTERVAL_MS] and never reports errors to the user.
     */
    fun autoCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0) < AUTO_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check(activity, manual = false)
    }

    /** User-triggered check ("Check for updates" in Settings). */
    fun check(activity: Activity, manual: Boolean) {
        val ctx = activity.applicationContext
        Thread {
            val release = try {
                fetchLatestRelease()
            } catch (e: Exception) {
                null
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (release == null) {
                    if (manual) {
                        Toast.makeText(ctx, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@runOnUiThread
                }
                if (isNewer(currentVersion(ctx), release.version)) {
                    showOfferDialog(activity, release)
                } else if (manual) {
                    Toast.makeText(ctx, R.string.update_latest, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "SeaShell-Updater")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v").removePrefix("V")
            if (tag.isEmpty()) return null
            val assets = json.optJSONArray("assets") ?: return null
            var apkUrl: String? = null
            var apkName: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkName = name
                    break
                }
            }
            if (apkUrl.isNullOrEmpty()) return null
            val notes = json.optString("body", "").takeIf { it.isNotBlank() }
            return ReleaseInfo(tag, notes, apkUrl, apkName!!)
        } finally {
            conn.disconnect()
        }
    }

    /** Compares dotted versions like 1.10.2 > 1.9.0 */
    fun isNewer(installed: String, candidate: String): Boolean {
        val a = installed.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val b = candidate.split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun showOfferDialog(activity: Activity, release: ReleaseInfo) {
        val ctx = activity.applicationContext
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = buildString {
            append(ctx.getString(R.string.update_available_msg, release.version, currentVersion(ctx)))
            release.notes?.let {
                append("\n\n")
                append(it.take(600))
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                downloadAndInstall(activity, release)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, release: ReleaseInfo) {
        val ctx = activity.applicationContext
        val latch = CountDownLatch(1)
        var progressDialog: AlertDialog? = null
        activity.runOnUiThread {
            progressDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_downloading_title)
                .setMessage(ctx.getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .show()
        }

        Thread {
            var target: File? = null
            var success = false
            var error: String? = null
            try {
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, release.apkName)
                val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("User-Agent", "SeaShell-Updater")
                    conn.instanceFollowRedirects = true
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = ((done * 100) / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        activity.runOnUiThread {
                                            progressDialog?.setMessage(
                                                ctx.getString(R.string.update_downloading, pct)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                target = file
                success = file.length() > 0
                if (!success) error = "empty file"
            } catch (e: Exception) {
                error = e.message
            }

            activity.runOnUiThread {
                try {
                    progressDialog?.dismiss()
                } catch (e: Exception) {
                }
                if (success && target != null) {
                    // Store the path in case the user has to grant the
                    // "install unknown apps" permission first.
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString(KEY_PENDING_APK, target.absolutePath).apply()
                    installOrAskPermission(activity, target)
                } else {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.update_download_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                latch.countDown()
            }
            latch.await()
        }.start()
    }

    /**
     * Launches the installer, or opens system settings when the user has not
     * allowed installing apps from this source yet (Android 8+).
     */
    fun installOrAskPermission(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(activity, R.string.update_install_permission, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${activity.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            return
        }
        install(activity, apk)
    }

    /** Called from onResume: retries a pending install after the permission was granted. */
    fun onResume(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING_APK, null) ?: return
        val apk = File(path)
        if (!apk.exists()) {
            prefs.edit().remove(KEY_PENDING_APK).apply()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            return // still not granted; the user can retry from Settings
        }
        prefs.edit().remove(KEY_PENDING_APK).apply()
        install(activity, apk)
    }

    private fun install(activity: Activity, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

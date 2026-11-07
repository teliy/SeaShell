package com.teliy.seashell

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo

/**
 * PuTTY-like storage of accepted SSH host keys.
 * Keys are kept in a private preferences file, one entry per host.
 */
class KnownHostsRepository(context: Context) : HostKeyRepository() {

    private val prefs =
        context.applicationContext.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    override fun check(host: String?, key: ByteArray?): Int {
        if (host.isNullOrEmpty() || key == null) return NOT_INCLUDED
        val saved = prefs.getString(host, null) ?: return NOT_INCLUDED
        val given = Base64.encodeToString(key, Base64.NO_WRAP)
        return if (saved == given) INCLUDED else CHANGED
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {
        if (hostkey == null) return
        try {
            prefs.edit().putString(hostkey.host, hostkey.key).apply()
        } catch (e: Exception) {
            // ignore persistence failures
        }
    }

    override fun remove(host: String?, type: String?) {
        if (host != null) prefs.edit().remove(host).apply()
    }

    override fun remove(host: String?, type: String?, strict: Boolean) {
        remove(host, type)
    }

    override fun removeAll() {
        prefs.edit().clear().apply()
    }

    override fun getHostKey(): Array<String> =
        prefs.all.keys.toTypedArray()

    override fun getID(): String = "SeaShell known hosts"
}

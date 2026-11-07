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
class KnownHostsRepository(context: Context) : HostKeyRepository {

    private val prefs =
        context.applicationContext.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    override fun check(host: String?, key: ByteArray?): Int {
        if (host.isNullOrEmpty() || key == null) return HostKeyRepository.NOT_INCLUDED
        val saved = prefs.getString(host, null) ?: return HostKeyRepository.NOT_INCLUDED
        val given = Base64.encodeToString(key, Base64.NO_WRAP)
        return if (saved == given) HostKeyRepository.OK else HostKeyRepository.CHANGED
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

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        remove(host, type)
    }

    override fun getKnownHostsRepositoryID(): String = "SeaShell known hosts"

    /**
     * Full listing is not needed by the app; JSch uses check()/add() during
     * the connection flow.
     */
    override fun getHostKey(): Array<HostKey> = arrayOf()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = arrayOf()
}

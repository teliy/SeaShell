package com.teliy.seashell

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists the list of SSH sessions in SharedPreferences (JSON via Gson).
 */
object SessionStore {

    private const val PREFS = "sessions"
    private const val KEY = "list"

    private val gson = Gson()
    private val type = object : TypeToken<MutableList<SshSession>>() {}.type

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): MutableList<SshSession> {
        val json = prefs(context).getString(KEY, null) ?: return mutableListOf()
        return try {
            gson.fromJson<SshSession>(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun get(context: Context, id: String): SshSession? =
        all(context).firstOrNull { it.id == id }

    fun save(context: Context, sessions: List<SshSession>) {
        prefs(context).edit().putString(KEY, gson.toJson(sessions)).apply()
    }

    fun upsert(context: Context, session: SshSession) {
        val list = all(context)
        val idx = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session else list.add(session)
        save(context, list)
    }

    fun delete(context: Context, id: String) {
        save(context, all(context).filter { it.id != id })
    }
}

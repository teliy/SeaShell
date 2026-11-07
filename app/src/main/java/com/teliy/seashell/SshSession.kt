package com.teliy.seashell

import java.io.Serializable
import java.util.UUID

/**
 * An SSH connection entry: either saved in the session list or created
 * on the fly for a quick, unsaved connection.
 */
data class SshSession(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var host: String = "",
    var port: Int = 22,
    var username: String = "",
    var authType: String = AUTH_PASSWORD,
    var password: String = "",
    var keyUri: String = "",
    var keyLabel: String = "",
    var keyPassphrase: String = "",
    var fontSize: Int = 14,
    var lastConnected: Long = 0
) : Serializable {
    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }

    fun displayName(): String = name.ifBlank { host }

    fun address(): String = "$username@$host:$port"
}

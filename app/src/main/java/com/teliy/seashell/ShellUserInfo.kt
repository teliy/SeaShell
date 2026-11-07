package com.teliy.seashell

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.util.concurrent.CountDownLatch

/**
 * JSch UserInfo implementation that shows real dialogs on the UI thread
 * and blocks the SSH thread until the user answers.
 *
 * Handles: host-key confirmation, password prompts, key passphrase prompts
 * and keyboard-interactive (PAM / 2FA) challenges.
 */
class ShellUserInfo(private val activity: Activity) : UserInfo, UIKeyboardInteractive {

    private var password: String? = null
    private var passphrase: String? = null

    private fun askYesNo(message: String): Boolean {
        val latch = CountDownLatch(1)
        var answer = false
        activity.runOnUiThread {
            val dlg: AlertDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.host_key_title)
                .setMessage(message)
                .setPositiveButton(R.string.host_key_accept) { _: DialogInterface, _: Int ->
                    answer = true
                    latch.countDown()
                }
                .setNegativeButton(R.string.host_key_reject) { _: DialogInterface, _: Int -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            dlg.show()
        }
        latch.await()
        return answer
    }

    /**
     * Shows a single-secret input dialog. Returns null when the user cancels.
     */
    private fun askForSecret(message: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        activity.runOnUiThread {
            val container = LinearLayout(activity)
            container.orientation = LinearLayout.VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            container.setPadding(pad, pad / 2, pad, 0)
            val til = TextInputLayout(activity)
            val et = TextInputEditText(til.context)
            et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            et.setText(password ?: "")
            til.addView(et)
            container.addView(til)

            val dlg: AlertDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(message)
                .setView(container)
                .setPositiveButton(R.string.btn_ok) { _, _ ->
                    result = et.text?.toString().orEmpty()
                    latch.countDown()
                }
                .setNegativeButton(R.string.btn_cancel) { _: DialogInterface, _: Int -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            dlg.show()
            et.requestFocus()
        }
        latch.await()
        return result
    }

    override fun promptYesNo(message: String?): Boolean = askYesNo(message ?: "")

    override fun promptPassword(message: String?): Boolean {
        // JSch may ask several times (wrong password, empty store, retries) -
        // always show a fresh dialog.
        val prompt = message ?: activity.getString(R.string.prompt_password)
        val answer = askForSecret(prompt) ?: return false
        password = answer
        return true
    }

    override fun promptPassphrase(message: String?): Boolean {
        val prompt = message ?: activity.getString(R.string.prompt_passphrase)
        val answer = askForSecret(prompt) ?: return false
        passphrase = answer
        return true
    }

    override fun getPassword(): String? = password

    override fun getPassphrase(): String? = passphrase

    override fun showMessage(message: String?) {
        activity.runOnUiThread {
            if (message.isNullOrBlank()) return@runOnUiThread
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_name)
                .setMessage(message)
                .setPositiveButton(R.string.btn_ok, null)
                .show()
        }
    }

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompts: Array<out String>?,
        echo: BooleanArray?
    ): Array<String>? {
        if (prompts == null || prompts.isEmpty()) return null
        val latch = CountDownLatch(1)
        var result: Array<String>? = null
        activity.runOnUiThread {
            val container = LinearLayout(activity)
            container.orientation = LinearLayout.VERTICAL
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            container.setPadding(pad, pad / 2, pad, 0)
            val fields = mutableListOf<TextInputEditText>()
            for ((i, p) in prompts.withIndex()) {
                val til = TextInputLayout(activity)
                til.hint = p
                val et = TextInputEditText(til.context)
                et.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                // First prompt of PAM is usually the password - pre-fill with the
                // password the user may have already typed.
                if (i == 0 && echo?.getOrNull(0) == false) {
                    et.setText(password ?: "")
                }
                til.addView(et)
                container.addView(til)
                fields.add(et)
            }
            val dlg: AlertDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(name ?: destination ?: "")
                .setMessage(instruction?.takeIf { it.isNotBlank() })
                .setView(container)
                .setPositiveButton(R.string.btn_ok) { _, _ ->
                    result = fields.map { it.text?.toString() ?: "" }.toTypedArray()
                    latch.countDown()
                }
                .setNegativeButton(R.string.btn_cancel) { _: DialogInterface, _: Int -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            dlg.show()
        }
        latch.await()
        return result
    }
}

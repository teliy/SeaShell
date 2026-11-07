package com.teliy.seashell

import android.app.Activity
import android.content.DialogInterface
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.util.concurrent.CountDownLatch

/**
 * JSch UserInfo implementation that shows real dialogs on the UI thread
 * and blocks the SSH thread until the user answers (PuTTY-like prompts).
 */
class ShellUserInfo(private val activity: Activity) : UserInfo, UIKeyboardInteractive {

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

    override fun promptYesNo(message: String?): Boolean = askYesNo(message ?: "")

    override fun promptPassword(message: String?): Boolean = false

    override fun promptPassphrase(message: String?): Boolean = false

    override fun getPassword(): String? = null

    override fun getPassphrase(): String? = null

    override fun showMessage(message: String?) {
        activity.runOnUiThread {
            Toast.makeText(activity, message ?: "", Toast.LENGTH_SHORT).show()
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
            val pad = (16 * activity.resources.displayMetrics.density).toInt()
            container.setPadding(pad, pad / 2, pad, 0)
            val fields = mutableListOf<TextInputEditText>()
            for (p in prompts) {
                val til = TextInputLayout(activity)
                til.hint = p
                val et = TextInputEditText(til.context)
                et.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
                .setNegativeButton(R.string.btn_cancel) { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .create()
            dlg.show()
        }
        latch.await()
        return result
    }
}

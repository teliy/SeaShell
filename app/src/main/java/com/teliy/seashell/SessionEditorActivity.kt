package com.teliy.seashell

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButtonToggleGroup
import com.teliy.seashell.databinding.ActivitySessionEditorBinding

class SessionEditorActivity : ThemedActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private lateinit var binding: ActivitySessionEditorBinding
    private lateinit var editing: SshSession

    private val pickKey = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // some providers do not support persistable grants
            }
            editing.keyUri = uri.toString()
            editing.keyLabel = uri.lastPathSegment ?: uri.toString()
            binding.inputKey.setText(editing.keyLabel)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        editing = if (id != null) SessionStore.get(this, id) ?: SshSession(id = id) else SshSession()
        supportActionBar?.setTitle(
            if (id != null) R.string.editor_edit_title else R.string.editor_new_title
        )

        populate()
        setupAuthToggle()
        binding.btnChooseKey.setOnClickListener { pickKey.launch(arrayOf("*/*")) }
        binding.inputKey.setOnClickListener { pickKey.launch(arrayOf("*/*")) }
        binding.btnSave.setOnClickListener { onSave() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun populate() {
        binding.inputName.setText(editing.name)
        binding.inputHost.setText(editing.host)
        binding.inputPort.setText(
            if (editing.port > 0) editing.port.toString() else "22"
        )
        binding.inputUser.setText(editing.username)
        if (editing.keyUri.isNotBlank()) {
            binding.inputKey.setText(editing.keyLabel.ifBlank { editing.keyUri })
        }
        binding.inputPassword.setText(editing.password)
        binding.inputPassphrase.setText(editing.keyPassphrase)
    }

    private fun setupAuthToggle() {
        binding.authGroup.addOnButtonCheckedListener(
            object : MaterialButtonToggleGroup.OnButtonCheckedListener {
                override fun onButtonChecked(
                    group: MaterialButtonToggleGroup?,
                    checkedId: Int,
                    isChecked: Boolean
                ) {
                    if (!isChecked) return
                    when (checkedId) {
                        R.id.btnAuthPassword -> {
                            editing.authType = SshSession.AUTH_PASSWORD
                            binding.layoutPassword.visibility = View.VISIBLE
                            binding.layoutKey.visibility = View.GONE
                        }
                        R.id.btnAuthKey -> {
                            editing.authType = SshSession.AUTH_KEY
                            binding.layoutPassword.visibility = View.GONE
                            binding.layoutKey.visibility = View.VISIBLE
                        }
                    }
                }
            })
        binding.authGroup.check(
            if (editing.authType == SshSession.AUTH_KEY) R.id.btnAuthKey else R.id.btnAuthPassword
        )
    }

    private fun onSave() {
        val host = binding.inputHost.text?.toString()?.trim().orEmpty()
        val user = binding.inputUser.text?.toString()?.trim().orEmpty()
        val port = binding.inputPort.text?.toString()?.trim()?.toIntOrNull()

        var ok = true
        if (host.isEmpty()) {
            binding.layoutHost.error = getString(R.string.error_host_required)
            ok = false
        } else {
            binding.layoutHost.error = null
        }
        if (user.isEmpty()) {
            binding.layoutUser.error = getString(R.string.error_username_required)
            ok = false
        } else {
            binding.layoutUser.error = null
        }
        if (port == null || port !in 1..65535) {
            binding.layoutPort.error = getString(R.string.error_port_invalid)
            ok = false
        } else {
            binding.layoutPort.error = null
        }
        if (!ok) return

        editing.name = binding.inputName.text?.toString()?.trim().orEmpty()
        editing.host = host
        editing.username = user
        editing.port = port!!
        editing.password = binding.inputPassword.text?.toString().orEmpty()
        editing.keyPassphrase = binding.inputPassphrase.text?.toString().orEmpty()

        SessionStore.upsert(this, editing)
        Toast.makeText(this, R.string.session_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

package com.deuty.agent.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deuty.agent.AgentApplication
import com.deuty.agent.R
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.databinding.ActivitySettingsBinding
import com.deuty.agent.di.AgentContainer
import com.deuty.agent.platform.AgentAccessibilityService
import com.deuty.agent.platform.PermissionStatus
import kotlinx.coroutines.launch

/**
 * Settings (requirements 15, 20, 23).
 *
 * Also the accessibility service's declared `settingsActivity`, so it is what the
 * system opens when the user taps the service's gear. That is why the permission
 * status is the first thing on the screen: the person arriving from there is
 * deciding whether to trust this app with their screen.
 *
 * Every control writes through [com.deuty.agent.platform.PreferencesStore.update],
 * which is the only path that touches both the flow and the disk, so the UI and the
 * engine can never disagree about a setting.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var container: AgentContainer

    /** Guards against feeding a value back into the field that produced it. */
    private var suppress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        container = AgentApplication.containerOf(this)

        binding.backButton.setOnClickListener { finish() }
        binding.openAccessibility.setOnClickListener { PermissionStatus.openAccessibilitySettings(this) }
        binding.openOverlay.setOnClickListener { PermissionStatus.openOverlaySettings(this) }
        binding.openInspector.setOnClickListener {
            startActivity(Intent(this, InspectorActivity::class.java))
        }

        bindSwitch(binding.confirmMedium) { value, s -> s.copy(confirmMedium = value) }
        bindSwitch(binding.blockSensitive) { value, s -> s.copy(blockSensitiveScreens = value) }
        bindSwitch(binding.allowPassword) { value, s -> s.copy(allowPasswordEntry = value) }
        bindSwitch(binding.askAmbiguous) { value, s -> s.copy(askWhenAmbiguous = value) }
        bindSwitch(binding.redact) { value, s -> s.copy(redact = value) }
        bindSwitch(binding.keepHistory) { value, s -> s.copy(keepHistory = value) }
        bindSwitch(binding.allowScreenshots) { value, s -> s.copy(allowScreenshots = value) }
        bindSwitch(binding.debugMode) { value, s -> s.copy(debugMode = value) }
        bindSwitch(binding.logEvents) { value, s -> s.copy(logEvents = value) }

        bindNumber(binding.maxActions, 1..500) { value, s -> s.copy(maxActions = value) }
        bindNumber(binding.maxSeconds, 10..7200) { value, s -> s.copy(maxTaskSeconds = value) }
        bindNumber(binding.maxRetries, 0..10) { value, s -> s.copy(maxRetriesPerStep = value) }

        binding.clearHistory.setOnClickListener {
            confirm(
                "Clear task history?",
                "Finished tasks are removed. The chat log and saved workflows stay."
            ) {
                lifecycleScope.launch {
                    container.tasks.clearHistory()
                    toast(R.string.pref_cleared)
                }
            }
        }
        binding.clearWorkflows.setOnClickListener {
            confirm("Delete all workflows?", "Every recorded workflow is removed from this device.") {
                lifecycleScope.launch {
                    val count = container.workflows.clearAll()
                    toast(R.string.pref_cleared)
                    container.log.i("settings", "deleted $count workflow(s)")
                }
            }
        }
        binding.deleteAll.setOnClickListener {
            confirm(
                "Delete all local data?",
                "Tasks, history, chat, workflows and settings. This cannot be undone."
            ) {
                lifecycleScope.launch {
                    runCatching { container.stateManager.clearAll() }
                    runCatching { container.prefs.clearEverything() }
                    toast(R.string.pref_cleared)
                    render(container.prefs.current())
                }
            }
        }

        binding.versionLine.text = getString(R.string.settings_version, versionName())
        binding.plannerName.text = container.plannerProvider.describe()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.prefs.settings.collect { render(it) } }
            }
        }
        renderPermissions()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
    }

    private fun bindSwitch(view: SwitchCompat, apply: (Boolean, AgentSettings) -> AgentSettings) {
        view.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            container.prefs.update { current -> apply(checked, current) }
        }
    }

    /**
     * Numbers are committed while typing, clamped to a sane range. Committing on
     * focus-loss instead would silently discard an edit when the user leaves via
     * the back button - the classic way a settings screen lies to you.
     */
    private fun bindNumber(view: EditText, range: IntRange, apply: (Int, AgentSettings) -> AgentSettings) {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (suppress) return
                val parsed = s?.toString()?.trim()?.toIntOrNull() ?: return
                val clamped = parsed.coerceIn(range.first, range.last)
                container.prefs.update { current -> apply(clamped, current) }
            }
        })
    }

    private fun render(settings: AgentSettings) {
        suppress = true
        try {
            binding.confirmMedium.isChecked = settings.confirmMedium
            binding.blockSensitive.isChecked = settings.blockSensitiveScreens
            binding.allowPassword.isChecked = settings.allowPasswordEntry
            binding.askAmbiguous.isChecked = settings.askWhenAmbiguous
            binding.redact.isChecked = settings.redact
            binding.keepHistory.isChecked = settings.keepHistory
            binding.allowScreenshots.isChecked = settings.allowScreenshots
            binding.debugMode.isChecked = settings.debugMode
            binding.logEvents.isChecked = settings.logEvents
            setNumber(binding.maxActions, settings.maxActions)
            setNumber(binding.maxSeconds, settings.maxTaskSeconds)
            setNumber(binding.maxRetries, settings.maxRetriesPerStep)
        } finally {
            suppress = false
        }
    }

    private fun setNumber(view: EditText, value: Int) {
        val text = value.toString()
        if (view.text?.toString() != text) view.setText(text)
    }

    private fun renderPermissions() {
        val enabled = PermissionStatus.isAccessibilityEnabled(this)
        val connected = AgentAccessibilityService.isConnected
        binding.accessibilityStatus.text = when {
            connected -> "Accessibility: on and connected"
            enabled -> "Accessibility: enabled, service not connected yet"
            else -> "Accessibility: off - the agent cannot see or act"
        }
        binding.overlayStatus.text =
            "Display over other apps: " +
                getString(
                    if (PermissionStatus.canDrawOverlays(this)) {
                        R.string.perm_status_granted
                    } else R.string.perm_status_missing
                )
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "dev"
    }.getOrDefault("dev")

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> onYes() }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun toast(res: Int) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
    }
}

package com.deuty.agent.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.deuty.agent.AgentApplication
import com.deuty.agent.R
import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.ChatMessage
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.model.TaskView
import com.deuty.agent.databinding.ActivityMainBinding
import com.deuty.agent.di.AgentContainer
import com.deuty.agent.platform.AgentAccessibilityService
import com.deuty.agent.platform.PermissionStatus
import kotlinx.coroutines.launch

/**
 * The agent chat screen (requirement 17).
 *
 * It is a *view* of the engine, never a participant: it collects two StateFlows
 * and renders them, and every control calls straight into the engine. That is why
 * rotating the device, backgrounding the app or killing the activity cannot
 * disturb a running task - the task lives in the process-scoped container, driven
 * by the accessibility service.
 *
 * Quick actions and example chips are built in code rather than XML: they are a
 * list of (label, instruction) pairs, and duplicating them in XML would mean the
 * two lists disagree the moment someone adds a command.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var container: AgentContainer
    private val chatAdapter = ChatAdapter()

    private var renderedAskId: String? = null
    private var lastMessageCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        container = AgentApplication.containerOf(this)

        setupChat()
        setupChips()
        setupInput()
        setupControls()
        setupNavigation()
        observe()
    }

    override fun onResume() {
        super.onResume()
        renderPermissions()
        // The engine may have published while we were away.
        renderStatus(container.engine.status.value)
        renderMessages(container.engine.messages.value)
    }

    // ------------------------------------------------------------------ setup

    private fun setupChat() {
        binding.chatList.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.chatList.adapter = chatAdapter
        binding.chatList.itemAnimator = null
    }

    private fun setupChips() {
        val quick = listOf(
            getString(R.string.quick_read_screen) to "read the screen",
            getString(R.string.quick_back) to "back",
            getString(R.string.quick_home) to "home",
            getString(R.string.quick_scroll_down) to "scroll down",
            getString(R.string.quick_record) to RECORD_TOGGLE,
            getString(R.string.inspector_sandbox) to SANDBOX
        )
        quick.forEach { (label, command) ->
            binding.quickRow.addView(chip(label) { onQuickAction(command) })
        }

        val examples = listOf(
            R.string.example_open_settings,
            R.string.example_read_screen,
            R.string.example_find_tap,
            R.string.example_rename_move,
            R.string.example_stuck
        )
        examples.forEach { res ->
            binding.examplesRow.addView(chip(getString(res), outline = true) {
                // Examples fill the input instead of firing: the interesting ones
                // ("organise my PDFs") are exactly the ones a user wants to edit
                // before sending.
                binding.input.setText(getString(res))
                binding.input.setSelection(binding.input.text?.length ?: 0)
                binding.input.requestFocus()
            })
        }
    }

    private fun chip(label: String, outline: Boolean = false, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        val padH = (14 * density).toInt()
        val padV = (8 * density).toInt()
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(getColor(if (outline) R.color.text_primary else R.color.on_brand))
            setBackgroundResource(if (outline) R.drawable.bg_input else R.drawable.bg_chip)
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * density).toInt()
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { onClick() }
        }
    }

    private fun setupInput() {
        binding.sendButton.setOnClickListener { send() }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                send()
                true
            } else {
                false
            }
        }
    }

    private fun setupControls() {
        binding.stopButton.setOnClickListener { container.engine.stopTask() }
        binding.pauseButton.setOnClickListener { container.engine.togglePause() }
        binding.retryButton.setOnClickListener { container.engine.retryStep() }
        binding.skipButton.setOnClickListener { container.engine.skipStep() }
    }

    private fun setupNavigation() {
        binding.openSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.openInspector.setOnClickListener { startActivity(Intent(this, InspectorActivity::class.java)) }
        binding.openWorkflows.setOnClickListener { startActivity(Intent(this, WorkflowsActivity::class.java)) }
        binding.openAccessibility.setOnClickListener { PermissionStatus.openAccessibilitySettings(this) }
        binding.openOverlay.setOnClickListener { PermissionStatus.openOverlaySettings(this) }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { container.engine.status.collect { renderStatus(it) } }
                launch { container.engine.messages.collect { renderMessages(it) } }
            }
        }
    }

    // ------------------------------------------------------------------ input

    private fun send() {
        val text = binding.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.input.setText("")
        container.engine.submit(text)
    }

    private fun onQuickAction(command: String) {
        when (command) {
            RECORD_TOGGLE ->
                if (container.recorder.isRecording) promptForWorkflowName() else container.engine.startRecording()

            SANDBOX -> container.engine.runSandboxDemo()
            else -> container.engine.submit(command)
        }
    }

    /** Naming a recording before it is saved beats renaming it afterwards. */
    private fun promptForWorkflowName() {
        val field = android.widget.EditText(this).apply {
            hint = getString(R.string.workflow_record_hint)
            setPadding(48, 32, 48, 32)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(field)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.workflow_record_title)
            .setView(wrapper)
            .setPositiveButton(R.string.workflow_save) { _, _ ->
                container.engine.stopRecording(field.text?.toString()?.trim())
            }
            .setNegativeButton(R.string.workflow_discard) { _, _ ->
                container.engine.cancelRecording()
            }
            // Cancelling the dialog must NOT cancel the recording: the capsule
            // still shows Stop, and losing a recording to a stray back press is
            // exactly the kind of data loss the spec calls out.
            .show()
    }

    // ----------------------------------------------------------------- render

    private fun renderMessages(messages: List<ChatMessage>) {
        if (messages.size == lastMessageCount) return
        lastMessageCount = messages.size
        val index = chatAdapter.submit(messages)
        if (index >= 0) binding.chatList.scrollToPosition(index)
        binding.examplesScroll.visibility =
            if (messages.size > 3) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun renderStatus(status: AgentStatus) {
        renderPermissions()
        binding.statusLine.text = statusLine(status)
        renderTask(status)
    }

    private fun statusLine(status: AgentStatus): String = when {
        !status.accessibilityEnabled -> getString(R.string.status_accessibility_off)
        !status.overlayEnabled -> getString(R.string.status_overlay_off)
        status.recording -> getString(R.string.workflow_recording) +
            " (${status.recordedSteps})"
        status.task == null -> getString(R.string.status_idle)
        else -> status.task.headline
    }

    private fun renderTask(status: AgentStatus) {
        val task = status.task
        if (task == null) {
            binding.taskCard.visibility = android.view.View.GONE
            renderedAskId = null
            return
        }
        binding.taskCard.visibility = android.view.View.VISIBLE
        binding.taskState.text = task.state.name +
            if (task.state == TaskState.RUNNING && status.recording) " · recording" else ""
        binding.taskState.setTextColor(getColor(stateColor(task.state)))
        binding.taskGoal.text = task.goal
        binding.taskBudget.text = "${task.actionsUsed}/${task.maxActions} actions · ${task.elapsedMs / 1000}s"
        binding.taskProgress.progress = (task.progress * 100).toInt()
        binding.taskSteps.text = renderSteps(task)
        binding.taskSteps.visibility =
            if (task.steps.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val paused = task.state == TaskState.PAUSED
        binding.pauseButton.setText(if (paused) R.string.action_resume else R.string.action_pause)
        val finished = task.state.isTerminal
        binding.stopButton.isEnabled = !finished
        binding.stopButton.alpha = if (finished) 0.4f else 1f
        binding.pauseButton.isEnabled = !finished
        binding.retryButton.isEnabled = true
        binding.skipButton.isEnabled = !finished

        renderAsk(status.ask)
    }

    private fun renderSteps(task: TaskView): String = task.steps.mapIndexed { index, step ->
        val marker = when (step.status) {
            StepStatus.DONE -> "\u2713"
            StepStatus.SKIPPED -> "\u2192"
            StepStatus.FAILED -> "\u2717"
            StepStatus.RUNNING -> "\u25B6"
            StepStatus.WAITING_FOR_USER -> "\u2026"
            StepStatus.PENDING -> if (step.isCurrent) "\u25B7" else "\u00B7"
        }
        buildString {
            append(index + 1).append(". ").append(marker).append(' ').append(step.title)
            step.note?.let { append("  (").append(it).append(')') }
        }
    }.joinToString("\n")

    private fun renderAsk(ask: AskUserRequest?) {
        if (ask == null) {
            binding.taskAsk.visibility = android.view.View.GONE
            binding.askOptions.visibility = android.view.View.GONE
            binding.askOptions.removeAllViews()
            renderedAskId = null
            return
        }
        binding.taskAsk.visibility = android.view.View.VISIBLE
        binding.taskAsk.text = ask.question
        binding.taskAsk.setTextColor(getColor(riskColor(ask.risk)))

        if (renderedAskId == ask.id) return
        renderedAskId = ask.id
        binding.askOptions.removeAllViews()
        val options = ask.options.ifEmpty { listOf(AskUserRequest.APPROVE, AskUserRequest.DENY) }
        options.forEach { label ->
            binding.askOptions.addView(chip(label) { container.engine.answer(label) })
        }
        if (ask.allowFreeText) {
            binding.askOptions.addView(chip("Type an answer", outline = true) {
                binding.input.requestFocus()
                binding.input.hint = getString(R.string.overlay_reply_hint)
            })
        }
        binding.askOptions.visibility = android.view.View.VISIBLE
    }

    private fun renderPermissions() {
        val accessibility = PermissionStatus.isAccessibilityEnabled(this) &&
            AgentAccessibilityService.isConnected
        val overlay = PermissionStatus.canDrawOverlays(this)
        val needsCard = !accessibility || !overlay

        binding.permissionCard.visibility = if (needsCard) android.view.View.VISIBLE else android.view.View.GONE
        binding.accessibilityLine.text = getString(R.string.perm_accessibility_title) + "  " +
            getString(if (accessibility) R.string.perm_status_granted else R.string.perm_status_missing)
        binding.overlayLine.text = getString(R.string.perm_overlay_title) + "  " +
            getString(if (overlay) R.string.perm_status_granted else R.string.perm_status_missing)
        binding.restrictedHint.visibility =
            if (!accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                android.view.View.VISIBLE
            } else android.view.View.GONE
    }

    private fun stateColor(state: TaskState): Int = when (state) {
        TaskState.COMPLETED -> R.color.status_ok
        TaskState.FAILED, TaskState.CANCELLED -> R.color.status_error
        TaskState.WAITING_FOR_USER, TaskState.PAUSED, TaskState.RECOVERING -> R.color.status_warn
        else -> R.color.status_working
    }

    private fun riskColor(risk: com.deuty.agent.core.model.RiskLevel): Int = when (risk) {
        com.deuty.agent.core.model.RiskLevel.HIGH,
        com.deuty.agent.core.model.RiskLevel.FORBIDDEN -> R.color.status_error
        com.deuty.agent.core.model.RiskLevel.MEDIUM -> R.color.status_warn
        else -> R.color.text_primary
    }

    companion object {
        private const val RECORD_TOGGLE = "__record__"
        private const val SANDBOX = "__sandbox__"
    }
}

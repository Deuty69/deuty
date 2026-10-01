package com.deuty.agent.ui.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.deuty.agent.R
import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.model.AskUserRequest
import com.deuty.agent.core.model.StepStatus
import com.deuty.agent.core.model.TaskState
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.databinding.ViewAgentOverlayBinding

/**
 * Renders [AgentStatus] into the overlay layout (requirement 16).
 *
 * Efficiency rules, because this view is on screen while other apps run:
 *   - No animation loop. The "working" pulse is a single repeating alpha
 *     animation that is started only when the state actually changes and
 *     cancelled the moment the task is not active.
 *   - Renders are skipped entirely when nothing relevant changed
 *     ([lastRenderKey]); the status flow emits on every accessibility event.
 *   - The step list and the option buttons are rebuilt only when their content
 *     changes, never on every frame.
 *   - Everything is a TextView with a drawable background: no themed widgets, so
 *     inflation from a Service context cannot fail on an OEM theme.
 */
class OverlayPresenter(
    private val context: Context,
    private val binding: ViewAgentOverlayBinding,
    private val log: AgentLog
) {

    var callbacks: OverlayCallbacks? = null

    var expanded: Boolean = false
        private set

    private var lastRenderKey: String? = null
    private var lastStepKey: String? = null
    private var lastOptionKey: String? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var wired = false

    private val density: Float get() = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    fun wire() {
        if (wired) return
        wired = true
        binding.stopButton.setOnClickListener { callbacks?.onStop() }
        binding.pauseButton.setOnClickListener { callbacks?.onPauseResume() }
        binding.retryButton.setOnClickListener { callbacks?.onRetry() }
        binding.collapseButton.setOnClickListener { setExpanded(false) }
        binding.capsule.setOnClickListener { callbacks?.onToggleExpand() }
        binding.panelTitle.setOnClickListener { callbacks?.onOpenAgent() }
        binding.panelInput.setOnEditorActionListener { _, _, _ ->
            submitReply()
            true
        }
    }

    fun setExpanded(value: Boolean) {
        expanded = value
        binding.panel.visibility = if (value) View.VISIBLE else View.GONE
        binding.capsule.visibility = if (value) View.GONE else View.VISIBLE
    }

    fun render(status: AgentStatus, debugMode: Boolean) {
        wire()
        val key = renderKey(status, debugMode)
        if (key == lastRenderKey) return
        lastRenderKey = key

        val style = CapsuleStyle.from(status)
        val color = ContextCompat.getColor(context, style.colorRes)

        // Tint the oval drawable rather than replacing the background, otherwise
        // the status dot becomes a square.
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.panelDot.backgroundTintList = ColorStateList.valueOf(color)
        binding.capsuleText.text = style.text
        binding.capsuleProgress.text = style.progressPercent?.let { "$it%" } ?: ""
        binding.capsuleProgress.visibility = if (style.progressPercent == null) View.GONE else View.VISIBLE

        val task = status.task
        binding.panelHeadline.text = when {
            task != null -> task.headline.ifBlank { task.goal }
            status.recording -> "Recording your actions"
            else -> "Idle"
        }
        binding.panelProgress.progress = ((task?.progress ?: 0f) * 100).toInt().coerceIn(0, 100)
        binding.panelProgress.visibility = if (task == null) View.GONE else View.VISIBLE

        renderSteps(status)
        renderAsk(status)
        renderControls(status)
        renderDebug(status, debugMode)
        renderPulse(style.pulse)
    }

    private fun renderSteps(status: AgentStatus) {
        val steps = status.task?.steps ?: emptyList()
        if (steps.isEmpty()) {
            binding.stepList.removeAllViews()
            lastStepKey = null
            binding.stepList.visibility = View.GONE
            return
        }
        val key = steps.joinToString("|") { "${it.status.name}:${it.title}" }
        if (key == lastStepKey) return
        lastStepKey = key

        binding.stepList.removeAllViews()
        binding.stepList.visibility = View.VISIBLE
        // Only show a window around the current step: a 30-step workflow must not
        // turn the capsule into a scrollable wall of text.
        val currentIndex = steps.indexOfFirst { it.isCurrent }
        val from = (currentIndex - 1).coerceAtLeast(0)
        val to = (currentIndex + 4).coerceAtMost(steps.size - 1)
        steps.subList(from, to + 1).forEach { step ->
            binding.stepList.addView(stepRow(step.title, step.status, step.isCurrent))
        }
        if (steps.size > (to - from + 1)) {
            binding.stepList.addView(metaRow("${steps.size} steps · ${currentIndex + 1} of ${steps.size}"))
        }
    }

    private fun stepRow(title: String, status: StepStatus, isCurrent: Boolean): TextView {
        val marker = when (status) {
            StepStatus.DONE -> "\u2713"
            StepStatus.SKIPPED -> "\u2192"
            StepStatus.FAILED -> "\u2717"
            StepStatus.WAITING_FOR_USER -> "\u25CF"
            StepStatus.RUNNING -> if (isCurrent) "\u25CF" else "\u25CB"
            StepStatus.PENDING -> "\u25CB"
        }
        val colorRes = when (status) {
            StepStatus.DONE -> R.color.status_ok
            StepStatus.FAILED -> R.color.status_error
            StepStatus.RUNNING, StepStatus.WAITING_FOR_USER -> R.color.overlay_text
            else -> R.color.overlay_text_dim
        }
        return TextView(context).apply {
            text = "$marker  $title"
            setTextColor(ContextCompat.getColor(context, colorRes))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
    }

    private fun metaRow(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.overlay_text_dim))
        textSize = 10f
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) }
    }

    private fun renderAsk(status: AgentStatus) {
        val ask: AskUserRequest? = status.ask
        if (ask == null) {
            binding.panelAsk.visibility = View.GONE
            binding.optionRow.visibility = View.GONE
            binding.panelInput.visibility = View.GONE
            lastOptionKey = null
            return
        }
        binding.panelAsk.text = ask.question
        binding.panelAsk.visibility = View.VISIBLE
        binding.panelInput.visibility = if (ask.allowFreeText) View.VISIBLE else View.GONE

        val key = ask.id + "|" + ask.options.joinToString(",")
        if (key != lastOptionKey) {
            lastOptionKey = key
            binding.optionRow.removeAllViews()
            ask.options.forEach { option ->
                binding.optionRow.addView(optionButton(option, ask))
            }
            binding.optionRow.visibility = if (ask.options.isEmpty()) View.GONE else View.VISIBLE
        }
        // An unanswered HIGH/FORBIDDEN question auto-expands the panel: the user
        // has to see it, and a collapsed capsule saying "Needs you" is not enough.
        if (ask.risk.rank >= 2) setExpanded(true)
    }

    private fun optionButton(label: String, ask: AskUserRequest): TextView {
        val destructive = label.startsWith("Stop") || label.startsWith("Don't")
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.overlay_text))
            setBackgroundResource(if (destructive) R.drawable.bg_overlay_stop else R.drawable.bg_overlay_button)
            textSize = 13f
            val padding = dp(10)
            setPadding(padding, dp(9), padding, dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            setOnClickListener {
                callbacks?.onChoice(label)
                if (ask.allowFreeText) {
                    binding.panelInput.visibility = View.GONE
                }
            }
        }
    }

    private fun renderControls(status: AgentStatus) {
        val task = status.task
        val live = status.recording || (task != null && !task.state.isTerminal)
        binding.controlRow.visibility = if (live) View.VISIBLE else View.GONE
        binding.stopButton.visibility = View.VISIBLE
        binding.stopButton.text = context.getString(R.string.action_stop)

        val paused = task?.state == TaskState.PAUSED || task?.state == TaskState.WAITING_FOR_USER
        binding.pauseButton.text = context.getString(if (paused) R.string.action_resume else R.string.action_pause)
        binding.pauseButton.visibility = if (task != null && !task.state.isTerminal) View.VISIBLE else View.GONE
        binding.retryButton.visibility =
            if (task != null && (task.state == TaskState.FAILED || task.state == TaskState.RECOVERING)) View.VISIBLE else View.GONE

        if (!live && status.ask == null) {
            binding.panelInput.visibility = View.GONE
        }
    }

    private fun renderDebug(status: AgentStatus, debugMode: Boolean) {
        val debug = status.debug
        if (!debugMode || debug == null) {
            binding.debugLine.visibility = View.GONE
            return
        }
        binding.debugLine.visibility = View.VISIBLE
        binding.debugLine.text = buildString {
            append(debug.packageName ?: "?")
            append(" \u00B7 ").append(debug.nodeCount).append(" nodes")
            append(" \u00B7 ").append(debug.source.label)
            append(" \u00B7 ").append(debug.captureMs).append("ms")
            append('\n')
            append("state=").append(debug.taskState.name)
            append(" retries=").append(debug.retryCount).append('/').append(debug.totalRetries)
            append(" actions=").append(debug.actionsUsed).append('/').append(debug.budgetActions)
            debug.lastAction?.let { append('\n').append("last: ").append(it) }
            debug.lastResult?.let { append('\n').append("result: ").append(it.take(80)) }
            debug.loopWarning?.let { append('\n').append("LOOP: ").append(it) }
        }
    }

    /**
     * The only animation in the overlay: a slow alpha pulse on the status dot.
     * It is a RenderNode property animation (no window invalidation, no layout
     * pass) and it is cancelled the moment the agent is not active.
     */
    private fun renderPulse(active: Boolean) {
        if (active) {
            if (pulseAnimator?.isRunning == true) return
            pulseAnimator = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.35f).apply {
                duration = PULSE_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            binding.statusDot.alpha = 1f
        }
    }

    fun stopAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.statusDot.alpha = 1f
    }

    fun submitReply() {
        val text = binding.panelInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        binding.panelInput.text?.clear()
        callbacks?.onReply(text)
    }

    fun showInputForReply() {
        binding.panelInput.visibility = View.VISIBLE
        binding.panelInput.requestFocus()
    }

    private fun renderKey(status: AgentStatus, debugMode: Boolean): String = buildString {
        append(status.recording).append('|')
        append(status.ask?.id ?: "-").append('|')
        append(status.task?.id ?: "-").append('|')
        append(status.task?.state?.name ?: "-").append('|')
        append(status.task?.progress ?: 0f).append('|')
        append(status.task?.headline()?.hashCode() ?: 0).append('|')
        append(status.debug?.signature ?: "-").append('|')
        append(status.debug?.lastAction ?: "-").append('|')
        append(status.debug?.lastResult?.hashCode() ?: 0).append('|')
        append(status.debug?.loopWarning ?: "-").append('|')
        append(debugMode)
    }

    companion object {
        private const val PULSE_MS = 620L
    }
}

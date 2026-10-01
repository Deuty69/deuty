package com.deuty.agent.platform

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.ContextThemeWrapper
import com.deuty.agent.R
import com.deuty.agent.core.model.AgentStatus
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.databinding.ViewAgentOverlayBinding
import com.deuty.agent.ui.overlay.OverlayCallbacks
import com.deuty.agent.ui.overlay.OverlayPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * WindowManager plumbing for the capsule (requirement 16).
 *
 * Behaviour contract:
 *  - Attached only while there is something worth showing (a live task, a pending
 *    question, a recording). Idle + "minimize when idle" => fully removed, so the
 *    agent never permanently covers another app's UI.
 *  - After a task finishes the capsule stays for [LINGER_MS] so the result is
 *    visible, then goes away on its own.
 *  - NOT_FOCUSABLE + NOT_TOUCH_MODAL: touches outside the capsule reach the app
 *    underneath, and the keyboard does not pop up just because we exist. The
 *    focusable flag is flipped only while the user is typing a reply.
 *  - Drag position is persisted, so the capsule stays where the user put it
 *    across process restarts.
 */
@SuppressLint("ClickableViewAccessibility")
class OverlayController(
    private val context: Context,
    private val prefs: PreferencesStore,
    private val log: AgentLog
) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lingerJob: Job? = null

    private var binding: ViewAgentOverlayBinding? = null
    private var presenter: OverlayPresenter? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    var callbacks: OverlayCallbacks? = null
        set(value) {
            field = value
            presenter?.callbacks = value
        }

    val isAttached: Boolean get() = attached

    val isExpanded: Boolean get() = presenter?.expanded == true

    // ---------------------------------------------------------------- attach

    fun show(expand: Boolean = false): Boolean {
        if (attached) {
            if (expand) setExpanded(true)
            return true
        }
        if (!PermissionStatus.canDrawOverlays(context)) {
            log.w(TAG, "overlay permission missing; capsule cannot be shown")
            return false
        }
        val wm = windowManager ?: return false
        return try {
            val themed = ContextThemeWrapper(context, R.style.Theme_Deuty_Overlay)
            val inflater = LayoutInflater.from(themed)
            val viewBinding = ViewAgentOverlayBinding.inflate(inflater)
            binding = viewBinding
            presenter = OverlayPresenter(themed, viewBinding, log).also { it.callbacks = callbacks }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE keeps the keyboard and the Back button with the
                // app underneath; NOT_TOUCH_MODAL lets touches outside the
                // capsule through. FLAG_LAYOUT_NO_LIMITS is deliberately absent:
                // it makes window placement unpredictable on some OEM builds.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val restoredX = prefs.overlayX
                val restoredY = prefs.overlayY
                x = if (restoredX >= 0) restoredX else defaultMargin()
                y = if (restoredY >= 0) restoredY else defaultTop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            params = layoutParams

            wm.addView(viewBinding.root, layoutParams)
            attached = true

            attachDrag(viewBinding.capsule)
            attachDrag(viewBinding.panelHeader)
            // A NOT_FOCUSABLE window cannot host an EditText, so focus is only
            // requested when the user actually taps the reply field.
            viewBinding.panelInput.setOnClickListener {
                setInputFocusable(true)
                viewBinding.panelInput.requestFocus()
                showKeyboard(viewBinding.panelInput)
            }
            viewBinding.panelInput.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) setInputFocusable(false)
            }

            presenter?.setExpanded(expand || prefs.overlayExpanded)
            log.i(TAG, "overlay attached at (${layoutParams.x},${layoutParams.y})")
            true
        } catch (error: Throwable) {
            log.e(TAG, "could not attach the overlay", error)
            attached = false
            binding = null
            presenter = null
            false
        }
    }

    fun hide() {
        lingerJob?.cancel()
        val wm = windowManager
        val view = binding?.root
        if (wm != null && view != null && attached) {
            runCatching { wm.removeViewImmediate(view) }
                .onFailure { log.w(TAG, "removeView failed: ${it.message}") }
        }
        presenter?.stopAnimations()
        presenter = null
        binding = null
        params = null
        attached = false
        setInputFocusable(false)
    }

    fun setExpanded(expand: Boolean) {
        presenter?.setExpanded(expand)
        prefs.overlayExpanded = expand
        // The panel is wider than the capsule; keep it inside the screen.
        clampPosition()
    }

    fun toggleExpanded() = setExpanded(!isExpanded)

    // ---------------------------------------------------------------- render

    fun render(status: AgentStatus) {
        val settings = prefs.current()
        val shouldShow = when {
            status.recording -> true
            status.ask != null -> true
            status.task == null -> !settings.minimizeOverlayWhenIdle
            status.task.state.isTerminal ->
                if (settings.minimizeOverlayWhenIdle) {
                    scheduleLinger()
                    lingering
                } else true
            else -> true
        }

        if (!shouldShow) {
            if (attached) hide()
            return
        }
        if (!attached && !show(expand = status.ask != null)) return

        presenter?.render(status, settings.debugMode)

        // Auto-expand when the agent needs an answer and the user may not be
        // looking at a 34dp pill.
        if (status.ask != null && !isExpanded) setExpanded(true)
        if (status.ask == null && binding?.panelInput?.hasFocus() != true) setInputFocusable(false)

    }

    private var lingering = false

    private fun lingerActive(): Boolean = lingering

    private fun scheduleLinger() {
        if (lingerJob?.isActive == true) return
        lingering = true
        lingerJob = scope.launch {
            delay(LINGER_MS)
            lingering = false
            if (attached) hide()
        }
    }

    // ------------------------------------------------------------------ drag

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var originX = 0
    private var originY = 0
    private var dragging = false

    private fun attachDrag(handle: View) {
        val slop = (context.resources.displayMetrics.density * 6f).toInt().coerceAtLeast(8)
        handle.setOnTouchListener { view, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    originX = p.x
                    originY = p.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartX).toInt()
                    val dy = (event.rawY - dragStartY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        p.x = originX + dx
                        p.y = originY + dy
                        clampPosition()
                        runCatching { windowManager?.updateViewLayout(binding?.root, p) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        view.performClick()
                    } else {
                        params?.let {
                            prefs.overlayX = it.x
                            prefs.overlayY = it.y
                        }
                    }
                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun clampPosition() {
        val p = params ?: return
        val root = binding?.root ?: return
        val metrics = context.resources.displayMetrics
        val width = if (root.width > 0) root.width else metrics.widthPixels / 2
        val height = if (root.height > 0) root.height else metrics.heightPixels / 8
        p.x = p.x.coerceIn(-width / 3, (metrics.widthPixels - width / 2).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (metrics.heightPixels - height / 2).coerceAtLeast(0))
        runCatching { windowManager?.updateViewLayout(root, p) }
    }

    private fun defaultMargin(): Int = (context.resources.displayMetrics.density * 12f).toInt()

    private fun defaultTop(): Int {
        val density = context.resources.displayMetrics.density
        val statusBar = runCatching {
            val res = context.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) res.getDimensionPixelSize(id) else (24 * density).toInt()
        }.getOrDefault((24 * density).toInt())
        return statusBar + (8 * density).toInt()
    }

    /**
     * Typing a reply needs a focusable window; everything else must stay
     * non-focusable so the underlying app keeps the keyboard and the back button.
     */
    private fun showKeyboard(view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        runCatching { imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun setInputFocusable(focusable: Boolean) {
        val p = params ?: return
        val root = binding?.root ?: return
        val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val newFlags = if (focusable) p.flags and notFocusable.inv() else p.flags or notFocusable
        if (newFlags == p.flags) return
        p.flags = newFlags
        runCatching { windowManager?.updateViewLayout(root, p) }
        if (!focusable) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            runCatching { imm?.hideSoftInputFromWindow(root.windowToken, 0) }
        }
    }

    fun shutdown() {
        hide()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "overlay"
        private const val LINGER_MS = 6_000L
    }
}

package com.deuty.agent.platform

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.deuty.agent.AgentApplication
import com.deuty.agent.di.AgentContainer

/**
 * The service that gives the agent eyes and hands (requirement 2).
 *
 * It is deliberately almost empty. Everything it could be tempted to do - decide
 * what to click, when to verify, what to record - lives in the container's
 * engine, because a service is destroyed whenever the user flips the toggle and
 * logic living here would die with it.
 *
 * What it does do:
 *   - hand itself to the container on connect, and take it away on disconnect;
 *   - forward accessibility events (the only signal we get, and the reason the
 *     app needs no polling);
 *   - never crash on a malformed event, because an exception here kills the
 *     service and the user sees the agent silently stop working.
 *
 * Note on `onInterrupt`: the framework calls it when it wants us to stop
 * feedback. We log it and keep going - an accessibility service that cancels a
 * running task because the system asked for quiet would be surprising, and the
 * user's STOP button is the real interruption path.
 */
class AgentAccessibilityService : AccessibilityService() {

    private var container: AgentContainer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val bound = runCatching { AgentApplication.containerOf(this) }.getOrElse { error ->
            // No container means no Application instance, which should be
            // impossible; if it ever happens, stay inert rather than crash-loop.
            android.util.Log.e(TAG, "container unavailable", error)
            return
        }
        container = bound
        instance = this
        bound.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val bound = container ?: return
        val received = event ?: return
        try {
            bound.onAccessibilityEvent(received)
        } catch (error: Throwable) {
            // Never let one bad event take the service down.
            bound.log.e(TAG, "dropped accessibility event", error)
        }
    }

    override fun onInterrupt() {
        container?.log?.w(TAG, "system asked the service to interrupt feedback")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown("unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown("destroyed")
        super.onDestroy()
    }

    /** Idempotent: the system may call both onUnbind and onDestroy. */
    private fun teardown(reason: String) {
        val bound = container ?: return
        container = null
        if (instance === this) instance = null
        runCatching { bound.onServiceDisconnected(this, reason) }
            .onFailure { bound.log.e(TAG, "error during teardown", it) }
    }

    companion object {
        private const val TAG = "a11y-service"

        /**
         * Live instance, or null. Read by [PermissionStatus] so the UI can tell
         * "enabled in settings" apart from "actually connected right now" - the
         * two disagree more often than you would expect, and telling the user
         * "you're all set" while the service is not bound is how an agent earns a
         * reputation for lying.
         */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null
    }
}

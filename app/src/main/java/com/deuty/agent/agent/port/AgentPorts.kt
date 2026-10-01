package com.deuty.agent.agent.port

import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.core.model.AgentStatus
import kotlinx.coroutines.flow.Flow

/** Settings, without binding the engine to SharedPreferences. */
interface SettingsProvider {
    fun current(): AgentSettings
    val changes: Flow<AgentSettings>
}

/**
 * The floating capsule, as seen from the engine.
 *
 * The engine renders status; it never touches WindowManager. That keeps the
 * overlay swappable (capsule today, notification-only build tomorrow) and keeps
 * the engine testable - a fake view is three lines.
 */
interface AgentView {
    val isVisible: Boolean
    fun render(status: AgentStatus)
    fun expand()
}

/** The device session the agent currently has (real, sandbox, or none). */
class SessionHolder {

    @Volatile
    private var current: DeviceSession? = null

    fun get(): DeviceSession? = current

    fun attach(session: DeviceSession) {
        current = session
    }

    fun detach(sessionId: String) {
        if (current?.id == sessionId) current = null
    }

    fun detachAll() {
        current = null
    }

    val attached: Boolean get() = current != null
}

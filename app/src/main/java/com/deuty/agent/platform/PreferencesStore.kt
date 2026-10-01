package com.deuty.agent.platform

import android.content.Context
import android.content.SharedPreferences
import com.deuty.agent.core.model.AgentSettings
import com.deuty.agent.agent.port.SettingsProvider
import com.deuty.agent.core.model.PlannerIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Typed preferences (requirement 18, "Preferences" memory).
 *
 * SharedPreferences is the right tool here: a few dozen scalar values, read
 * constantly, written rarely, and already cached in memory by the framework. A
 * database would be strictly worse.
 *
 * The [settings] flow is the single source of truth for the UI; writes go
 * through [update] so the flow and the disk can never disagree.
 */
class PreferencesStore(context: Context) : SettingsProvider {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private fun read(): AgentSettings = AgentSettings(
        confirmMedium = prefs.getBoolean(K_CONFIRM_MEDIUM, true),
        blockSensitiveScreens = prefs.getBoolean(K_BLOCK_SENSITIVE, true),
        allowPasswordEntry = prefs.getBoolean(K_PASSWORD, false),
        askWhenAmbiguous = prefs.getBoolean(K_ASK_AMBIGUOUS, false),
        maxActions = prefs.getInt(K_MAX_ACTIONS, 60),
        maxTaskSeconds = prefs.getInt(K_MAX_SECONDS, 600),
        maxRetriesPerStep = prefs.getInt(K_MAX_RETRIES, 3),
        redact = prefs.getBoolean(K_REDACT, true),
        keepHistory = prefs.getBoolean(K_KEEP_HISTORY, true),
        allowScreenshots = prefs.getBoolean(K_SCREENSHOTS, false),
        historyLimit = prefs.getInt(K_HISTORY_LIMIT, 200),
        plannerId = prefs.getString(K_PLANNER, PlannerIds.RULE_BASED) ?: PlannerIds.RULE_BASED,
        debugMode = prefs.getBoolean(K_DEBUG, false),
        logEvents = prefs.getBoolean(K_LOG_EVENTS, false),
        minimizeOverlayWhenIdle = prefs.getBoolean(K_MINIMIZE_IDLE, true),
        showStepListInCapsule = prefs.getBoolean(K_STEP_LIST, true)
    )

    override fun current(): AgentSettings = _settings.value

    /** The engine observes settings through the port, not through SharedPreferences. */
    override val changes: Flow<AgentSettings> = _settings.asStateFlow()

    fun update(block: (AgentSettings) -> AgentSettings): AgentSettings {
        val next = block(_settings.value)
        prefs.edit()
            .putBoolean(K_CONFIRM_MEDIUM, next.confirmMedium)
            .putBoolean(K_BLOCK_SENSITIVE, next.blockSensitiveScreens)
            .putBoolean(K_PASSWORD, next.allowPasswordEntry)
            .putBoolean(K_ASK_AMBIGUOUS, next.askWhenAmbiguous)
            .putInt(K_MAX_ACTIONS, next.maxActions)
            .putInt(K_MAX_SECONDS, next.maxTaskSeconds)
            .putInt(K_MAX_RETRIES, next.maxRetriesPerStep)
            .putBoolean(K_REDACT, next.redact)
            .putBoolean(K_KEEP_HISTORY, next.keepHistory)
            .putBoolean(K_SCREENSHOTS, next.allowScreenshots)
            .putInt(K_HISTORY_LIMIT, next.historyLimit)
            .putString(K_PLANNER, next.plannerId)
            .putBoolean(K_DEBUG, next.debugMode)
            .putBoolean(K_LOG_EVENTS, next.logEvents)
            .putBoolean(K_MINIMIZE_IDLE, next.minimizeOverlayWhenIdle)
            .putBoolean(K_STEP_LIST, next.showStepListInCapsule)
            .apply()
        _settings.value = next
        return next
    }

    /** Reload from disk; used after a "clear all data" from another screen. */
    fun refresh(): AgentSettings {
        _settings.value = read()
        return _settings.value
    }

    // --- overlay geometry (persisted so the capsule stays where the user put it)

    var overlayX: Int
        get() = prefs.getInt(K_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(K_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(K_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(K_OVERLAY_Y, value).apply()

    var overlayExpanded: Boolean
        get() = prefs.getBoolean(K_OVERLAY_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(K_OVERLAY_EXPANDED, value).apply()

    // --- misc

    var onboardingSeen: Boolean
        get() = prefs.getBoolean(K_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(K_ONBOARDING, value).apply()

    fun clearEverything() {
        prefs.edit().clear().apply()
        _settings.value = read()
    }

    companion object {
        private const val FILE = "deuty.prefs"
        private const val K_CONFIRM_MEDIUM = "confirm_medium"
        private const val K_BLOCK_SENSITIVE = "block_sensitive"
        private const val K_PASSWORD = "allow_password"
        private const val K_ASK_AMBIGUOUS = "ask_ambiguous"
        private const val K_MAX_ACTIONS = "max_actions"
        private const val K_MAX_SECONDS = "max_seconds"
        private const val K_MAX_RETRIES = "max_retries"
        private const val K_REDACT = "redact"
        private const val K_KEEP_HISTORY = "keep_history"
        private const val K_SCREENSHOTS = "allow_screenshots"
        private const val K_HISTORY_LIMIT = "history_limit"
        private const val K_PLANNER = "planner"
        private const val K_DEBUG = "debug_mode"
        private const val K_LOG_EVENTS = "log_events"
        private const val K_MINIMIZE_IDLE = "minimize_idle"
        private const val K_STEP_LIST = "step_list"
        private const val K_OVERLAY_X = "overlay_x"
        private const val K_OVERLAY_Y = "overlay_y"
        private const val K_OVERLAY_EXPANDED = "overlay_expanded"
        private const val K_ONBOARDING = "onboarding_seen"
    }
}

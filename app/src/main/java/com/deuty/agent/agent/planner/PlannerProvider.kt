package com.deuty.agent.agent.planner

import com.deuty.agent.agent.port.SettingsProvider
import com.deuty.agent.core.model.PlannerIds
import com.deuty.agent.core.util.AgentLog

/**
 * Chooses the planner and owns the fallback chain (requirement 12).
 *
 * Ordering rule: cheap, offline, deterministic first. The user's configured
 * planner is tried first if it is available, and [RuleBasedPlanner] is always
 * last in the chain - so "the app must keep working without an AI provider" is a
 * property of this class, not a hope.
 *
 * Fallback semantics: only [PlanResult.NotCapable] falls through. A
 * [PlanResult.Clarify], [PlanResult.Rejected] or [PlanResult.Control] is an
 * answer, not a failure, and is returned to the user as-is. Without that rule a
 * model that says "I need more information" would be overruled by the grammar.
 */
class PlannerProvider(
    private val registry: List<Planner>,
    private val settings: SettingsProvider,
    private val log: AgentLog
) {

    val activeId: String
        get() = settings.current().plannerId

    fun active(): Planner = registry.firstOrNull { it.id == activeId } ?: ruleBased() ?: registry.first()

    fun ruleBased(): Planner? = registry.firstOrNull { it.id == PlannerIds.RULE_BASED }

    /** Preference order for the current configuration. */
    fun chain(): List<Planner> {
        val preferred = registry.firstOrNull { it.id == activeId && it.available }
        val fallback = ruleBased()?.takeIf { it.available && it.id != preferred?.id }
        return listOfNotNull(preferred, fallback)
    }

    suspend fun plan(request: PlanRequest): PlanResult {
        val candidates = chain()
        if (candidates.isEmpty()) {
            return PlanResult.NotCapable("no planner is available", "none")
        }
        var lastNotCapable: PlanResult.NotCapable? = null
        for (planner in candidates) {
            val result = runCatching { planner.plan(request) }.getOrElse { error ->
                log.e(planner.id, "planner threw", error)
                return@getOrElse PlanResult.NotCapable("planner error: ${error.javaClass.simpleName}", planner.id)
            }
            when (result) {
                is PlanResult.NotCapable -> {
                    lastNotCapable = result
                    log.d(planner.id, "not capable (${result.reason}); trying the next planner")
                }
                else -> return result
            }
        }
        return lastNotCapable ?: PlanResult.NotCapable("no planner could handle this", "none")
    }

    /** One-line description for the settings screen. */
    fun describe(): String {
        val active = active()
        val available = registry.filter { it.available }.joinToString(", ") { it.displayName }
        return "Using ${active.displayName}. Available: ${available.ifBlank { "none" }}."
    }

    companion object {
        /** The default chain: rules first, model stubs registered but inert. */
        fun default(
            ruleBased: Planner,
            local: Planner,
            remote: Planner,
            settings: SettingsProvider,
            log: AgentLog
        ) = PlannerProvider(listOf(ruleBased, local, remote), settings, log)
    }
}

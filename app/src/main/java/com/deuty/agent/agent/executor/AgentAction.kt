package com.deuty.agent.agent.executor

import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.Expectation
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.RiskLevel

/**
 * The action vocabulary (requirement 7).
 *
 * Rules this design enforces:
 *  1. Actions are *data*. Nothing here can execute arbitrary code, so a planner
 *     (rule-based today, an LLM tomorrow) can only ever produce operations the
 *     engine knows how to perform safely and can verify.
 *  2. Nothing here touches the framework. The same action list runs against a
 *     real AccessibilityService or against a sandbox.
 *  3. Adding an action = adding a subclass + one `when` branch in
 *     [ActionExecutor] + one codec branch in [AgentActionCodecs]. No other file
 *     in the system needs to change.
 *
 * [baseRisk] is the *floor*. The effective risk is computed by RiskManager from
 * the base risk, the target text and the current screen, and it can only go up.
 */
sealed interface AgentAction {
    /** Short, stable name used in logs, JSON and the capsule ("tap", "type"). */
    val label: String

    /** Human-readable one-liner, e.g. `tap(text="Send")`. */
    fun describe(): String = label

    val baseRisk: RiskLevel

    /** The element this action is aimed at, if any. Used by risk + resolver. */
    fun targetQuery(): ElementQuery? = null

    /** Child actions for composite steps; used for risk rollup and budgeting. */
    fun children(): List<AgentAction> = emptyList()

    /**
     * True when the action only *looks* at the device.
     *
     * The verifier uses this to skip "did the screen change?" for actions that
     * cannot change anything - otherwise every ReadScreen would block for the
     * full step timeout waiting for a change that will never come, and the agent
     * would feel broken on exactly the commands users try first.
     */
    val observesOnly: Boolean get() = false
}

data class Tap(
    val target: ElementQuery,
    /** Double-tap support without adding a new action type. */
    val times: Int = 1,
    val intervalMs: Long = 90L
) : AgentAction {
    override val label = "tap"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = if (times > 1) "tap x$times ${target.describe()}" else "tap ${target.describe()}"
    override fun targetQuery() = target
}

data class LongPress(
    val target: ElementQuery,
    val durationMs: Long = 650L
) : AgentAction {
    override val label = "long_press"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = "long press ${target.describe()}"
    override fun targetQuery() = target
}

/**
 * A directional drag. Used for swiping between pages and as the fallback when
 * no scrollable node is exposed (very common in WebView and Compose apps).
 */
data class Swipe(
    val direction: Direction,
    val distanceRatio: Float = 0.55f,
    val durationMs: Long = 280L,
    val startPoint: Point? = null
) : AgentAction {
    override val label = "swipe"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = "swipe ${direction.name.lowercase()}"
}

data class Scroll(
    val direction: Direction = Direction.DOWN,
    val container: ElementQuery? = null,
    val times: Int = 1,
    /** Keep scrolling until this element appears (bounded by [maxTimes]). */
    val untilVisible: ElementQuery? = null,
    val maxTimes: Int = 12
) : AgentAction {
    override val label = "scroll"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = buildString {
        append("scroll ").append(direction.name.lowercase())
        untilVisible?.let { append(" until ").append(it.describe()) }
        container?.let { append(" in ").append(it.describe()) }
    }

    override fun targetQuery() = container ?: untilVisible
}

/**
 * Text entry.
 *
 * [target] null means "whatever has focus right now". [clearFirst] makes typing
 * idempotent, which matters enormously on replay: without it, retrying a failed
 * step appends the text a second time.
 */
data class Type(
    val text: String,
    val target: ElementQuery? = null,
    val clearFirst: Boolean = true,
    val pressEnter: Boolean = false
) : AgentAction {
    override val label = "type"
    override val baseRisk = RiskLevel.LOW
    override fun describe(): String {
        // The text itself is redacted by the Redactor before this reaches any
        // log or the persisted history; describe() stays honest but short.
        val where = target?.describe() ?: "focused field"
        return "type ${text.length} chars into $where"
    }

    override fun targetQuery() = target
}

data class Paste(
    val target: ElementQuery? = null,
    val text: String? = null
) : AgentAction {
    override val label = "paste"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = "paste into ${target?.describe() ?: "focused field"}"
    override fun targetQuery() = target
}

data object Back : AgentAction {
    override val label = "back"
    override val baseRisk = RiskLevel.LOW
}

data object Home : AgentAction {
    override val label = "home"
    override val baseRisk = RiskLevel.LOW
}

data object Recents : AgentAction {
    override val label = "recents"
    override val baseRisk = RiskLevel.LOW
}

/**
 * Launch by package (deterministic) or by human label (resolved at runtime
 * against the installed launchable apps, with an alias table for the apps that
 * every OEM renames - "Files" alone is six different packages).
 */
data class LaunchApp(
    val appLabel: String? = null,
    val packageName: String? = null
) : AgentAction {
    override val label = "launch_app"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = "launch ${packageName ?: appLabel ?: "app"}"
}

data class Wait(
    val millis: Long,
    /** When set, wait until this holds instead of for a fixed duration. */
    val until: Expectation? = null
) : AgentAction {
    override val label = "wait"
    override val baseRisk = RiskLevel.LOW
    override val observesOnly = true
    override fun describe() =
        if (until != null) "wait until ${until.describe()} (max ${millis}ms)" else "wait ${millis}ms"
}

data class ReadScreen(
    val maxItems: Int = 14,
    val reportToUser: Boolean = true
) : AgentAction {
    override val label = "read_screen"
    override val baseRisk = RiskLevel.LOW
    override val observesOnly = true
    override fun describe() = "read screen"
}

/**
 * Level-2 capture. Risk is MEDIUM because a bitmap of the screen is the single
 * most sensitive artefact the agent can produce, so it is opt-in per capture
 * and disabled by default in settings.
 */
data class Screenshot(
    val reason: String = "requested",
    val region: Bounds? = null,
    val runOcr: Boolean = false
) : AgentAction {
    override val label = "screenshot"
    override val baseRisk = RiskLevel.MEDIUM
    override val observesOnly = true
    override fun describe() = "screenshot ($reason)"
}

data class FindElement(
    val query: ElementQuery,
    val reportOnly: Boolean = true
) : AgentAction {
    override val label = "find"
    override val baseRisk = RiskLevel.LOW
    override val observesOnly = true
    override fun describe() = "find ${query.describe()}"
    override fun targetQuery() = query
}

/** If/else without giving a planner any way to express unbounded control flow. */
data class Conditional(
    val condition: Expectation,
    val then: List<AgentAction>,
    val otherwise: List<AgentAction> = emptyList()
) : AgentAction {
    override val label = "if"
    override val baseRisk get() = maxRiskOf(children())
    override fun describe() = "if ${condition.describe()} then ${then.size} action(s)" +
        (if (otherwise.isEmpty()) "" else " else ${otherwise.size}")

    override fun children() = then + otherwise
}

/**
 * The only loop in the vocabulary, and it is always bounded twice: by
 * [maxTimes] and by [until]. An unbounded loop cannot be expressed, which is
 * how requirement 20 is enforced structurally rather than by convention.
 */
data class Repeat(
    val body: List<AgentAction>,
    val maxTimes: Int = 5,
    val until: Expectation? = null
) : AgentAction {
    override val label = "repeat"
    override val baseRisk get() = maxRiskOf(children())
    override fun describe() = "repeat ${body.size} action(s) up to $maxTimes times" +
        (until?.let { " until ${it.describe()}" } ?: "")

    override fun children() = body
}

/** Explicit "stop and ask a human". Also produced implicitly by RiskManager. */
data class AskUser(
    val question: String,
    val options: List<String> = emptyList(),
    val allowFreeText: Boolean = true,
    val risk: RiskLevel = RiskLevel.MEDIUM,
    val denyStopsTask: Boolean = false
) : AgentAction {
    override val label = "ask_user"
    override val baseRisk = RiskLevel.LOW
    override val observesOnly = true
    override fun describe() = "ask user: $question"
}

/**
 * Expands into the referenced workflow's steps at plan time, so the risk gate
 * and verifier see real steps. Kept as an action so a plan can be persisted
 * before expansion.
 */
data class RunWorkflow(
    val workflowId: String? = null,
    val workflowName: String? = null
) : AgentAction {
    override val label = "run_workflow"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = "run workflow ${workflowName ?: workflowId}"
}

/**
 * Bring an app back to the foreground after a crash or an unexpected exit.
 * Recovery-only: never produced by a planner and never recorded into a workflow.
 */
data class Relaunch(
    val packageName: String
) : AgentAction {
    override val label = "relaunch"
    override val baseRisk = RiskLevel.LOW
    override fun describe() = "relaunch $packageName"
}

/** Say something to the user without touching the device. */
data class Report(
    val message: String
) : AgentAction {
    override val label = "report"
    override val baseRisk = RiskLevel.LOW
    override val observesOnly = true
    override fun describe() = "report to user"
}

fun maxRiskOf(actions: List<AgentAction>): RiskLevel =
    actions.fold(RiskLevel.LOW) { acc, a -> if (a.baseRisk.rank > acc.rank) a.baseRisk else acc }

/**
 * Rebuild an action with a relaxed locator. Used by recovery: after an exact
 * text match fails, retrying with "contains" is far more likely to work than
 * retrying the identical query and hoping the app changed its mind.
 */
fun AgentAction.withTarget(query: ElementQuery?): AgentAction {
    if (query == null) return this
    return when (this) {
        is Tap -> copy(target = query)
        is LongPress -> copy(target = query)
        is Type -> copy(target = query)
        is FindElement -> copy(query = query)
        is Scroll -> copy(container = query)
        is Paste -> copy(target = query)
        else -> this
    }
}

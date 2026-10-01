package com.deuty.agent.agent.planner

import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.port.AppDirectory
import com.deuty.agent.agent.executor.Back
import com.deuty.agent.agent.executor.FindElement
import com.deuty.agent.agent.executor.Home
import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.LongPress
import com.deuty.agent.agent.executor.ReadScreen
import com.deuty.agent.agent.executor.Recents
import com.deuty.agent.agent.executor.Report
import com.deuty.agent.agent.executor.Screenshot
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.agent.executor.Wait
import com.deuty.agent.core.model.Always
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementPresent
import com.deuty.agent.core.model.Expectation
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.FieldContains
import com.deuty.agent.core.model.PackageIs
import com.deuty.agent.core.model.RetryPolicy
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenChanged
import com.deuty.agent.core.model.TaskSource
import com.deuty.agent.core.model.TaskStep
import com.deuty.agent.core.model.TextPresent
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.Clock
import com.deuty.agent.core.util.Ids
import com.deuty.agent.core.util.TextMatch
import com.deuty.agent.workflow.WorkflowRepository

/**
 * The V1 brain: a deterministic, offline grammar (requirement 12/13).
 *
 * Why a rule-based planner ships first, and why it is not a placeholder:
 *   - It works with no network, no model and no key, on a 4 GB phone.
 *   - It is deterministic, so a bug is reproducible and a fix is a test.
 *   - It defines the *contract* an AI planner has to satisfy: typed steps with
 *     preconditions, postconditions, timeouts and retry policies. A remote model
 *     that emits this shape can be dropped in later without touching the engine.
 *   - It covers the MVP commands (open, tap, type, scroll, back, home, find,
 *     read, wait, rename, move, run workflow) plus compound instructions.
 *
 * What it deliberately does NOT do: guess. If a sentence does not match, the
 * result is [PlanResult.NotCapable] and the engine either asks the user or falls
 * through to another planner. Inventing steps from an unparsed sentence is how an
 * agent ends up tapping the wrong thing with total confidence.
 */
class RuleBasedPlanner(
    private val clock: Clock,
    private val log: AgentLog,
    private val apps: AppDirectory? = null,
    private val workflows: WorkflowRepository? = null
) : Planner {

    override val id = "rule"
    override val displayName = "Rule-based (offline)"
    override val available = true
    override val requiresNetwork = false
    override val costRank = 0

    override suspend fun plan(request: PlanRequest): PlanResult {
        val raw = request.instruction.trim()
        if (raw.isEmpty()) return PlanResult.NotCapable("empty instruction", id)
        val text = stripPoliteness(raw)
        val normalized = TextMatch.normalize(text)

        controlCommand(normalized)?.let { return PlanResult.Control(it, id) }

        workflowPlan(normalized, text, request)?.let { return it }

        val clauses = splitClauses(text)
        val steps = mutableListOf<TaskStep>()
        val notes = mutableListOf<String>()

        for (clause in clauses) {
            when (val result = planClause(clause, request, notes)) {
                is PlanResult.Plan -> steps += result.steps
                is PlanResult.Clarify -> return result
                is PlanResult.Rejected -> return result
                is PlanResult.Control -> return result
                is PlanResult.NotCapable -> notes += "could not parse \"$clause\""
            }
        }

        if (steps.isEmpty()) {
            return PlanResult.NotCapable(
                "no rule matched \"${raw.take(80)}\"", id
            )
        }
        notes.forEach { note ->
            steps += step("Tell you what I could not do", Report(note), Always)
        }

        val risk = steps.fold(RiskLevel.LOW) { acc, s -> if (s.risk.rank > acc.rank) s.risk else acc }
        log.i(TAG, "planned ${steps.size} step(s) for \"${raw.take(60)}\"")
        return PlanResult.Plan(
            goal = raw,
            steps = steps,
            source = TaskSource.USER_TEXT,
            notes = notes.joinToString("; ").ifBlank { null },
            risk = risk,
            contextValues = mapOf("instruction" to raw.take(200)),
            plannerId = id
        )
    }

    // ------------------------------------------------------------- control

    private fun controlCommand(text: String): ControlCommand? = ControlGrammar.parse(text)

    // ----------------------------------------------------------- workflows

    private suspend fun workflowPlan(normalized: String, text: String, request: PlanRequest): PlanResult? {
        val match = WORKFLOW_RUN.matchEntire(normalized) ?: return null
        // "start the timer" is not a workflow request. Only intercept when the user
        // said "workflow", or when a saved workflow actually matches the name.
        val explicit = normalized.contains("workflow") || normalized.contains("replay") ||
            normalized.contains("repeat what i did")
        val name = match.groupValues[1].trim().removePrefix("called ").trim()
        val repo = workflows ?: return if (explicit) {
            PlanResult.Rejected("workflow storage is not available", id)
        } else null
        val workflow = repo.findByName(name)
        if (workflow == null) {
            if (!explicit) return null
            val known = request.context.workflows.map { it.name }
            return PlanResult.Clarify(
                if (known.isEmpty()) {
                    "I don't have any saved workflows yet. Record one first: tap Record, do the task, tap Stop."
                } else {
                    "I don't have a workflow called \"$name\". Did you mean one of: ${known.take(6).joinToString(", ")}?"
                },
                known.take(6),
                id
            )
        }
        val steps = repo.instantiate(workflow)
        return PlanResult.Plan(
            goal = workflow.name,
            steps = steps,
            source = TaskSource.WORKFLOW,
            notes = "replaying recorded workflow (${workflow.source.name.lowercase()})",
            risk = workflow.riskLevel,
            contextValues = mapOf("workflowId" to workflow.id),
            plannerId = id
        ).also { log.i(TAG, "matched workflow \"${workflow.name}\" (${steps.size} steps)") }
    }

    // -------------------------------------------------------------- clauses

    private fun splitClauses(text: String): List<String> {
        var working = text
        // "open Settings and tap Wi-Fi": split on "and" only when a verb follows,
        // so "find and tap" or "College and Assignments" stay intact.
        working = AND_VERB.replace(working, "|SPLIT|")
        SPLITTERS.forEach { splitter ->
            working = working.replace(splitter, "|SPLIT|", ignoreCase = true)
        }
        return working.split("|SPLIT|")
            .map { it.trim().trim(',', ';', '.') }
            .filter { it.isNotBlank() }
    }

    private suspend fun planClause(
        clause: String,
        request: PlanRequest,
        notes: MutableList<String>
    ): PlanResult {
        val raw = clause.trim().trim('.', '!', '?')
        val text = TextMatch.normalize(raw)
        if (text.isEmpty()) return PlanResult.NotCapable("empty clause", id)

        // --- navigation -----------------------------------------------------
        if (text.matches(BACK)) return plan(raw, step("Go back", Back, ScreenChanged(), timeoutMs = 4_000))
        if (text.matches(HOME)) return plan(raw, step("Go home", Home, ScreenChanged(), timeoutMs = 4_000))
        if (text.matches(RECENTS)) return plan(raw, step("Open recents", Recents, ScreenChanged(), timeoutMs = 4_000))

        WAIT.matchEntire(text)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: 1L
            val unit = match.groupValues[2]
            val millis = if (unit.startsWith("ms")) amount else amount * 1000L
            return plan(raw, step("Wait ${amount}${unit.ifBlank { "s" }}", Wait(millis.coerceIn(100L, 60_000L)), Always, timeoutMs = millis + 2_000))
        }

        if (text.matches(READ_SCREEN)) {
            return plan(raw, step("Read the screen", ReadScreen(), Always, timeoutMs = 3_000))
        }
        if (text.matches(SCREENSHOT)) {
            return plan(
                raw,
                step("Capture the screen", Screenshot("user request"), Always, timeoutMs = 6_000,
                    riskOverride = RiskLevel.MEDIUM)
            )
        }

        // --- apps -----------------------------------------------------------
        OPEN.matchEntire(text)?.let { match ->
            val label = cleanTarget(match.groupValues[1])
            return launchPlan(raw, label, request)
        }

        // --- file-ish composites --------------------------------------------
        RENAME.matchEntire(text)?.let { match ->
            return renamePlan(raw, cleanTarget(match.groupValues[1]), cleanTarget(match.groupValues[2]))
        }
        MOVE.matchEntire(text)?.let { match ->
            return movePlan(raw, cleanTarget(match.groupValues[1]), cleanTarget(match.groupValues[2]))
        }

        // --- direct manipulation --------------------------------------------
        LONG_PRESS.matchEntire(text)?.let { match ->
            val query = ElementQuery.byAnyLabel(cleanTarget(match.groupValues[1]))
            return plan(
                raw,
                step("Long press ${query.primaryLocator()}", LongPress(query), ScreenChanged(), timeoutMs = 6_000)
            )
        }
        FIND_AND_TAP.matchEntire(text)?.let { match ->
            val query = ElementQuery.byAnyLabel(cleanTarget(match.groupValues[1]))
            return plan(
                raw,
                step("Find ${query.primaryLocator()}", FindElement(query), Always, timeoutMs = 4_000),
                step("Tap ${query.primaryLocator()}", Tap(query), ScreenChanged(), timeoutMs = 6_000)
            )
        }
        TAP.matchEntire(text)?.let { match ->
            val query = ElementQuery.byAnyLabel(cleanTarget(match.groupValues[1]))
            return plan(raw, step("Tap ${query.primaryLocator()}", Tap(query), ScreenChanged(), timeoutMs = 6_000))
        }

        TYPE_INTO.matchEntire(text)?.let { match ->
            val value = unquote(match.groupValues[1])
            val field = ElementQuery.byAnyLabel(cleanTarget(match.groupValues[2]))
            return plan(
                raw,
                step(
                    "Type into ${field.primaryLocator()}",
                    Type(value, field, clearFirst = true),
                    FieldContains(value, field),
                    timeoutMs = 8_000,
                    retry = RetryPolicy.PATIENT
                )
            )
        }
        TYPE.matchEntire(text)?.let { match ->
            val value = unquote(match.groupValues[1])
            if (value.isBlank()) {
                return PlanResult.Clarify("What should I type?", id = id)
            }
            val field = ElementQuery(editable = true, focused = true, label = "the focused field")
            return plan(
                raw,
                step("Type ${value.length} character(s)", Type(value, field, clearFirst = true),
                    FieldContains(value, field), timeoutMs = 8_000, retry = RetryPolicy.PATIENT)
            )
        }

        SCROLL.matchEntire(text)?.let { match ->
            val direction = Direction.parse(match.groupValues[1]) ?: Direction.DOWN
            val times = (match.groupValues[2].toIntOrNull() ?: 1).coerceIn(1, 10)
            return plan(
                raw,
                step(
                    "Scroll ${direction.name.lowercase()}" + if (times > 1) " x$times" else "",
                    Scroll(direction = direction, times = times),
                    ScreenChanged(),
                    timeoutMs = 4_000 + times * 900L
                )
            )
        }

        SEARCH.matchEntire(text)?.let { match ->
            return searchPlan(raw, cleanTarget(match.groupValues[1]), match.groupValues[2].ifBlank { null })
        }

        // --- things that need a conversation --------------------------------
        if (text.matches(ORGANISE)) {
            return PlanResult.Clarify(
                "I can do this, but I need two things: which folder should I look in, and where should " +
                    "each kind of file go? For example: \"look in Downloads, put PDFs in College/Assignments\".",
                listOf("Look in Downloads", "Look in Documents", "Never mind"),
                id
            )
        }
        if (text.matches(MONITOR)) {
            return PlanResult.Clarify(
                "Sure - what should I wait for? Give me the text that appears when it finishes " +
                    "(for example \"Download complete\") and I'll watch for it.",
                id = id
            )
        }

        return PlanResult.NotCapable("no rule matched \"$raw\"", id)
    }

    // ------------------------------------------------------------- plans

    private suspend fun launchPlan(raw: String, label: String, request: PlanRequest): PlanResult {
        if (label.isBlank()) return PlanResult.Clarify("Which app should I open?", id = id)
        val pkg = apps?.resolve(label)
        val postcondition = if (pkg != null) PackageIs(pkg) else ScreenChanged()
        return plan(
            raw,
            step(
                "Open $label",
                LaunchApp(appLabel = label, packageName = pkg),
                postcondition,
                timeoutMs = 9_000
            )
        )
    }

    private fun renamePlan(raw: String, target: String, newName: String): PlanResult {
        if (target.isBlank() || newName.isBlank()) {
            return PlanResult.Clarify("Rename what, to what?", id = id)
        }
        val item = ElementQuery.byAnyLabel(target)
        val steps = listOf(
            step("Find $target", FindElement(item), ElementPresent(item), timeoutMs = 6_000),
            step("Open the menu for $target", LongPress(item), ScreenChanged(), timeoutMs = 6_000),
            step(
                "Choose Rename",
                Tap(ElementQuery.byAnyLabel("Rename")),
                ScreenChanged(),
                timeoutMs = 6_000
            ),
            step(
                "Type the new name",
                Type(newName, ElementQuery(editable = true, focused = true, label = "the name field"), clearFirst = true),
                FieldContains(newName),
                timeoutMs = 8_000,
                retry = RetryPolicy.PATIENT
            ),
            step(
                "Confirm the new name",
                Tap(
                    ElementQuery(
                        text = "OK",
                        textContains = "Rename",
                        contentDescription = "OK",
                        label = "the confirm button"
                    )
                ),
                ScreenChanged(),
                timeoutMs = 6_000,
                riskOverride = RiskLevel.MEDIUM
            ),
            step("Check the file is now called $newName", FindElement(ElementQuery.byAnyLabel(newName)),
                ElementPresent(ElementQuery.byAnyLabel(newName)), timeoutMs = 6_000)
        )
        return PlanResult.Plan(
            goal = raw,
            steps = steps,
            notes = "Rename flow: long press -> Rename -> type -> confirm. Menu labels differ per file manager, " +
                "so each step is verified and recovered individually.",
            risk = RiskLevel.MEDIUM,
            plannerId = id
        )
    }

    private fun movePlan(raw: String, target: String, destination: String): PlanResult {
        if (target.isBlank() || destination.isBlank()) {
            return PlanResult.Clarify("Move what, and where to?", id = id)
        }
        val item = ElementQuery.byAnyLabel(target)
        val segments = destination.split('/', '>', '\\').map { it.trim() }.filter { it.isNotBlank() }
        val steps = mutableListOf(
            step("Find $target", FindElement(item), ElementPresent(item), timeoutMs = 6_000),
            step("Open the menu for $target", LongPress(item), ScreenChanged(), timeoutMs = 6_000),
            step("Choose Move", Tap(ElementQuery(text = "Move", textContains = "Move to", label = "Move")),
                ScreenChanged(), timeoutMs = 6_000)
        )
        segments.forEachIndexed { index, segment ->
            steps += step(
                "Open folder $segment",
                Tap(ElementQuery.byAnyLabel(segment)),
                ScreenChanged(),
                timeoutMs = 6_000,
                retry = RetryPolicy(maxAttempts = 4, backoffMs = 400L)
            ).let { it.copy(precondition = null, note = "destination segment ${index + 1}/${segments.size}") }
        }
        steps += step(
            "Confirm the move",
            Tap(
                ElementQuery(
                    text = "Move here",
                    textContains = "Move",
                    contentDescription = "Move here",
                    label = "the move confirmation"
                )
            ),
            ScreenChanged(),
            timeoutMs = 6_000,
            riskOverride = RiskLevel.MEDIUM
        )
        steps += step(
            "Check $target is in $destination",
            FindElement(item),
            ElementPresent(item),
            timeoutMs = 6_000
        )
        return PlanResult.Plan(
            goal = raw,
            steps = steps,
            notes = "Move flow: long press -> Move -> walk the destination folders -> confirm. " +
                "Folder names are tapped in order; if a folder is not visible the step scrolls and retries.",
            risk = RiskLevel.MEDIUM,
            plannerId = id
        )
    }

    private fun searchPlan(raw: String, query: String, appLabel: String?): PlanResult {
        if (query.isBlank()) return PlanResult.Clarify("What should I search for?", id = id)
        val steps = mutableListOf<TaskStep>()
        if (!appLabel.isNullOrBlank()) {
            steps += step("Open $appLabel", LaunchApp(appLabel = appLabel), ScreenChanged(), timeoutMs = 9_000)
        }
        val searchField = ElementQuery(
            textContains = "Search",
            contentDescription = "Search",
            descriptionContains = "Search",
            editable = null,
            label = "the search box"
        )
        steps += step("Open the search box", Tap(searchField), ScreenChanged(), timeoutMs = 6_000)
        steps += step(
            "Search for $query",
            Type(query, ElementQuery(editable = true, focused = true, label = "the search field"), clearFirst = true, pressEnter = true),
            TextPresent(query),
            timeoutMs = 9_000,
            retry = RetryPolicy.PATIENT
        )
        return PlanResult.Plan(
            goal = raw,
            steps = steps,
            notes = "Search flow: open the search box, type, submit. If the keyboard action key cannot be " +
                "pressed on this device I will ask you to press it.",
            plannerId = id
        )
    }

    // ----------------------------------------------------------- utilities

    private fun plan(goal: String, vararg steps: TaskStep) = PlanResult.Plan(
        goal = goal,
        steps = steps.toList(),
        risk = steps.fold(RiskLevel.LOW) { acc, s -> if (s.risk.rank > acc.rank) s.risk else acc },
        plannerId = id
    )

    private fun step(
        title: String,
        action: AgentAction,
        postcondition: Expectation? = null,
        timeoutMs: Long = 6_000L,
        retry: RetryPolicy = RetryPolicy.DEFAULT,
        riskOverride: RiskLevel? = null
    ) = TaskStep(
        id = Ids.step(),
        title = title,
        action = action,
        postcondition = postcondition,
        timeoutMs = timeoutMs,
        retry = retry,
        riskOverride = riskOverride
    )

    private fun cleanTarget(raw: String): String = unquote(raw)
        .removePrefix("the ")
        .removeSuffix(" app")
        .removeSuffix(" button")
        .removeSuffix(" option")
        .removeSuffix(" menu")
        .trim()

    private fun unquote(raw: String): String =
        raw.trim().trim('"', '\u201C', '\u201D', '\'', '\u2018', '\u2019').trim()

    private fun stripPoliteness(raw: String): String {
        var text = raw.trim()
        POLITE_PREFIXES.forEach { prefix ->
            if (text.startsWith(prefix, ignoreCase = true)) text = text.removePrefix(prefix).trim()
        }
        return text.trimStart(',', ' ')
    }

    companion object {
        private const val TAG = "planner"

        val POLITE_PREFIXES = listOf(
            "please ", "pls ", "could you ", "can you ", "would you ", "hey agent ", "agent ",
            "deuty ", "i want you to ", "i need you to ", "go ahead and "
        )

        /** Split only on unambiguous sequencing words; never on a bare "and". */
        val SPLITTERS = listOf(", then ", " then ", "; ", " after that ", " finally ", " next, ")

        /** "and" + an action verb is a new clause; any other "and" is part of a name. */
        private val AND_VERB = Regex(
            " and (?=(?:tap|type|scroll|open|press|find|search|rename|move|swipe|click|long|wait|go|take|read|launch)\\b)",
            RegexOption.IGNORE_CASE
        )

        private val BACK = Regex("^(go back|back|press back|navigate back|go back one)$")
        private val HOME = Regex("^(go home|home|press home|go to home screen)$")
        private val RECENTS = Regex("^(recents|recent apps|open recents|show recent apps|task switcher)$")
        private val WAIT = Regex("^wait(?: for)? (\\d+)\\s?(ms|s|sec|secs|seconds|second)?$")
        private val READ_SCREEN = Regex(
            "^(read (?:the )?screen|what(?:'s| is) on (?:the |my )?screen|describe (?:this |the )?screen|" +
                "where am i|which app is (?:this|open)|tell me what you see|look at the screen)$"
        )
        private val SCREENSHOT = Regex("^(?:(?:take|grab) a )?(?:screenshot|screen capture|screen shot)$")
        private val ORGANISE = Regex("^(?:organise|organize|clean up|clean|tidy up|tidy|sort)(?: my| the)? .*$")
        private val MONITOR = Regex("^(?:watch|monitor|keep an eye on|track) .*$")

        private val OPEN = Regex("^(?:open|launch|start|go to|show me|bring up) (.+)$")
        private val TAP = Regex("^(?:tap|click|press|hit|select|choose|touch) (?:on )?(?:the )?(.+)$")
        private val FIND_AND_TAP = Regex("^(?:find and tap|find and click|find and press|locate and tap) (.+)$")
        private val LONG_PRESS = Regex("^(?:long press|long-press|press and hold|hold down|hold) (?:on )?(?:the )?(.+)$")
        private val TYPE_INTO = Regex("^(?:type|enter|input|write|fill in|fill|put) (.+?) (?:in|into|to) (.+)$")
        private val TYPE = Regex("^(?:type|enter|input|write) (.+)$")
        private val SCROLL = Regex("^(?:scroll|swipe|page|flick) ?(up|down|left|right|upwards|downwards)? ?(\\d+)? ?(?:times|pages|more)?$")
        private val SEARCH = Regex("^(?:search for|search|look for|look up|find|google) (.+?)(?: in (.+))?$")
        private val RENAME = Regex("^(?:rename|rename the file|rename it) (.+?) (?:to|as) (.+)$")
        private val MOVE = Regex("^(?:move|move the file|transfer|file) (.+?) (?:to|into|inside) (.+)$")
        private val WORKFLOW_RUN = Regex("^(?:run|replay|play|do|execute|start)(?: the)?(?: workflow)?(?: called)? (.+)$")
    }
}

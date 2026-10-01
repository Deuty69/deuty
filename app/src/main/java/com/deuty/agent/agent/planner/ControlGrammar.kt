package com.deuty.agent.agent.planner

import com.deuty.agent.core.util.TextMatch

/**
 * The control vocabulary, shared by the planner and the engine.
 *
 * It lives outside the planner for one concrete reason: STOP has to work while a
 * task is running, and while a task is running the engine is not asking a planner
 * anything. The engine therefore needs the same table, and duplicating a keyword
 * list in two places is how "stop" ends up understood in chat but not in the
 * overlay.
 *
 * These are matched against the *whole* normalised sentence. "stop" is a control
 * command; "stop the download" is a task.
 */
object ControlGrammar {

    private val STOP = setOf(
        "stop", "stop it", "stop now", "stop the task", "halt", "cancel", "cancel task",
        "cancel it", "abort", "quit", "emergency stop"
    )
    private val PAUSE = setOf("pause", "pause the task", "hold on", "hold", "wait for me", "wait a moment")
    private val RESUME = setOf("resume", "unpause", "continue", "carry on", "go on", "keep going")
    private val RETRY = setOf("retry", "retry the step", "try again", "again", "one more time")
    private val SKIP = setOf("skip", "skip this", "skip it", "skip this step", "next step", "move on")
    private val STATUS = setOf(
        "status", "what are you doing", "where are you", "progress", "how is it going",
        "what is happening", "what step are you on"
    )
    private val HELP = setOf(
        "help", "what can you do", "commands", "what do you understand", "how do i use this",
        "what do you know"
    )

    private val STUCK_PREFIXES = listOf(
        "why are you stuck", "why did you stop", "why are you waiting", "what went wrong",
        "why did that fail", "why are you"
    )

    fun parse(raw: String): ControlCommand? {
        val text = TextMatch.normalize(raw).trim('.', '!', '?', ' ')
        if (text.isEmpty()) return null
        return when {
            text in STOP -> ControlCommand.STOP
            text in PAUSE -> ControlCommand.PAUSE
            text in RESUME -> ControlCommand.RESUME
            text in RETRY -> ControlCommand.RETRY
            text in SKIP -> ControlCommand.SKIP
            text in STATUS -> ControlCommand.STATUS
            text in HELP -> ControlCommand.HELP
            STUCK_PREFIXES.any { text.startsWith(it) } -> ControlCommand.WHY_STUCK
            else -> null
        }
    }
}

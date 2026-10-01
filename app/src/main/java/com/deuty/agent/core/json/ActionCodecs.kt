package com.deuty.agent.core.json

import com.deuty.agent.agent.executor.AgentAction
import com.deuty.agent.agent.executor.AskUser
import com.deuty.agent.agent.executor.Back
import com.deuty.agent.agent.executor.Conditional
import com.deuty.agent.agent.executor.FindElement
import com.deuty.agent.agent.executor.Home
import com.deuty.agent.agent.executor.LaunchApp
import com.deuty.agent.agent.executor.LongPress
import com.deuty.agent.agent.executor.Paste
import com.deuty.agent.agent.executor.ReadScreen
import com.deuty.agent.agent.executor.Relaunch
import com.deuty.agent.agent.executor.Recents
import com.deuty.agent.agent.executor.Repeat
import com.deuty.agent.agent.executor.Report
import com.deuty.agent.agent.executor.RunWorkflow
import com.deuty.agent.agent.executor.Screenshot
import com.deuty.agent.agent.executor.Scroll
import com.deuty.agent.agent.executor.Swipe
import com.deuty.agent.agent.executor.Tap
import com.deuty.agent.agent.executor.Type
import com.deuty.agent.agent.executor.Wait
import com.deuty.agent.core.model.AllOf
import com.deuty.agent.core.model.AnyOf
import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.Direction
import com.deuty.agent.core.model.ElementAbsent
import com.deuty.agent.core.model.ElementPresent
import com.deuty.agent.core.model.ElementQuery
import com.deuty.agent.core.model.Expectation
import com.deuty.agent.core.model.FieldCleared
import com.deuty.agent.core.model.FieldContains
import com.deuty.agent.core.model.PackageIs
import com.deuty.agent.core.model.Point
import com.deuty.agent.core.model.RelativePosition
import com.deuty.agent.core.model.RiskLevel
import com.deuty.agent.core.model.ScreenChanged
import com.deuty.agent.core.model.ScreenUnchanged
import com.deuty.agent.core.model.TextAbsent
import com.deuty.agent.core.model.TextPresent
import com.deuty.agent.core.model.Until
import com.deuty.agent.core.model.WindowIs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Codecs for the action vocabulary, the locator model and expectations.
 *
 * Two rules shaped this file:
 *  1. Every record carries a format version. The agent persists state that
 *     outlives the process, so an upgrade must degrade gracefully: an unknown
 *     action type decodes to null and the step is skipped with a clear message,
 *     instead of throwing at launch.
 *  2. Short keys. This JSON is written on the main thread inside accessibility
 *     callbacks and read back on every cold start; small payloads are cheaper
 *     than pretty ones, and the field names live in exactly one place.
 */
object ActionCodecs {

    const val FORMAT_VERSION = 1

    // ---------------------------------------------------------------- Bounds

    fun encodeBounds(b: Bounds): JSONObject =
        Json.obj("l" to b.left, "t" to b.top, "r" to b.right, "b" to b.bottom)

    fun decodeBounds(o: JSONObject?): Bounds? = o?.let {
        Bounds(it.intOr("l", 0), it.intOr("t", 0), it.intOr("r", 0), it.intOr("b", 0))
    }

    fun encodePoint(p: Point): JSONObject = Json.obj("x" to p.x, "y" to p.y)

    fun decodePoint(o: JSONObject?): Point? = o?.let { Point(it.intOr("x", 0), it.intOr("y", 0)) }

    // ------------------------------------------------------------ ElementQuery

    fun encodeQuery(q: ElementQuery?): JSONObject? {
        if (q == null) return null
        return Json.obj(
        "viewId" to q.viewId,
        "idName" to q.idName,
        "text" to q.text,
        "contains" to q.textContains,
        "startsWith" to q.textStartsWith,
        "desc" to q.contentDescription,
        "descContains" to q.descriptionContains,
        "cls" to q.className,
        "editable" to q.editable,
        "clickable" to q.clickable,
        "scrollable" to q.scrollable,
        "checked" to q.checked,
        "focused" to q.focused,
        "enabled" to q.enabled,
        "index" to if (q.index == 0) null else q.index,
        "near" to q.nearText,
        "rel" to q.relativePosition?.name,
        "bounds" to q.bounds?.let { encodeBounds(it) },
        "ocr" to q.ocrText,
        "point" to q.point?.let { encodePoint(it) },
        "label" to q.label,
        "pw" to if (q.matchPasswordFields) true else null
        )
    }

    fun decodeQuery(o: JSONObject?): ElementQuery? {
        if (o == null) return null
        return ElementQuery(
            viewId = o.strOrNull("viewId"),
            idName = o.strOrNull("idName"),
            text = o.strOrNull("text"),
            textContains = o.strOrNull("contains"),
            textStartsWith = o.strOrNull("startsWith"),
            contentDescription = o.strOrNull("desc"),
            descriptionContains = o.strOrNull("descContains"),
            className = o.strOrNull("cls"),
            editable = o.boolOrNull("editable"),
            clickable = o.boolOrNull("clickable"),
            scrollable = o.boolOrNull("scrollable"),
            checked = o.boolOrNull("checked"),
            focused = o.boolOrNull("focused"),
            enabled = o.boolOrNull("enabled"),
            index = o.intOr("index", 0),
            nearText = o.strOrNull("near"),
            relativePosition = o.enumOrNull<RelativePosition>("rel"),
            bounds = decodeBounds(o.objOrNull("bounds")),
            ocrText = o.strOrNull("ocr"),
            point = decodePoint(o.objOrNull("point")),
            label = o.strOrNull("label"),
            matchPasswordFields = o.boolOr("pw", false)
        )
    }

    // ----------------------------------------------------------- Expectation

    fun encodeExpectation(e: Expectation?): JSONObject? {
        if (e == null) return null
        val o = JSONObject()
        when (e) {
            is ElementPresent -> o.set("t", "present").set("q", encodeQuery(e.query))
            is ElementAbsent -> o.set("t", "absent").set("q", encodeQuery(e.query))
            is TextPresent -> o.set("t", "text").set("v", e.text).set("ic", e.ignoreCase)
            is TextAbsent -> o.set("t", "notext").set("v", e.text).set("ic", e.ignoreCase)
            is ScreenChanged -> o.set("t", "changed").set("sig", e.fromSignature)
            is ScreenUnchanged -> o.set("t", "unchanged").set("sig", e.fromSignature)
            is PackageIs -> o.set("t", "pkg").set("v", e.packageName)
            is WindowIs -> o.set("t", "window").set("v", e.titleContains)
            is FieldCleared -> o.set("t", "cleared").set("q", encodeQuery(e.query))
            is FieldContains -> o.set("t", "fieldhas").set("v", e.text).set("q", encodeQuery(e.query))
            is AnyOf -> o.set("t", "any").set("o", encodeExpectations(e.options))
            is AllOf -> o.set("t", "all").set("o", encodeExpectations(e.options))
            is Until -> o.set("t", "until").set("o", encodeExpectation(e.expectation)).set("max", e.maxRounds)
            else -> o.set("t", "always")
        }
        return o
    }

    private fun encodeExpectations(list: List<Expectation>): JSONArray {
        val a = JSONArray()
        list.forEach { encodeExpectation(it)?.let(a::put) }
        return a
    }

    fun decodeExpectation(o: JSONObject?): Expectation? {
        if (o == null) return null
        return when (o.strOrNull("t")) {
            "present" -> ElementPresent(decodeQuery(o.objOrNull("q")) ?: return null)
            "absent" -> ElementAbsent(decodeQuery(o.objOrNull("q")) ?: return null)
            "text" -> TextPresent(o.strOrNull("v") ?: return null, o.boolOr("ic", true))
            "notext" -> TextAbsent(o.strOrNull("v") ?: return null, o.boolOr("ic", true))
            "changed" -> ScreenChanged(o.strOrNull("sig"))
            "unchanged" -> ScreenUnchanged(o.strOrNull("sig"))
            "pkg" -> PackageIs(o.strOrNull("v") ?: return null)
            "window" -> WindowIs(o.strOrNull("v") ?: return null)
            "cleared" -> FieldCleared(decodeQuery(o.objOrNull("q")))
            "fieldhas" -> FieldContains(o.strOrNull("v") ?: return null, decodeQuery(o.objOrNull("q")))
            "any" -> AnyOf(decodeExpectations(o.arrOrNull("o")))
            "all" -> AllOf(decodeExpectations(o.arrOrNull("o")))
            "until" -> Until(decodeExpectation(o.objOrNull("o")) ?: return null, o.intOr("max", 8))
            "always" -> com.deuty.agent.core.model.Always
            else -> null
        }
    }

    private fun decodeExpectations(a: JSONArray?): List<Expectation> {
        if (a == null) return emptyList()
        return a.objects().mapNotNull { decodeExpectation(it) }
    }

    // --------------------------------------------------------------- Actions

    fun encodeAction(action: AgentAction): JSONObject {
        val o = JSONObject().set("t", action.label).set("v", FORMAT_VERSION)
        when (action) {
            is Tap -> o.set("q", encodeQuery(action.target)).set("times", action.times).set("interval", action.intervalMs)
            is LongPress -> o.set("q", encodeQuery(action.target)).set("dur", action.durationMs)
            is Swipe -> o.set("dir", action.direction.name)
                .set("ratio", action.distanceRatio.toDouble())
                .set("dur", action.durationMs)
                .set("start", action.startPoint?.let { encodePoint(it) })
            is Scroll -> o.set("dir", action.direction.name)
                .set("times", action.times)
                .set("maxTimes", action.maxTimes)
                .set("container", encodeQuery(action.container))
                .set("until", encodeQuery(action.untilVisible))
            is Type -> o.set("text", action.text)
                .set("q", encodeQuery(action.target))
                .set("clear", action.clearFirst)
                .set("enter", action.pressEnter)
            is Paste -> o.set("q", encodeQuery(action.target)).set("text", action.text)
            is LaunchApp -> o.set("app", action.appLabel).set("pkg", action.packageName)
            is Wait -> o.set("ms", action.millis).set("until", encodeExpectation(action.until))
            is ReadScreen -> o.set("max", action.maxItems).set("report", action.reportToUser)
            is Screenshot -> o.set("why", action.reason)
                .set("region", action.region?.let { encodeBounds(it) })
                .set("ocr", action.runOcr)
            is FindElement -> o.set("q", encodeQuery(action.query)).set("report", action.reportOnly)
            is Conditional -> o.set("cond", encodeExpectation(action.condition))
                .set("then", encodeActions(action.then))
                .set("else", encodeActions(action.otherwise))
            is Repeat -> o.set("body", encodeActions(action.body))
                .set("max", action.maxTimes)
                .set("until", encodeExpectation(action.until))
            is AskUser -> o.set("question", action.question)
                .setStrings("options", action.options)
                .set("freeText", action.allowFreeText)
                .set("risk", action.risk.name)
                .set("denyStops", action.denyStopsTask)
            is RunWorkflow -> o.set("wf", action.workflowId).set("name", action.workflowName)
            is Relaunch -> o.set("pkg", action.packageName)
            is Report -> o.set("msg", action.message)
            Back -> Unit
            Home -> Unit
            Recents -> Unit
        }
        return o
    }

    fun encodeActions(list: List<AgentAction>): JSONArray {
        val a = JSONArray()
        list.forEach { a.put(encodeAction(it)) }
        return a
    }

    /** Returns null for unknown/corrupt actions; callers must handle that. */
    fun decodeAction(o: JSONObject?): AgentAction? {
        if (o == null) return null
        return when (o.strOrNull("t")) {
            "tap" -> Tap(
                target = decodeQuery(o.objOrNull("q")) ?: return null,
                times = o.intOr("times", 1).coerceIn(1, 5),
                intervalMs = o.longOr("interval", 90L)
            )
            "long_press" -> LongPress(
                target = decodeQuery(o.objOrNull("q")) ?: return null,
                durationMs = o.longOr("dur", 650L)
            )
            "swipe" -> Swipe(
                direction = o.enumOrNull<Direction>("dir") ?: Direction.DOWN,
                distanceRatio = o.dblOr("ratio", 0.55).toFloat().coerceIn(0.05f, 0.95f),
                durationMs = o.longOr("dur", 280L),
                startPoint = decodePoint(o.objOrNull("start"))
            )
            "scroll" -> Scroll(
                direction = o.enumOrNull<Direction>("dir") ?: Direction.DOWN,
                container = decodeQuery(o.objOrNull("container")),
                times = o.intOr("times", 1).coerceIn(1, 20),
                untilVisible = decodeQuery(o.objOrNull("until")),
                maxTimes = o.intOr("maxTimes", 12).coerceIn(1, 30)
            )
            "type" -> Type(
                text = o.strOrNull("text") ?: "",
                target = decodeQuery(o.objOrNull("q")),
                clearFirst = o.boolOr("clear", true),
                pressEnter = o.boolOr("enter", false)
            )
            "paste" -> Paste(decodeQuery(o.objOrNull("q")), o.strOrNull("text"))
            "back" -> Back
            "home" -> Home
            "recents" -> Recents
            "launch_app" -> LaunchApp(o.strOrNull("app"), o.strOrNull("pkg"))
            "wait" -> Wait(o.longOr("ms", 500L), decodeExpectation(o.objOrNull("until")))
            "read_screen" -> ReadScreen(o.intOr("max", 14), o.boolOr("report", true))
            "screenshot" -> Screenshot(
                reason = o.strOrNull("why") ?: "requested",
                region = decodeBounds(o.objOrNull("region")),
                runOcr = o.boolOr("ocr", false)
            )
            "find" -> FindElement(decodeQuery(o.objOrNull("q")) ?: return null, o.boolOr("report", true))
            "if" -> Conditional(
                condition = decodeExpectation(o.objOrNull("cond")) ?: return null,
                then = decodeActions(o.arrOrNull("then")),
                otherwise = decodeActions(o.arrOrNull("else"))
            )
            "repeat" -> Repeat(
                body = decodeActions(o.arrOrNull("body")),
                maxTimes = o.intOr("max", 5).coerceIn(1, 20),
                until = decodeExpectation(o.objOrNull("until"))
            )
            "ask_user" -> AskUser(
                question = o.strOrNull("question") ?: "Continue?",
                options = o.strList("options"),
                allowFreeText = o.boolOr("freeText", true),
                risk = o.enumOrNull<RiskLevel>("risk") ?: RiskLevel.MEDIUM,
                denyStopsTask = o.boolOr("denyStops", false)
            )
            "run_workflow" -> RunWorkflow(o.strOrNull("wf"), o.strOrNull("name"))
            "relaunch" -> Relaunch(o.strOrNull("pkg") ?: return null)
            "report" -> Report(o.strOrNull("msg") ?: "")
            else -> null
        }
    }

    fun decodeActions(a: JSONArray?): List<AgentAction> {
        if (a == null) return emptyList()
        return a.objects().mapNotNull { decodeAction(it) }
    }
}

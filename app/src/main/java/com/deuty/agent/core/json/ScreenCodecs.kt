package com.deuty.agent.core.json

import com.deuty.agent.core.model.Bounds
import com.deuty.agent.core.model.OcrLine
import com.deuty.agent.core.model.ScreenSnapshot
import com.deuty.agent.core.model.ScreenSource
import com.deuty.agent.core.model.UiNode
import com.deuty.agent.core.util.ScreenSignature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screen snapshot persistence (requirement 27).
 *
 * Two uses, both worth more than the ~150 lines:
 *   1. The inspector's "Dump JSON" button, which is how a bug on a real device
 *      becomes a reproducible artifact.
 *   2. Offline replay: a dumped screen can be loaded in a JVM test and fed to the
 *      element resolver, so a targeting bug found on one user's phone can be fixed
 *      against that exact screen forever after. Without a decoder this file format
 *      would be write-only, which is the same as useless.
 *
 * Redaction: snapshots are dumped as-is, because a redacted tree cannot be used to
 * reproduce a targeting bug (the resolver matched on the real text). Dumps live in
 * the app's private directory, are never uploaded (no INTERNET permission), and the
 * inspector says so next to the button. Password fields keep their `isPassword`
 * flag but their text is never captured in the first place - see
 * [com.deuty.agent.platform.AccessibilityScreenSource].
 */
object ScreenCodecs {

    const val FORMAT_VERSION = 1

    fun encodeNode(node: UiNode): JSONObject = Json.obj(
        "key" to node.key,
        "parent" to node.parentKey,
        "depth" to node.depth,
        "text" to node.text,
        "desc" to node.contentDescription,
        "id" to node.viewIdResourceName,
        "class" to node.className,
        "pkg" to node.packageName,
        "bounds" to ActionCodecs.encodeBounds(node.bounds),
        "clickable" to node.isClickable,
        "longClickable" to node.isLongClickable,
        "scrollable" to node.isScrollable,
        "editable" to node.isEditable,
        "checked" to node.isChecked,
        "selected" to node.isSelected,
        "enabled" to node.isEnabled,
        "focused" to node.isFocused,
        "password" to node.isPassword,
        "children" to node.childCount
    )

    fun decodeNode(o: JSONObject?): UiNode? {
        if (o == null) return null
        val bounds = ActionCodecs.decodeBounds(o.objOrNull("bounds")) ?: Bounds(0, 0, 0, 0)
        return UiNode(
            key = o.intOr("key", 0),
            parentKey = if (o.has("parent") && !o.isNull("parent")) o.optInt("parent") else null,
            depth = o.intOr("depth", 0),
            text = o.strOrNull("text"),
            contentDescription = o.strOrNull("desc"),
            viewIdResourceName = o.strOrNull("id"),
            className = o.strOrNull("class"),
            packageName = o.strOrNull("pkg"),
            bounds = bounds,
            isClickable = o.boolOr("clickable", false),
            isLongClickable = o.boolOr("longClickable", false),
            isScrollable = o.boolOr("scrollable", false),
            isEditable = o.boolOr("editable", false),
            isChecked = o.boolOr("checked", false),
            isSelected = o.boolOr("selected", false),
            isEnabled = o.boolOr("enabled", true),
            isFocused = o.boolOr("focused", false),
            isPassword = o.boolOr("password", false),
            childCount = o.intOr("children", 0)
        )
    }

    fun encodeOcr(line: OcrLine): JSONObject = Json.obj(
        "text" to line.text,
        "bounds" to ActionCodecs.encodeBounds(line.bounds),
        "confidence" to line.confidence.toDouble()
    )

    fun decodeOcr(o: JSONObject?): OcrLine? {
        if (o == null) return null
        val bounds = ActionCodecs.decodeBounds(o.objOrNull("bounds")) ?: return null
        return OcrLine(
            text = o.strOrNull("text") ?: return null,
            bounds = bounds,
            confidence = o.dblOr("confidence", 1.0).toFloat()
        )
    }

    fun encodeSnapshot(snapshot: ScreenSnapshot): JSONObject {
        val nodes = JSONArray()
        snapshot.nodes.forEach { nodes.put(encodeNode(it)) }
        val ocr = JSONArray()
        snapshot.ocrLines.forEach { ocr.put(encodeOcr(it)) }
        return Json.obj(
            "format" to FORMAT_VERSION,
            "kind" to "screen",
            "package" to snapshot.packageName,
            "window" to snapshot.windowTitle,
            "source" to snapshot.source.name,
            "capturedAt" to snapshot.capturedAtMs,
            "width" to snapshot.displayWidth,
            "height" to snapshot.displayHeight,
            "secure" to snapshot.isSecure,
            "signature" to snapshot.signature,
            "truncated" to snapshot.truncated,
            "captureMs" to snapshot.captureDurationMs,
            "nodes" to nodes,
            "ocr" to ocr
        )
    }

    fun decodeSnapshot(o: JSONObject?): ScreenSnapshot? {
        if (o == null) return null
        val nodes = o.arrOrNull("nodes")?.objects()?.mapNotNull { decodeNode(it) } ?: emptyList()
        val ocr = o.arrOrNull("ocr")?.objects()?.mapNotNull { decodeOcr(it) } ?: emptyList()
        val source = o.enumOrNull<ScreenSource>("source") ?: ScreenSource.UNAVAILABLE
        val snapshot = ScreenSnapshot(
            packageName = o.strOrNull("package"),
            windowTitle = o.strOrNull("window"),
            nodes = nodes,
            source = source,
            capturedAtMs = o.longOr("capturedAt", 0L),
            displayWidth = o.intOr("width", 0),
            displayHeight = o.intOr("height", 0),
            isSecure = o.boolOr("secure", false),
            ocrLines = ocr,
            signature = o.strOrNull("signature") ?: "",
            truncated = o.boolOr("truncated", false),
            captureDurationMs = o.longOr("captureMs", 0L)
        )
        // A dump with no signature (older format, hand-edited) is recomputed so it
        // can still be used as a verification baseline.
        return if (snapshot.signature.isBlank()) {
            snapshot.copy(signature = ScreenSignature.compute(snapshot))
        } else snapshot
    }
}

/**
 * Human-readable tree for the inspector.
 *
 * Indented by depth, one line per node, interactive flags as single characters so
 * a 200-node screen still fits in a scrollable TextView: `c` clickable,
 * `l` long-clickable, `s` scrollable, `e` editable, `k` checked, `f` focused,
 * `p` password.
 */
fun ScreenSnapshot.toTreeText(maxNodes: Int = 400): String = buildString {
    append(packageName ?: "?").append(" / ").append(windowTitle ?: "-")
    append("  [").append(source.label).append(", ").append(nodes.size).append(" nodes")
    if (truncated) append(", TRUNCATED")
    if (isSecure) append(", SECURE")
    append("]\n")
    nodes.take(maxNodes).forEach { node ->
        append("  ".repeat(node.depth.coerceAtMost(8)))
        append(node.simpleClassName ?: "?")
        node.label?.let { append(" \"").append(it.take(48)).append('"') }
        node.idName?.let { append(" #").append(it) }
        val flags = buildString {
            if (node.isClickable) append('c')
            if (node.isLongClickable) append('l')
            if (node.isScrollable) append('s')
            if (node.isEditable) append('e')
            if (node.isChecked) append('k')
            if (node.isFocused) append('f')
            if (node.isPassword) append('p')
            if (!node.isEnabled) append('!')
        }
        if (flags.isNotEmpty()) append(" [").append(flags).append(']')
        append(" (").append(node.bounds.left).append(',').append(node.bounds.top)
            .append('-').append(node.bounds.right).append(',').append(node.bounds.bottom).append(")\n")
    }
    if (nodes.size > maxNodes) append("... ").append(nodes.size - maxNodes).append(" more node(s)\n")
    if (ocrLines.isNotEmpty()) {
        append("--- ocr (").append(ocrLines.size).append(" line(s)) ---\n")
        ocrLines.take(40).forEach { line ->
            append(line.text.take(80)).append("  @").append(line.bounds.centerY).append('\n')
        }
    }
}

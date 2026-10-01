package com.deuty.agent.core.model

/**
 * Where in the locator priority chain a match was found (requirement 8).
 *
 * Lower order == more stable across UI changes. The resolver always reports the
 * tier it used, so the debug view can say *why* something was tapped and so
 * workflows can be re-recorded when a good locator degrades to a bad one
 * (e.g. a recorded id disappears and only coordinates remain).
 */
enum class MatchTier(val order: Int, val label: String) {
    VIEW_ID(1, "resource-id"),
    EXACT_TEXT(2, "exact text"),
    CONTENT_DESCRIPTION(3, "content description"),
    TEXT_CONTAINS(4, "partial text"),
    SEMANTIC(5, "semantic properties"),
    RELATIVE(6, "relative structure"),
    APPROX_BOUNDS(7, "approximate bounds"),
    OCR(8, "ocr text"),
    COORDINATES(9, "coordinates");

    /** Coordinate/OCR targeting is brittle; the recorder warns when it is used. */
    val isBrittle: Boolean get() = order >= APPROX_BOUNDS.order
}

enum class RelativePosition { BELOW, ABOVE, LEFT_OF, RIGHT_OF, NEAREST, INSIDE }

/**
 * A declarative description of "the element I mean".
 *
 * Never coordinates-first: [viewId]/[idName]/[text]/[contentDescription] are
 * the primary locators, and [bounds]/[point] exist only as recorded fallbacks
 * or as the last resort for apps with no accessibility metadata.
 *
 * A query is data, not code: it can be persisted, replayed, diffed, shown to
 * the user, and produced by either the rule-based planner or an AI planner
 * without either one being able to execute arbitrary logic.
 */
data class ElementQuery(
    val viewId: String? = null,
    val idName: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    val textStartsWith: String? = null,
    val contentDescription: String? = null,
    val descriptionContains: String? = null,
    val className: String? = null,
    val editable: Boolean? = null,
    val clickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val enabled: Boolean? = null,
    val index: Int = 0,
    val nearText: String? = null,
    val relativePosition: RelativePosition? = null,
    val bounds: Bounds? = null,
    val ocrText: String? = null,
    val point: Point? = null,
    val label: String? = null,
    val matchPasswordFields: Boolean = false
) {
    /** Human-readable locator, in priority order, e.g. `text="Send"`. */
    fun describe(): String {
        label?.let { if (it.isNotBlank()) return it }
        val parts = mutableListOf<String>()
        viewId?.let { parts += "id=$it" }
        idName?.let { parts += "idName=$it" }
        text?.let { parts += "text=\"$it\"" }
        textContains?.let { parts += "text~\"$it\"" }
        textStartsWith?.let { parts += "text^\"$it\"" }
        contentDescription?.let { parts += "desc=\"$it\"" }
        descriptionContains?.let { parts += "desc~\"$it\"" }
        className?.let { parts += "class=$it" }
        editable?.let { parts += if (it) "editable" else "notEditable" }
        clickable?.let { if (it) parts += "clickable" }
        scrollable?.let { if (it) parts += "scrollable" }
        checked?.let { parts += if (it) "checked" else "unchecked" }
        focused?.let { if (it) parts += "focused" }
        enabled?.let { if (!it) parts += "disabled" }
        if (index > 0) parts += "index=$index"
        nearText?.let { parts += "near=\"$it\"" }
        relativePosition?.let { parts += it.name.lowercase() }
        bounds?.let { parts += "bounds=[${it.left},${it.top}][${it.right},${it.bottom}]" }
        ocrText?.let { parts += "ocr=\"$it\"" }
        point?.let { parts += "at=(${it.x},${it.y})" }
        return if (parts.isEmpty()) "any" else parts.joinToString(" ")
    }

    /** True if this query would only ever be satisfied by raw coordinates. */
    val isCoordinateOnly: Boolean
        get() = point != null && viewId == null && idName == null && text == null &&
            textContains == null && textStartsWith == null && contentDescription == null &&
            descriptionContains == null && ocrText == null && bounds == null

    /** True if the query has no locator at all (a resolver would return everything). */
    val isUnconstrained: Boolean
        get() = viewId == null && idName == null && text == null && textContains == null &&
            textStartsWith == null && contentDescription == null && descriptionContains == null &&
            className == null && editable == null && clickable == null && scrollable == null &&
            checked == null && focused == null && enabled == null && nearText == null &&
            bounds == null && ocrText == null && point == null

    /**
     * The single most important locator, used to build better queries from
     * recordings and to deduplicate repeated steps.
     */
    fun primaryLocator(): String = when {
        !viewId.isNullOrBlank() -> viewId
        !idName.isNullOrBlank() -> idName
        !text.isNullOrBlank() -> text
        !contentDescription.isNullOrBlank() -> contentDescription
        !textContains.isNullOrBlank() -> textContains
        !descriptionContains.isNullOrBlank() -> descriptionContains
        !ocrText.isNullOrBlank() -> ocrText
        bounds != null -> "bounds"
        point != null -> "point"
        else -> describe()
    }

    /** Drop the brittle recorded fallbacks, keeping only semantic locators. */
    fun withoutFallbacks(): ElementQuery = copy(bounds = null, point = null, index = 0)

    companion object {
        fun byText(value: String) = ElementQuery(text = value.trim(), label = "\"${value.trim()}\"")

        /** Accepts "Send", id/send, com.x:id/save and desc=Send. */
        fun byAnyLabel(value: String): ElementQuery {
            val raw = value.trim().trim('"', '\u201C', '\u201D', '\'')
            return when {
                raw.startsWith("id=", true) -> ElementQuery(viewId = raw.substring(3), label = raw)
                raw.startsWith("desc=", true) ->
                    ElementQuery(contentDescription = raw.substring(5), label = raw)
                raw.contains(":id/") -> ElementQuery(viewId = raw, label = raw)
                else -> ElementQuery(text = raw, contentDescription = raw, label = "\"$raw\"")
            }
        }

        fun focusedEditable() = ElementQuery(editable = true, focused = true, label = "focused text field")

        fun anyScrollable() = ElementQuery(scrollable = true, label = "scrollable container")
    }
}

/**
 * A resolved target. [node] is null for the OCR/coordinate tiers, in which case
 * [point] carries the tap location.
 */
data class MatchedElement(
    val node: UiNode?,
    val point: Point?,
    val tier: MatchTier,
    val score: Float,
    val reason: String
) {
    val isUsable: Boolean get() = node != null || point != null

    val tapPoint: Point?
        get() = node?.takeIf { it.isOnScreen }?.let { Point(it.bounds.centerX, it.bounds.centerY) } ?: point

    fun describe(): String = buildString {
        append("[").append(tier.label).append(" score=").append(String.format("%.2f", score)).append("] ")
        append(reason)
        node?.let { append(" -> ").append(it.describe()) }
            ?: point?.let { append(" -> (${it.x},${it.y})") }
    }
}

package com.deuty.agent.core.json

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal helpers over org.json.
 *
 * org.json is part of the Android framework, so the persistence layer adds no
 * dependency and no reflection (unlike Gson/Moshi/kotlinx.serialization codegen).
 * For the data volumes here - one active task, a bounded history, a handful of
 * workflows - hand-written codecs are ~400 lines of very boring, very testable
 * code and they cannot silently drop a field the way an annotation processor
 * can after an obfuscation change.
 */
object Json {

    /** Builds an object, skipping null values so files stay small and diffable. */
    fun obj(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.set(k, v)
        return o
    }

    fun arr(items: Collection<Any?>): JSONArray {
        val a = JSONArray()
        items.forEach { if (it != null) a.put(it) }
        return a
    }

    fun parse(text: String?): JSONObject? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }

    fun parseArray(text: String?): JSONArray? {
        if (text.isNullOrBlank()) return null
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            null
        }
    }
}

// --- null-tolerant readers ---------------------------------------------------

fun JSONObject.strOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.intOr(key: String, default: Int): Int = if (has(key) && !isNull(key)) optInt(key, default) else default

fun JSONObject.longOr(key: String, default: Long): Long = if (has(key) && !isNull(key)) optLong(key, default) else default

fun JSONObject.dblOr(key: String, default: Double): Double = if (has(key) && !isNull(key)) optDouble(key, default) else default

fun JSONObject.boolOr(key: String, default: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, default) else default

fun JSONObject.boolOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null

fun JSONObject.objOrNull(key: String): JSONObject? = if (has(key) && !isNull(key)) optJSONObject(key) else null

fun JSONObject.arrOrNull(key: String): JSONArray? = if (has(key) && !isNull(key)) optJSONArray(key) else null

fun JSONObject.strList(key: String): List<String> {
    val a = arrOrNull(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotEmpty() } }
}

fun JSONObject.strMap(key: String): Map<String, String> {
    val o = objOrNull(key) ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    o.keys().forEach { k -> o.strOrNull(k)?.let { out[k] = it } }
    return out
}

// --- null-tolerant writers ---------------------------------------------------
//
// Deliberately named `set`, not `putOpt`: org.json already has a *member*
// `putOpt(String, Object)` that stores JSONObject.NULL, and Kotlin members
// always win over extensions. A shadowed extension would compile happily and
// then write "field": null everywhere.

/**
 * Stores [value] under [key], skipping nulls so files stay small and diffable.
 * Only JSON-native types are written; enums become their name and anything else
 * is stringified, because Android's org.json throws on unserialisable objects
 * rather than calling toString() the way the reference implementation does.
 */
fun JSONObject.set(key: String, value: Any?): JSONObject {
    if (value == null) return this
    val safe: Any = when (value) {
        is String, is Int, is Long, is Double, is Float, is Boolean, is JSONObject, is JSONArray -> value
        is Enum<*> -> value.name
        else -> value.toString()
    }
    put(key, safe)
    return this
}

fun JSONObject.set(key: String, value: Int?): JSONObject = if (value == null) this else set(key, value.toInt() as Any)

fun JSONObject.set(key: String, value: Long?): JSONObject = if (value == null) this else set(key, value.toLong() as Any)

fun JSONObject.set(key: String, value: Boolean?): JSONObject = if (value == null) this else set(key, value.booleanValue() as Any)

fun JSONObject.setStrings(key: String, values: Collection<String>?): JSONObject =
    if (values.isNullOrEmpty()) this else set(key, Json.arr(values))

fun JSONObject.setObjects(key: String, values: Collection<JSONObject>?): JSONObject =
    if (values.isNullOrEmpty()) this else set(key, Json.arr(values))


inline fun <reified T : Enum<T>> JSONObject.enumOrNull(key: String): T? {
    val raw = strOrNull(key) ?: return null
    return try {
        enumValueOf<T>(raw)
    } catch (e: IllegalArgumentException) {
        null
    }
}

fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { i -> optJSONObject(i) }

package com.deuty.agent.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Atomic JSON file store.
 *
 * Why files and not Room (see docs/DECISIONS.md, ADR-011): the data here is one
 * active task, a bounded history, a handful of workflows and some preferences.
 * A JSON file with atomic replace gives us crash safety without adding KSP, an
 * annotation processor and a Kotlin-version coupling to the build - three things
 * that can break a build for reasons unrelated to the agent.
 *
 * Durability model: write to `<name>.tmp`, keep the previous file as `<name>.bak`,
 * then rename. A crash mid-write leaves either the old file or the new one, never
 * a half-written one. Read falls back to `.bak` if the primary is corrupt, which
 * is what lets the agent survive process death mid-task (requirement 6).
 *
 * All methods are blocking; callers are expected to be on Dispatchers.IO.
 */
class JsonFileStore(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    val root: File get() = dir

    private fun file(name: String): File = File(dir, name)
    private fun tmp(name: String): File = File(dir, "$name.tmp")
    private fun bak(name: String): File = File(dir, "$name.bak")

    fun readText(name: String): String? {
        val primary = file(name)
        val text = if (primary.exists()) runCatching { primary.readText() }.getOrNull() else null
        if (!text.isNullOrBlank()) return text
        val backup = bak(name)
        if (backup.exists()) return runCatching { backup.readText() }.getOrNull()
        return null
    }

    fun readObject(name: String): JSONObject? {
        val text = readText(name) ?: return null
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            // Corrupt primary: try the backup once, then give up quietly.
            val backup = bak(name)
            if (backup.exists()) {
                try {
                    JSONObject(backup.readText())
                } catch (e2: Exception) {
                    null
                }
            } else null
        }
    }

    fun readArray(name: String): JSONArray? {
        val text = readText(name) ?: return null
        return try {
            JSONArray(text)
        } catch (e: Exception) {
            val backup = bak(name)
            if (backup.exists()) try {
                JSONArray(backup.readText())
            } catch (e2: Exception) {
                null
            } else null
        }
    }

    fun writeText(name: String, text: String): Boolean = try {
        val target = file(name)
        val temp = tmp(name)
        temp.writeText(text)
        if (target.exists()) {
            val backup = bak(name)
            if (backup.exists()) backup.delete()
            target.renameTo(backup)
        }
        val ok = temp.renameTo(target)
        if (!ok) {
            // Rename failed (should not happen inside one dir): fall back to a copy.
            target.writeText(text)
            temp.delete()
        }
        true
    } catch (e: Exception) {
        false
    }

    fun writeObject(name: String, obj: JSONObject): Boolean = writeText(name, obj.toString())

    fun writeArray(name: String, arr: JSONArray): Boolean = writeText(name, arr.toString())

    fun exists(name: String): Boolean = file(name).exists()

    fun delete(name: String): Boolean {
        var ok = true
        listOf(file(name), tmp(name), bak(name)).forEach { if (it.exists()) ok = ok && it.delete() }
        return ok
    }

    fun deleteAll(): Int {
        val files = dir.listFiles() ?: return 0
        var count = 0
        files.forEach { if (it.isFile && it.delete()) count++ }
        return count
    }

    fun names(): List<String> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".bak") }
            .map { it.name }.sorted()

    fun totalBytes(): Long =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile }.sumOf { it.length() }
}

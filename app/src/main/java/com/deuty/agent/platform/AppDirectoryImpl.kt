package com.deuty.agent.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.deuty.agent.agent.port.AppDirectory
import com.deuty.agent.core.util.AgentLog
import com.deuty.agent.core.util.TextMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Label -> package resolution.
 *
 * "Open Files" is genuinely hard: there is no single Files app. Pixel ships
 * Files by Google, AOSP has DocumentsUI, Samsung has My Files, Xiaomi has
 * File Manager, OnePlus has its own. A hardcoded package list fails on most
 * devices, and a pure label match fails when the user says "my files" or the
 * launcher label is localised.
 *
 * So: enumerate what is actually installed and launchable (one PackageManager
 * query, cached), match loosely, and fall back to a per-name alias table of
 * candidate packages that is tried in order. Everything is filtered by what the
 * <queries> manifest block lets us see, which is exactly the launchable set.
 */
class AppDirectoryImpl(
    private val context: Context,
    private val log: AgentLog
) : AppDirectory {

    private val lock = Any()
    private var cache: Map<String, String> = emptyMap()
    private var cachedAtMs = 0L

    override suspend fun resolve(label: String): String? = withContext(Dispatchers.IO) {
        val wanted = TextMatch.normalize(label)
        if (wanted.isEmpty()) return@withContext null
        val apps = ensureCache()

        // 1. Exact (normalised) label.
        apps[wanted]?.let { return@withContext it }

        // 2. Known aliases, verified against what is really installed.
        ALIASES[wanted]?.let { candidates ->
            candidates.firstOrNull { pkg -> isLaunchable(pkg, apps) }?.let { return@withContext it }
        }

        // 3. Substring either way ("my files" vs "Files", "Chrome" vs "Chrome Beta").
        val contains = apps.entries.firstOrNull { (name, _) ->
            name.length >= 3 && (name.contains(wanted) || wanted.contains(name))
        }
        contains?.value?.let { return@withContext it }

        // 4. Fuzzy last resort.
        val fuzzy = apps.entries
            .map { (name, pkg) -> Triple(name, pkg, TextMatch.similarity(name, wanted)) }
            .filter { it.third >= 0.72f }
            .maxByOrNull { it.third }
        if (fuzzy != null) {
            log.i(TAG, "resolved \"$label\" to ${fuzzy.second} via fuzzy match on \"${fuzzy.first}\"")
            return@withContext fuzzy.second
        }

        // 5. Something that looks like a package name was typed directly.
        if (label.contains('.') && isLaunchable(label.trim(), apps)) return@withContext label.trim()

        log.w(TAG, "could not resolve app label \"$label\" among ${apps.size} launchable app(s)")
        null
    }

    override suspend fun launchableApps(): Map<String, String> = withContext(Dispatchers.IO) { ensureCache() }

    override suspend fun labelOf(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrNull() ?: ensureCache().entries.firstOrNull { it.value == packageName }?.key
        }
    }

    override fun refresh() {
        synchronized(lock) {
            cache = emptyMap()
            cachedAtMs = 0L
        }
    }

    private fun isLaunchable(pkg: String, apps: Map<String, String>): Boolean =
        apps.containsValue(pkg) || runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)

    private fun ensureCache(): Map<String, String> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (cache.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache
        }
        val built = buildCache()
        synchronized(lock) {
            cache = built
            cachedAtMs = System.currentTimeMillis()
        }
        log.d(TAG, "indexed ${built.size} launchable app(s)")
        return built
    }

    private fun buildCache(): Map<String, String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrElse { error ->
            log.e(TAG, "queryIntentActivities failed", error)
            return emptyMap()
        }
        val map = LinkedHashMap<String, String>()
        resolved.forEach { info ->
            val pkg = info.activityInfo?.packageName ?: return@forEach
            val rawLabel = (info.loadLabel(pm)?.toString() ?: pkg).trim()
            val key = TextMatch.normalize(rawLabel)
            if (key.isEmpty()) return@forEach
            // First wins: if two apps share a label, prefer the one the launcher
            // would show first (PackageManager order is roughly that).
            map.putIfAbsent(key, pkg)
            // Also index the package's own name so "open com.android.settings" works.
            map.putIfAbsent(TextMatch.normalize(pkg), pkg)
        }
        return map
    }

    companion object {
        private const val TAG = "apps"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L

        /**
         * Ordered candidate packages per common name. Only entries that are
         * actually installed are used; the list is a hint, never an assumption.
         */
        val ALIASES: Map<String, List<String>> = mapOf(
            "settings" to listOf("com.android.settings", "com.miui.securitycenter", "com.sec.android.app.samsungapps"),
            "files" to listOf(
                "com.google.android.documentsui",
                "com.android.documentsui",
                "com.miui.filemanager",
                "com.sec.android.app.myfiles",
                "com.oneplus.filemanager",
                "com.coloros.filemanager",
                "com.asus.filemanager"
            ),
            "file manager" to listOf(
                "com.google.android.documentsui",
                "com.miui.filemanager",
                "com.sec.android.app.myfiles"
            ),
            "my files" to listOf("com.sec.android.app.myfiles", "com.google.android.documentsui"),
            "downloads" to listOf("com.android.providers.downloads.ui", "com.android.documentsui"),
            "chrome" to listOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser"),
            "browser" to listOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.sec.android.app.sbrowser",
                "com.microsoft.emmx",
                "com.brave.browser"
            ),
            "camera" to listOf("com.google.android.GoogleCamera", "com.android.camera", "com.sec.android.app.camera"),
            "gallery" to listOf(
                "com.google.android.apps.photos",
                "com.android.gallery3d",
                "com.sec.android.gallery3d",
                "com.miui.gallery"
            ),
            "photos" to listOf("com.google.android.apps.photos", "com.miui.gallery", "com.sec.android.gallery3d"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage"),
            "calculator" to listOf("com.google.android.calculator", "com.android.calculator2", "com.miui.calculator"),
            "calendar" to listOf("com.google.android.calendar", "com.android.calendar", "com.samsung.android.calendar"),
            "play store" to listOf("com.android.vending"),
            "youtube" to listOf("com.google.android.youtube"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "contacts" to listOf("com.google.android.contacts", "com.android.contacts", "com.samsung.android.app.contacts"),
            "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "messages" to listOf(
                "com.google.android.apps.messaging",
                "com.android.mms",
                "com.samsung.android.messaging",
                "com.whatsapp"
            ),
            "whatsapp" to listOf("com.whatsapp")
        )
    }
}

/** Package visibility helper: true when a package can be seen at all. */
fun PackageManager.canSee(packageName: String): Boolean = try {
    getApplicationInfo(packageName, 0) != null
} catch (e: PackageManager.NameNotFoundException) {
    false
}

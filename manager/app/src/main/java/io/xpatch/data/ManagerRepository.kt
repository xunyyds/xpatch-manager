package io.xpatch.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import io.xpatch.model.AppInfo
import io.xpatch.model.ModuleInfo
import io.xpatch.patch.obfuscate.InjectLayout
import io.xpatch.patch.obfuscate.Logical
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

class ManagerRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val scope = ScopeStore(context)
    private val apiInspector = ModuleApiInspector(context)
    private val patchPrefs = context.getSharedPreferences(PATCH_PREFS, Context.MODE_PRIVATE)

    private val buildMutex = Mutex()
    private val generation = AtomicLong(0L)
    private val iconLock = Any()
    private val patchLock = Any()
    private val icons = HashMap<String, Drawable>()
    private val usedIcons = HashSet<String>()

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var patchCache: MutableMap<String, String>? = null

    data class PatchInfo(val hookCore: String, val runtimeVersion: Int)

    fun invalidate() {
        generation.incrementAndGet()
        cached = null
        patchCache = null
        apiInspector.invalidate()
    }

    suspend fun loadModules(): List<ModuleInfo> = withContext(Dispatchers.IO) {
        val current = snapshot()
        current.modules.map { module ->
            module.copy(enabledApps = scope.appsOf(module.packageName).count { it in current.patched })
        }
    }

    suspend fun loadApps(includeSystem: Boolean): List<AppInfo> = withContext(Dispatchers.IO) {
        val current = snapshot()
        current.apps.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { includeSystem || !it.isSystem }
            .map { scoped(it) }
            .toList()
    }

    suspend fun loadPatchedApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val current = snapshot()
        current.apps.asSequence()
            .filter { it.packageName in current.patched }
            .map { scoped(it) }
            .toList()
    }

    suspend fun patchedPackageNames(): List<String> = withContext(Dispatchers.IO) {
        snapshot().patched.toList()
    }

    suspend fun loadPatchableApps(includeSystem: Boolean = false): List<AppInfo> = withContext(Dispatchers.IO) {
        val current = snapshot()
        current.apps.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { includeSystem || !it.isSystem }
            .map { scoped(it) }
            .toList()
    }

    fun setModuleEnabled(modulePackage: String, enabled: Boolean) =
        scope.setModuleEnabled(modulePackage, enabled)

    fun isModuleEnabled(modulePackage: String) = scope.isModuleEnabled(modulePackage)

    fun appsOfModule(modulePackage: String) = scope.appsOf(modulePackage)

    fun setInScope(modulePackage: String, appPackage: String, inScope: Boolean) {
        scope.setInScope(modulePackage, appPackage, inScope)
        scope.invalidate()
    }

    fun isInScopeFor(modulePackage: String, appPackage: String): Boolean =
        appPackage in scope.appsOf(modulePackage)

    fun countApps(modulePackage: String) = scope.countApps(modulePackage)

    fun countModules(appPackage: String) = scope.countModules(appPackage)

    private data class Snapshot(
        val apps: List<AppInfo>,
        val modules: List<ModuleInfo>,
        val patched: Set<String>,
    )

    private data class ApkStamp(val path: String, val length: Long, val lastModified: Long)

    private fun scoped(app: AppInfo): AppInfo =
        app.copy(enabledModules = scope.countModules(app.packageName))

    private suspend fun snapshot(): Snapshot {
        cached?.let { return it }
        return buildMutex.withLock {
            cached?.let { return@withLock it }
            val before = generation.get()
            val built = buildSnapshot(before)
            if (built.apps.isNotEmpty() && generation.get() == before) cached = built
            built
        }
    }

    private suspend fun buildSnapshot(startGeneration: Long): Snapshot {
        val packages = installedPackages()
        val patches = detectPatches(packages, startGeneration)
        synchronized(iconLock) { usedIcons.clear() }
        val apps = packages
            .mapParallel(APP_PARALLEL) { info -> toAppInfo(info, patches[info.packageName]) }
            .filterNotNull()
            .sortedBy { it.label.lowercase() }
        val labels = HashMap<String, String>(apps.size)
        apps.forEach { labels[it.packageName] = it.label }
        val moduleCandidates = packages.filter { info ->
            runCatching {
                info.applicationInfo?.metaData?.getBoolean(META_MODULE) == true
            }.getOrDefault(false)
        }
        val modules = moduleCandidates
            .mapParallel(APP_PARALLEL) { info -> toModule(info, labels) }
            .filterNotNull()
            .sortedBy { it.label.lowercase() }
        pruneIcons()
        return Snapshot(apps, modules, patches.keys.toSet())
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> = runCatching {
        pm.getInstalledPackages(PackageManager.GET_META_DATA or PackageManager.GET_SIGNATURES)
    }.getOrDefault(emptyList())

    private suspend fun detectPatches(
        packages: List<PackageInfo>,
        startGeneration: Long,
    ): Map<String, PatchInfo> {
        val entries = patchLookup()
        val stamps = HashMap<String, ApkStamp>(packages.size)
        val pending = ArrayList<PackageInfo>()
        packages.forEach { info ->
            val stamp = apkStamp(info) ?: return@forEach
            stamps[info.packageName] = stamp
            if (entries[stampKey(stamp)] == null) pending += info
        }
        var dirty = false
        if (pending.isNotEmpty()) {
            val probed = pending.mapParallel(PATCH_PARALLEL) { info ->
                val stamp = stamps.getValue(info.packageName)
                stampKey(stamp) to probePatch(stamp.path)
            }
            probed.forEach { (key, value) ->
                if (value != null) {
                    entries[key] = value
                    dirty = true
                }
            }
        }
        val live = stamps.values.map { stampKey(it) }.toSet()
        val stale = entries.keys.filter { it !in live }
        if (stale.isNotEmpty()) {
            stale.forEach { entries.remove(it) }
            dirty = true
        }
        if (dirty && generation.get() == startGeneration) persistPatchEntries(entries)
        val patched = HashMap<String, PatchInfo>(stamps.size)
        packages.forEach { info ->
            val stamp = stamps[info.packageName] ?: return@forEach
            val patch = entries[stampKey(stamp)]?.let { decodePatch(it) } ?: return@forEach
            patched[info.packageName] = patch
        }
        return patched
    }

    private fun patchLookup(): MutableMap<String, String> {
        patchCache?.let { return it }
        synchronized(patchLock) {
            patchCache?.let { return it }
            val loaded = ConcurrentHashMap<String, String>()
            runCatching {
                patchPrefs.all.forEach { (key, value) ->
                    (value as? String)?.let { loaded[key] = it }
                }
            }
            patchCache = loaded
            return loaded
        }
    }

    private fun apkStamp(info: PackageInfo): ApkStamp? {
        val path = info.applicationInfo?.sourceDir ?: return null
        if (path.isEmpty()) return null
        return runCatching {
            val apk = File(path)
            ApkStamp(path, apk.length(), apk.lastModified())
        }.getOrNull()
    }

    private fun stampKey(stamp: ApkStamp) = "${stamp.path}|${stamp.length}|${stamp.lastModified}"

    private fun probePatch(apkPath: String): String? = runCatching {
        val apk = File(apkPath)
        if (!apk.canRead()) return@runCatching PATCH_NONE
        ZipFile(apk).use { zip ->
            if (zip.getEntry(CONFIG_ENTRY) != null) legacyPatch(zip) else layoutPatch(zip)
        }
    }.getOrNull()

    private fun legacyPatch(zip: ZipFile): String {
        val config = zip.getEntry(CONFIG_ENTRY) ?: return PATCH_NONE
        val text = zip.getInputStream(config).use { it.readBytes() }.toString(Charsets.UTF_8)
        val json = JSONObject(text)
        return encodePatch(hookCoreOf(json.optInt("hook_core", 0)), json.optInt("ver", 0))
    }

    private fun layoutPatch(zip: ZipFile): String {
        if (!hasLayoutMark(zip)) return PATCH_NONE
        val found = runCatching {
            val entries = ArrayList<Pair<String, Long>>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                entries += entry.name to entry.size
            }
            InjectLayout.discoverConfigEntry(entries) { name -> entryBytes(zip, name) }
        }.getOrNull() ?: return PATCH_NONE
        val text = entryBytes(zip, found)?.toString(Charsets.UTF_8) ?: return PATCH_NONE
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return PATCH_NONE
        val layout = InjectLayout.fromConfig(text)
        val hasCore = layout?.entry(Logical.CORE)?.let { zip.getEntry(it) } != null ||
            zip.getEntry(RUNTIME_ENTRY) != null
        if (!hasCore) return PATCH_NONE
        return encodePatch(hookCoreOf(json.optInt("hook_core", 0)), json.optInt("ver", 0))
    }

    private fun hasLayoutMark(zip: ZipFile): Boolean {
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory) continue
            if (!isClassesDex(entry.name)) continue
            if (entry.size <= 0L || entry.size > MAX_SCAN_DEX) continue
            val body = entryBytes(zip, entry.name) ?: continue
            if (containsAscii(body, LAYOUT_MARK)) return true
        }
        return false
    }

    private fun isClassesDex(name: String) =
        name.startsWith(CLASSES_PREFIX) && name.endsWith(DEX_SUFFIX) && name.indexOf('/') < 0

    private fun containsAscii(body: ByteArray, text: String): Boolean {
        val needle = text.toByteArray(Charsets.US_ASCII)
        if (needle.isEmpty() || body.size < needle.size) return false
        val last = body.size - needle.size
        var index = 0
        while (index <= last) {
            if (body[index] == needle[0]) {
                var offset = 1
                while (offset < needle.size && body[index + offset] == needle[offset]) offset++
                if (offset == needle.size) return true
            }
            index++
        }
        return false
    }

    private fun entryBytes(zip: ZipFile, name: String): ByteArray? = runCatching {
        val entry = zip.getEntry(name) ?: return@runCatching null
        zip.getInputStream(entry).use { it.readBytes() }
    }.getOrNull()

    private fun hookCoreOf(index: Int): String =
        HOOK_CORES[index.coerceIn(0, HOOK_CORES.size - 1)]

    private fun encodePatch(hookCore: String, runtimeVersion: Int) = "$PATCH_YES|$hookCore|$runtimeVersion"

    private fun decodePatch(raw: String): PatchInfo? {
        if (!raw.startsWith(PATCH_YES_WITH_SEP)) return null
        val parts = raw.split('|', limit = 3)
        if (parts.size < 3) return null
        return PatchInfo(parts[1], parts[2].toIntOrNull() ?: return null)
    }

    private fun persistPatchEntries(entries: Map<String, String>) {
        val editor = patchPrefs.edit().clear()
        entries.forEach { (key, value) -> editor.putString(key, value) }
        editor.apply()
    }

    private fun toAppInfo(info: PackageInfo, patch: PatchInfo?): AppInfo? {
        val applicationInfo = info.applicationInfo ?: return null
        return AppInfo(
            packageName = info.packageName,
            label = labelOf(applicationInfo),
            versionName = info.versionName.orEmpty(),
            isSystem = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            enabledModules = 0,
            icon = iconFor(applicationInfo, info.lastUpdateTime),
            apkPath = applicationInfo.sourceDir.orEmpty(),
            versionCode = versionCodeOf(info),
            hookCore = patch?.hookCore,
            runtimeVersion = patch?.runtimeVersion ?: 0,
            size = runCatching { File(applicationInfo.sourceDir).length() }.getOrDefault(0L),
        )
    }

    private fun toModule(info: PackageInfo, labels: Map<String, String>): ModuleInfo? {
        val applicationInfo = info.applicationInfo ?: return null
        val metaData = applicationInfo.metaData ?: return null
        if (!runCatching { metaData.getBoolean(META_MODULE) }.getOrDefault(false)) return null

        val description = runCatching { metaData.getString(META_DESCRIPTION) }.getOrNull().orEmpty()
        val minVersion = runCatching { metaData.getInt(META_MIN_VERSION) }.getOrDefault(0)
        val scopeResId = runCatching { metaData.getInt(META_SCOPE) }.getOrDefault(0)
        val recommended = recommendedApps(info.packageName, scopeResId)
        val apiInfo = apiInspector.inspect(applicationInfo.sourceDir, info.packageName)

        return ModuleInfo(
            packageName = info.packageName,
            label = labelOf(applicationInfo),
            versionName = info.versionName.orEmpty(),
            description = description,
            apiLevel = apiInfo?.minApiVersion?.takeIf { it > 0 } ?: minVersion,
            enabledApps = 0,
            icon = iconFor(applicationInfo, info.lastUpdateTime),
            recommendedApps = recommended,
            recommendedLabels = recommended.mapNotNull { pkg -> labels[pkg] },
            availableApiLevels = apiInfo?.availableLevels.orEmpty(),
            autoApiLevel = apiInfo?.resolvedLevel ?: ModuleApiInspector.API_100,
            modernModule = apiInfo?.modernModule ?: false,
            legacyModule = apiInfo?.legacyModule ?: false,
        )
    }

    private fun recommendedApps(packageName: String, resId: Int): Set<String> = runCatching {
        val res = pm.getResourcesForApplication(packageName)
        res.getStringArray(resId).filterNotNull().toSet()
    }.getOrDefault(emptySet())

    private fun iconFor(info: ApplicationInfo, lastUpdateTime: Long): Drawable? {
        val key = "${info.packageName}@$lastUpdateTime"
        synchronized(iconLock) {
            usedIcons.add(key)
            icons[key]?.let { return it }
        }
        val loaded = runCatching { pm.getApplicationIcon(info) }.getOrNull() ?: return null
        synchronized(iconLock) { icons[key] = loaded }
        return loaded
    }

    private fun pruneIcons() {
        synchronized(iconLock) {
            val keep = HashSet(usedIcons)
            icons.keys.retainAll(keep)
            usedIcons.clear()
        }
    }

    private fun labelOf(info: ApplicationInfo): String =
        runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()

    private suspend fun <T, R> List<T>.mapParallel(parallel: Int, block: (T) -> R): List<R> {
        val source = this
        val results = ArrayList<R>(source.size)
        coroutineScope {
            for (chunk in source.chunked(parallel)) {
                results += chunk.map { item -> async(Dispatchers.IO) { block(item) } }.awaitAll()
            }
        }
        return results
    }

    private companion object {
        const val META_MODULE = "xposedmodule"
        const val META_DESCRIPTION = "xposeddescription"
        const val META_MIN_VERSION = "xposedminversion"
        const val META_SCOPE = "xposedscope"
        const val RUNTIME_ENTRY = "xp/core.dex"
        const val CONFIG_ENTRY = "xp/config.json"
        const val LAYOUT_MARK = "res_core"
        const val CLASSES_PREFIX = "classes"
        const val DEX_SUFFIX = ".dex"
        const val MAX_SCAN_DEX = 2L * 1024 * 1024
        const val UNKNOWN_CORE = "未知"
        const val PATCH_PREFS = "patch_cache"
        const val PATCH_NONE = "0"
        const val PATCH_YES = "1"
        const val PATCH_YES_WITH_SEP = "$PATCH_YES|"
        const val APP_PARALLEL = 4
        const val PATCH_PARALLEL = 8
        val HOOK_CORES = listOf("LSPlant", "LSPlantNoInline", "Pine")
    }
}
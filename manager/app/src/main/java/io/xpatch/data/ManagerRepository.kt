package io.xpatch.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
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
    private val stateLock = Any()
    private val icons = HashMap<String, Drawable>()
    private val packageEntries = HashMap<String, PackageEntry>()
    private val replacingPackages = HashMap<String, Long>()

    @Volatile
    private var cached: Snapshot? = null

    @Volatile
    private var patchCache: MutableMap<String, String>? = null

    data class PatchInfo(val hookCore: String, val runtimeVersion: Int)

    data class RefreshResult(
        val apps: List<AppInfo>,
        val patchedApps: List<AppInfo>,
        val modules: List<ModuleInfo>,
        val packageScanComplete: Boolean,
        val removedModules: Set<String>,
        val affectedScopeApps: Set<String>,
    )

    fun invalidate() {
        synchronized(stateLock) { generation.incrementAndGet() }
        scope.invalidate()
    }

    fun onPackageChanged(packageName: String, replacing: Boolean = false) {
        synchronized(stateLock) {
            if (replacing) replacingPackages[packageName] = SystemClock.elapsedRealtime() + REPLACE_GUARD_MS
            else replacingPackages.remove(packageName)
            generation.incrementAndGet()
        }
    }

    fun onPackageRemoved(packageName: String, replacing: Boolean = false) {
        if (replacing) {
            onPackageChanged(packageName, true)
            return
        }
        onPackageChanged(packageName)
    }

    fun scopeSyncVersion(appPackage: String): String = scope.scopeSyncVersion(appPackage)

    fun requestScopeSync(apps: Collection<String>) = scope.requestScopeSync(apps)

    fun acknowledgeScopeSync(appPackage: String, version: String): Boolean =
        scope.acknowledgeScopeSync(appPackage, version)

    suspend fun refresh(force: Boolean = false): RefreshResult = withContext(Dispatchers.IO) {
        overview(snapshot(refresh = true, force = force))
    }

    suspend fun loadOverview(): RefreshResult = withContext(Dispatchers.IO) {
        overview(snapshot())
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
        val generation: Long,
        val scannedAt: Long,
        val packageScanComplete: Boolean,
        val removedModules: Set<String> = emptySet(),
    )

    private data class ApkStamp(val path: String, val length: Long, val lastModified: Long)

    private data class PackageStamp(
        val apk: ApkStamp,
        val versionCode: Long,
        val versionName: String?,
        val lastUpdateTime: Long,
        val flags: Int,
        val configuration: String,
    )

    private data class PackageEntry(
        val stamp: PackageStamp?,
        val app: AppInfo?,
        val module: ModuleInfo?,
        val complete: Boolean,
    )

    private data class PackageScan(val packages: List<PackageInfo>, val complete: Boolean)

    private data class PatchScan(val patches: Map<String, PatchInfo>, val entries: Map<String, String>)

    private data class SnapshotBuild(
        val snapshot: Snapshot,
        val entries: Map<String, PackageEntry>,
        val patches: Map<String, String>,
        val moduleStamps: Map<String, String>,
        val removedModules: Set<String>,
    )

    private fun scoped(app: AppInfo): AppInfo =
        app.copy(enabledModules = scope.countModules(app.packageName))

    private fun overview(current: Snapshot): RefreshResult {
        val apps = current.apps.map { scoped(it) }
        return RefreshResult(
            apps = apps.filter { it.packageName != context.packageName },
            patchedApps = apps.filter { it.packageName in current.patched },
            modules = current.modules.map { module ->
                module.copy(enabledApps = scope.appsOf(module.packageName).count { it in current.patched })
            },
            packageScanComplete = current.packageScanComplete,
            removedModules = current.removedModules,
            affectedScopeApps = scope.pendingScopeSyncApps(),
        )
    }

    private suspend fun snapshot(refresh: Boolean = false, force: Boolean = false): Snapshot {
        val observed = cached
        val now = SystemClock.elapsedRealtime()
        if (observed != null && observed.generation == generation.get() &&
            (!refresh || (!force && now - observed.scannedAt < REFRESH_INTERVAL_MS))
        ) return observed
        return buildMutex.withLock {
            val latest = cached
            if (latest != null && latest.generation == generation.get() &&
                (latest !== observed || (!force && (!refresh ||
                    SystemClock.elapsedRealtime() - latest.scannedAt < REFRESH_INTERVAL_MS)))
            ) return@withLock latest
            while (true) {
                val before = generation.get()
                val built = buildSnapshot(before)
                val accepted = synchronized(stateLock) {
                    if (before != generation.get()) false
                    else {
                        if (built.snapshot.packageScanComplete) {
                            scope.reconcileModules(built.moduleStamps, built.removedModules)
                        }
                        packageEntries.clear()
                        packageEntries.putAll(built.entries)
                        persistPatchEntries(built.patches)
                        cached = built.snapshot
                        pruneIcons(built.entries.values)
                        true
                    }
                }
                if (accepted) return@withLock built.snapshot
            }
            error("unreachable")
        }
    }

    private suspend fun buildSnapshot(startGeneration: Long): SnapshotBuild {
        val scan = installedPackages()
        val previous = cached
        if (scan.packages.isEmpty()) {
            val fallback = previous?.copy(
                generation = startGeneration,
                scannedAt = SystemClock.elapsedRealtime(),
                packageScanComplete = false,
                removedModules = emptySet(),
            ) ?: Snapshot(emptyList(), emptyList(), emptySet(), startGeneration, SystemClock.elapsedRealtime(), false)
            return SnapshotBuild(fallback, packageEntries.toMap(), patchLookup().toMap(), emptyMap(), emptySet())
        }
        val patches = detectPatches(scan.packages, scan.complete)
        val configuration = context.resources.configuration.toString()
        val entries = scan.packages.mapParallel(APP_PARALLEL) { info ->
            info.packageName to packageEntry(info, patches, configuration)
        }.toMap().toMutableMap()
        val present = scan.packages.mapTo(HashSet()) { it.packageName }
        val known = scope.knownModulePackages() + packageEntries.filterValues { it.module != null }.keys
        val candidates = known + scope.exportAll().keys + scope.enabledModules()
        val removed = if (scan.complete) candidates.filterTo(HashSet()) { module ->
            module !in present && !isReplacing(module) && packageMissing(module)
        } else emptySet()
        packageEntries.forEach { (packageName, entry) ->
            if (packageName !in entries && packageName !in removed &&
                (!scan.complete || isReplacing(packageName) || !packageMissing(packageName))
            ) entries[packageName] = entry
        }
        val apps = entries.values.mapNotNull { it.app }.sortedBy { it.label.lowercase() }
        val labels = apps.associate { it.packageName to it.label }
        val modules = entries.values.mapNotNull { it.module }.map { module ->
            module.copy(
                recommendedLabels = module.recommendedApps.mapNotNull { labels[it] },
                missingRecommended = module.recommendedApps.filter { it !in labels },
            )
        }.sortedBy { it.label.lowercase() }
        val patched = apps.filter { it.hookCore != null }.mapTo(HashSet()) { it.packageName }
        val moduleStamps = entries.filter { (name, entry) ->
            name in present && entry.complete && entry.module != null
        }.mapValues { (_, entry) ->
            val stamp = entry.stamp!!
            "${stampKey(stamp.apk)}|${stamp.versionCode}|${stamp.lastUpdateTime}"
        }
        return SnapshotBuild(
            Snapshot(apps, modules, patched, startGeneration, SystemClock.elapsedRealtime(), scan.complete, removed),
            entries,
            patches.entries,
            moduleStamps,
            removed,
        )
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): PackageScan = runCatching {
        val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
        val complete = packages.isNotEmpty() && packages.any { it.packageName == context.packageName } &&
            packages.all { it.packageName.isNotBlank() && it.applicationInfo != null } &&
            packages.map { it.packageName }.toSet().size == packages.size
        PackageScan(packages, complete)
    }.getOrElse { PackageScan(emptyList(), false) }

    private fun isReplacing(packageName: String): Boolean = synchronized(stateLock) {
        val until = replacingPackages[packageName] ?: return@synchronized false
        if (until > SystemClock.elapsedRealtime()) true
        else {
            replacingPackages.remove(packageName)
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun packageMissing(packageName: String): Boolean {
        return try {
            pm.getApplicationInfo(packageName, 0)
            false
        } catch (_: PackageManager.NameNotFoundException) {
            try {
                val info = pm.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                (info.flags and ApplicationInfo.FLAG_INSTALLED) == 0
            } catch (_: PackageManager.NameNotFoundException) {
                true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun packageEntry(info: PackageInfo, patches: PatchScan, configuration: String): PackageEntry {
        val previous = packageEntries[info.packageName]
        val applicationInfo = info.applicationInfo ?: return previous ?: PackageEntry(null, null, null, false)
        val patch = patches.patches[info.packageName]
        val apk = apkStamp(info) ?: return previous ?: PackageEntry(null, toAppInfo(info, patch), null, false)
        val stamp = PackageStamp(
            apk, versionCodeOf(info), info.versionName, info.lastUpdateTime, applicationInfo.flags, configuration,
        )
        if (previous?.stamp == stamp && previous.complete) return previous
        val patchKnown = stampKey(apk) in patches.entries
        val previousPatch = previous?.app?.let { app -> app.hookCore?.let { PatchInfo(it, app.runtimeVersion) } }
        val app = toAppInfo(info, if (patchKnown) patch else previousPatch)
        val apiInfo = apiInspector.inspect(apk.path, info.packageName)
        val declared = runCatching { applicationInfo.metaData?.getBoolean(META_MODULE) == true }.getOrDefault(false)
        val module = if (apiInfo == null) previous?.module ?: if (declared) toModule(info, null) else null
        else if (declared || apiInfo.isModule) toModule(info, apiInfo) else null
        return PackageEntry(stamp, app, module, apiInfo != null && patchKnown)
    }

    private suspend fun detectPatches(
        packages: List<PackageInfo>,
        complete: Boolean,
    ): PatchScan {
        val entries = patchLookup().toMutableMap()
        val stamps = HashMap<String, ApkStamp>(packages.size)
        val pending = ArrayList<PackageInfo>()
        packages.forEach { info ->
            val stamp = apkStamp(info) ?: return@forEach
            stamps[info.packageName] = stamp
            if (entries[stampKey(stamp)] == null) pending += info
        }
        if (pending.isNotEmpty()) {
            val probed = pending.mapParallel(PATCH_PARALLEL) { info ->
                val stamp = stamps.getValue(info.packageName)
                stampKey(stamp) to probePatch(stamp.path)
            }
            probed.forEach { (key, value) ->
                if (value != null) entries[key] = value
            }
        }
        if (complete) {
            val keep = stamps.values.mapTo(HashSet()) { stampKey(it) }
            packageEntries.forEach { (packageName, entry) ->
                if (packageName !in stamps) entry.stamp?.apk?.let { keep.add(stampKey(it)) }
            }
            entries.keys.retainAll(keep)
        }
        val patched = HashMap<String, PatchInfo>(stamps.size)
        packages.forEach { info ->
            val stamp = stamps[info.packageName] ?: return@forEach
            val patch = entries[stampKey(stamp)]?.let { decodePatch(it) } ?: return@forEach
            patched[info.packageName] = patch
        }
        return PatchScan(patched, entries)
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
            if (!apk.isFile || !apk.canRead() || apk.length() <= 0L) return@runCatching null
            ApkStamp(path, apk.length(), apk.lastModified())
        }.getOrNull()
    }

    private fun stampKey(stamp: ApkStamp) = "${stamp.path}|${stamp.length}|${stamp.lastModified}"

    private fun probePatch(apkPath: String): String? = runCatching {
        val apk = File(apkPath)
        if (!apk.canRead()) return@runCatching null
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
        synchronized(patchLock) {
            if (patchCache == entries) return
            val editor = patchPrefs.edit().clear()
            entries.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
            patchCache = ConcurrentHashMap(entries)
        }
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

    private fun toModule(info: PackageInfo, apiInfo: ModuleApiInspector.Info?): ModuleInfo? {
        val applicationInfo = info.applicationInfo ?: return null
        val metaData = applicationInfo.metaData
        val declaredModule = runCatching {
            metaData?.getBoolean(META_MODULE) == true
        }.getOrDefault(false)
        if (!declaredModule && apiInfo?.isModule != true) return null

        val description = runCatching { metaData?.getString(META_DESCRIPTION) }.getOrNull().orEmpty()
        val minVersion = runCatching { metaData?.getInt(META_MIN_VERSION) ?: 0 }.getOrDefault(0)
        val scopeResId = runCatching { metaData?.getInt(META_SCOPE) ?: 0 }.getOrDefault(0)
        val recommended = recommendedApps(info.packageName, scopeResId)
            .ifEmpty { staticScopeApps(applicationInfo.sourceDir) }

        return ModuleInfo(
            packageName = info.packageName,
            label = labelOf(applicationInfo),
            versionName = info.versionName.orEmpty(),
            description = description,
            apiLevel = apiInfo?.minApiVersion?.takeIf { it > 0 } ?: minVersion,
            enabledApps = 0,
            icon = iconFor(applicationInfo, info.lastUpdateTime),
            recommendedApps = recommended,
            availableApiLevels = apiInfo?.availableLevels.orEmpty(),
            autoApiLevel = apiInfo?.resolvedLevel ?: ModuleApiInspector.API_100,
            modernModule = apiInfo?.modernModule ?: false,
            legacyModule = apiInfo?.legacyModule ?: false,
        )
    }

    private fun recommendedApps(packageName: String, resId: Int): Set<String> = runCatching {
        if (resId == 0) return@runCatching emptySet()
        val res = pm.getResourcesForApplication(packageName)
        LinkedHashSet(res.getStringArray(resId).filterNotNull())
    }.getOrDefault(emptySet())

    private fun staticScopeApps(apkPath: String): Set<String> = runCatching {
        java.util.zip.ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(MODERN_SCOPE_LIST) ?: return@use emptySet()
            zip.getInputStream(entry).bufferedReader().useLines { lines ->
                LinkedHashSet(
                    lines.map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList(),
                )
            }
        }
    }.getOrDefault(emptySet())

    private fun iconFor(info: ApplicationInfo, lastUpdateTime: Long): Drawable? {
        val key = "${info.packageName}@$lastUpdateTime|${info.sourceDir}|${context.resources.configuration}"
        synchronized(iconLock) { icons[key]?.let { return it } }
        val loaded = runCatching { pm.getApplicationIcon(info) }.getOrNull() ?: return null
        synchronized(iconLock) { icons[key] = loaded }
        return loaded
    }

    private fun pruneIcons(entries: Collection<PackageEntry>) {
        synchronized(iconLock) {
            val keep = entries.mapNotNullTo(HashSet()) { entry ->
                val app = entry.app ?: return@mapNotNullTo null
                val stamp = entry.stamp ?: return@mapNotNullTo null
                "${app.packageName}@${stamp.lastUpdateTime}|${stamp.apk.path}|${stamp.configuration}"
            }
            icons.keys.retainAll(keep)
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
        const val MODERN_SCOPE_LIST = "META-INF/xposed/scope.list"
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
        const val REFRESH_INTERVAL_MS = 2000L
        const val REPLACE_GUARD_MS = 120000L
        const val APP_PARALLEL = 4
        const val PATCH_PARALLEL = 8
        val HOOK_CORES = listOf("LSPlant", "LSPlantNoInline", "Pine")
    }
}
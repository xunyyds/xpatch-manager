package io.xpatch.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.zip.ZipFile

class ModuleApiInspector(private val context: Context) {

    private val lock = Any()
    private val cache = HashMap<String, Info>()

    data class Info(
        val availableLevels: List<Int>,
        val resolvedLevel: Int,
        val modernModule: Boolean,
        val legacyModule: Boolean,
        val minApiVersion: Int,
        val targetApiVersion: Int,
        val legacyApiLevel: Int,
    )

    fun inspect(modulePackage: String): Info? = runCatching {
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(modulePackage, 0)
        }.getOrNull() ?: return@runCatching null
        inspect(info.sourceDir, modulePackage)
    }.getOrNull()

    fun inspect(apkPath: String, modulePackage: String?): Info? = runCatching {
        cacheKey(apkPath, modulePackage)?.let { key ->
            synchronized(lock) { cache[key] }?.let { return@runCatching it }
        }

        var modernModule = false
        var legacyModule = false
        var minApiVersion = 0
        var targetApiVersion = 0

        ZipFile(apkPath).use { zip ->
            val propEntry = zip.getEntry(MODULE_PROP)
            if (propEntry != null) {
                modernModule = true
                val props = java.util.Properties()
                zip.getInputStream(propEntry).use { props.load(it) }
                minApiVersion = parseInt(props.getProperty(KEY_MIN_API))
                targetApiVersion = parseInt(props.getProperty(KEY_TARGET_API))
            }
            legacyModule = zip.getEntry(LEGACY_INIT) != null
        }

        var legacyApiLevel = installedLegacyApiLevel(modulePackage)
        if (legacyApiLevel <= 0) legacyApiLevel = archiveLegacyApiLevel(apkPath)

        val levels = buildLevels(legacyApiLevel, minApiVersion, targetApiVersion, modernModule, legacyModule)
        val info = Info(
            availableLevels = levels,
            resolvedLevel = levels.filter { it in WRAPPER_LEVELS }.lastOrNull() ?: API_100,
            modernModule = modernModule,
            legacyModule = legacyModule,
            minApiVersion = minApiVersion,
            targetApiVersion = targetApiVersion,
            legacyApiLevel = legacyApiLevel,
        )
        cacheKey(apkPath, modulePackage)?.let { key ->
            synchronized(lock) { cache[key] = info }
        }
        info
    }.getOrNull()

    fun invalidate() {
        synchronized(lock) { cache.clear() }
    }

    private fun cacheKey(apkPath: String, modulePackage: String?): String? = runCatching {
        val apk = java.io.File(apkPath)
        "${modulePackage.orEmpty()}|${apkPath}|${apk.length()}|${apk.lastModified()}"
    }.getOrNull()

    fun availableLevels(modulePackage: String): List<Int> =
        inspect(modulePackage)?.availableLevels ?: LEVELS_FALLBACK

    fun resolvedLevel(modulePackage: String): Int =
        inspect(modulePackage)?.resolvedLevel ?: API_100

    private fun buildLevels(
        legacyApiLevel: Int,
        minApiVersion: Int,
        targetApiVersion: Int,
        modernModule: Boolean,
        legacyModule: Boolean,
    ): List<Int> {
        val pool = LinkedHashSet<Int>()
        if (legacyApiLevel > 0) {
            if (legacyApiLevel in WRAPPER_LEVELS) pool.add(legacyApiLevel)
            else pool.add(API_89)
        } else if (legacyModule) {
            pool.add(API_89)
        }

        val modernLow = when {
            minApiVersion >= API_100 -> minApiVersion
            targetApiVersion >= API_100 -> API_100
            else -> 0
        }
        val modernHigh = when {
            targetApiVersion in API_100..API_102 -> targetApiVersion
            targetApiVersion > API_102 -> API_102
            else -> API_100
        }
        if (modernHigh >= API_100 && (modernModule || modernLow >= API_100)) {
            var level = if (modernLow in API_100..modernHigh) modernLow else API_100
            while (level <= modernHigh) {
                if (level in WRAPPER_LEVELS) pool.add(level)
                level++
            }
        }

        return pool.toList().sorted()
    }

    private fun apkPathOf(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val info = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: return null
        return info.sourceDir
    }

    private fun installedLegacyApiLevel(packageName: String?): Int {
        if (packageName.isNullOrBlank()) return 0
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            legacyApiLevelOf(info)
        }.getOrDefault(0)
    }

    private fun archiveLegacyApiLevel(apkPath: String): Int = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA)
            ?.applicationInfo ?: return@runCatching 0
        info.sourceDir = apkPath
        info.publicSourceDir = apkPath
        legacyApiLevelOf(info)
    }.getOrDefault(0)

    private fun legacyApiLevelOf(info: android.content.pm.ApplicationInfo): Int =
        runCatching { info.metaData?.getInt(META_MIN_VERSION) ?: 0 }.getOrDefault(0)

    private fun parseInt(value: String?): Int =
        value?.trim()?.toIntOrNull() ?: 0

    companion object {
        const val API_89 = 89
        const val API_100 = 100
        const val API_101 = 101
        const val API_102 = 102

        val LEVELS_FALLBACK = listOf(API_89, API_100, API_101, API_102)

        val WRAPPER_LEVELS = setOf(API_89, API_100, API_101, API_102)

        private const val MODULE_PROP = "META-INF/xposed/module.prop"
        private const val LEGACY_INIT = "assets/xposed_init"
        private const val KEY_MIN_API = "minApiVersion"
        private const val KEY_TARGET_API = "targetApiVersion"
        private const val META_MIN_VERSION = "xposedminversion"

        fun label(level: Int): String = wrapperLabel(level)

        fun wrapperLabel(level: Int): String = when {
            level < API_100 -> "API 89"
            level == API_100 -> "API 100"
            level == API_101 -> "API 101"
            else -> "API 102"
        }

        fun wrapperOf(level: Int): String = when {
            level < API_100 -> "xp89"
            level == API_100 -> "xp100"
            level == API_101 -> "xp101"
            else -> "xp102"
        }
    }
}

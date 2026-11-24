package io.xpatch.data

import android.content.Context
import io.xpatch.patch.PatchLog

enum class HookCore(val label: String, val index: Int) {
    XPLANT("LSPlant", 0),
    NOINLINE("LSPlant NoInline", 1),
    PINE("Pine", 2),
}

enum class UiStyle(val index: Int, val label: String) {
    MIUIX(1, "MiuiX"),
    MATERIAL3(0, "Material"),
    ;

    companion object {
        fun of(index: Int): UiStyle = entries.firstOrNull { it.index == index } ?: MATERIAL3
    }
}

enum class ThemeColor(val key: String, val label: String) {
    GREEN("green", "默认"),
    TEAL("teal", "青色"),
    BLUE("blue", "蓝色"),
    INDIGO("indigo", "靛蓝色"),
    VIOLET("violet", "紫罗兰色"),
    PINK("pink", "粉红色"),
    YELLOW("yellow", "黄色"),
    ORANGE("orange", "橙色"),
    DEEP_ORANGE("deep_orange", "深橙色"),
    ;

    companion object {
        fun of(key: String): ThemeColor = entries.firstOrNull { it.key == key } ?: GREEN
    }
}

enum class LiquidGlassLevel(val index: Int, val label: String) {
    STANDARD(0, "标准"),
    ENHANCED(1, "增强"),
    HIGH_TRANSPARENCY(2, "高透"),
    ;

    companion object {
        fun of(index: Int): LiquidGlassLevel =
            entries.firstOrNull { it.index == index } ?: ENHANCED
    }
}

enum class SoftwareSource(val key: String, val label: String) {
    AUTO("auto", "自动"),
    WANDOUJIA("wandoujia", "豌豆荚"),
    APPCHINA("appchina", "应用汇"),
    ;

    companion object {
        fun of(key: String): SoftwareSource = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

data class ManagerSettings(
    val themeMode: Int,
    val themeColor: ThemeColor = ThemeColor.GREEN,
    val dynamicColor: Boolean = true,
    val oledDark: Boolean = false,
    val signatureBypassLevel: Int,
    val hookCore: HookCore,
    val showSystemApps: Boolean,
    val injectSignature: Boolean,
    val fileProvider: Boolean = false,
    val bottomBarStyle: Int = SettingsStore.BOTTOM_BAR_STANDARD,
    val liquidGlassLevel: LiquidGlassLevel = LiquidGlassLevel.ENHANCED,
    val uiStyle: UiStyle = UiStyle.MATERIAL3,
    val exportTreeUri: String = "",
    val exportTreeLabel: String = "",
    val patchHistoryLimit: Int = SettingsStore.HISTORY_LIMIT_DEFAULT,
    val deleteArtifactOnRemove: Boolean = true,
    val skipArtifactDeletePrompt: Boolean = false,
    val onboardingDone: Boolean = false,
    val disclaimerAcceptedAt: Long = 0,
    val storageDeniedOnce: Boolean = false,
    val lastUpdateCheckAt: Long = 0,
    val updateCheckCache: String = "",
    val updateGoodSource: String = "",
    val receiveBeta: Boolean = false,
    val forceOverwrite: Boolean = false,
    val sigMode: String = SettingsStore.SIG_MODE_DEFAULT,
    val injectObfuscate: Boolean = false,
    val injectKeepCrashPermission: Boolean = false,
    val patchLogLevel: Int = SettingsStore.LOG_LEVEL_DEFAULT,
    val softwareSource: SoftwareSource = SoftwareSource.AUTO,
    val installUsers: Set<Int> = emptySet(),
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ManagerSettings = ManagerSettings(
        themeMode = prefs.getInt(KEY_THEME, THEME_SYSTEM),
        themeColor = ThemeColor.of(prefs.getString(KEY_THEME_COLOR, "").orEmpty()),
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
        oledDark = prefs.getBoolean(KEY_OLED_DARK, false),
        signatureBypassLevel = prefs.getInt(KEY_BYPASS_LEVEL, 3),
        hookCore = HookCore.entries.firstOrNull { it.index == prefs.getInt(KEY_HOOK_CORE, 0) }
            ?: HookCore.XPLANT,
        showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM, false),
        injectSignature = prefs.getBoolean(KEY_INJECT_SIGNATURE, true),
        fileProvider = prefs.getBoolean(KEY_FILE_PROVIDER, false),
        bottomBarStyle = prefs.getInt(KEY_BOTTOM_BAR, SettingsStore.BOTTOM_BAR_STANDARD),
        liquidGlassLevel = LiquidGlassLevel.of(
            prefs.getInt(KEY_LIQUID_GLASS, SettingsStore.LIQUID_GLASS_ENHANCED),
        ),
        uiStyle = UiStyle.of(prefs.getInt(KEY_UI_STYLE, UiStyle.MATERIAL3.index)),
        exportTreeUri = prefs.getString(KEY_EXPORT_TREE, "").orEmpty(),
        exportTreeLabel = prefs.getString(KEY_EXPORT_LABEL, "").orEmpty(),
        patchHistoryLimit = prefs.getInt(KEY_HISTORY_LIMIT, HISTORY_LIMIT_DEFAULT)
            .coerceIn(HISTORY_LIMIT_MIN, HISTORY_LIMIT_MAX),
        deleteArtifactOnRemove = prefs.getBoolean(KEY_DELETE_ARTIFACT, true),
        skipArtifactDeletePrompt = prefs.getBoolean(KEY_SKIP_DELETE_PROMPT, false),
        onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, false),
        disclaimerAcceptedAt = prefs.getLong(KEY_DISCLAIMER_AT, 0),
        storageDeniedOnce = prefs.getBoolean(KEY_STORAGE_DENIED, false),
        lastUpdateCheckAt = prefs.getLong(KEY_UPDATE_CHECK_AT, 0),
        updateCheckCache = prefs.getString(KEY_UPDATE_CACHE, "").orEmpty(),
        updateGoodSource = prefs.getString(KEY_UPDATE_SOURCE, "").orEmpty(),
        receiveBeta = prefs.getBoolean(KEY_RECEIVE_BETA, false),
        forceOverwrite = prefs.getBoolean(KEY_FORCE_OVERWRITE, false),
        sigMode = normalizedSigMode(prefs.getString(KEY_SIG_MODE, "").orEmpty()),
        injectObfuscate = prefs.getBoolean(KEY_INJECT_OBFUSCATE, false),
        injectKeepCrashPermission = prefs.getBoolean(KEY_INJECT_KEEP_CRASH_PERMISSION, false),
        patchLogLevel = PatchLog.normalize(prefs.getInt(KEY_LOG_LEVEL, LOG_LEVEL_DEFAULT)),
        softwareSource = SoftwareSource.of(prefs.getString(KEY_SOFTWARE_SOURCE, "").orEmpty()),
        installUsers = parseUsers(prefs.getString(KEY_INSTALL_USERS, "").orEmpty()),
    )

    fun setThemeMode(mode: Int) = prefs.edit().putInt(KEY_THEME, mode).apply()

    fun setThemeColor(color: ThemeColor) =
        prefs.edit().putString(KEY_THEME_COLOR, color.key).apply()

    fun setDynamicColor(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()

    fun setOledDark(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_OLED_DARK, enabled).apply()

    fun setSignatureBypassLevel(level: Int) =
        prefs.edit().putInt(KEY_BYPASS_LEVEL, level).apply()

    fun setHookCore(core: HookCore) = prefs.edit().putInt(KEY_HOOK_CORE, core.index).apply()

    fun setShowSystemApps(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_SYSTEM, show).apply()

    fun setInjectSignature(inject: Boolean) =
        prefs.edit().putBoolean(KEY_INJECT_SIGNATURE, inject).apply()

    fun setFileProvider(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FILE_PROVIDER, enabled).apply()

    fun setBottomBarStyle(style: Int) =
        prefs.edit().putInt(KEY_BOTTOM_BAR, style).apply()

    fun setLiquidGlassLevel(level: LiquidGlassLevel) =
        prefs.edit().putInt(KEY_LIQUID_GLASS, level.index).apply()

    fun setUiStyle(style: UiStyle) =
        prefs.edit().putInt(KEY_UI_STYLE, style.index).apply()

    fun setExportTree(uri: String, label: String) =
        prefs.edit().putString(KEY_EXPORT_TREE, uri).putString(KEY_EXPORT_LABEL, label).apply()

    fun clearExportTree() =
        prefs.edit().remove(KEY_EXPORT_TREE).remove(KEY_EXPORT_LABEL).apply()

    fun setPatchHistoryLimit(limit: Int) =
        prefs.edit().putInt(KEY_HISTORY_LIMIT, limit.coerceIn(HISTORY_LIMIT_MIN, HISTORY_LIMIT_MAX)).apply()

    fun setDeleteArtifactOnRemove(delete: Boolean) =
        prefs.edit().putBoolean(KEY_DELETE_ARTIFACT, delete).apply()

    fun setSkipArtifactDeletePrompt(skip: Boolean) =
        prefs.edit().putBoolean(KEY_SKIP_DELETE_PROMPT, skip).apply()

    fun setOnboardingDone(done: Boolean) =
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()

    fun setDisclaimerAcceptedAt(at: Long) =
        prefs.edit().putLong(KEY_DISCLAIMER_AT, at).apply()

    fun setStorageDeniedOnce(denied: Boolean) =
        prefs.edit().putBoolean(KEY_STORAGE_DENIED, denied).apply()

    fun setUpdateCheckCache(at: Long, json: String) =
        prefs.edit().putLong(KEY_UPDATE_CHECK_AT, at).putString(KEY_UPDATE_CACHE, json).apply()

    fun setUpdateGoodSource(prefix: String) =
        prefs.edit().putString(KEY_UPDATE_SOURCE, prefix).apply()

    fun setReceiveBeta(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_RECEIVE_BETA, enabled).apply()

    fun setForceOverwrite(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FORCE_OVERWRITE, enabled).apply()

    fun setSigMode(mode: String) =
        prefs.edit().putString(KEY_SIG_MODE, normalizedSigMode(mode)).apply()

    fun setInjectObfuscate(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_INJECT_OBFUSCATE, enabled).apply()

    fun setInjectKeepCrashPermission(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_INJECT_KEEP_CRASH_PERMISSION, enabled).apply()

    fun setPatchLogLevel(level: Int) =
        prefs.edit().putInt(KEY_LOG_LEVEL, PatchLog.normalize(level)).apply()

    fun setSoftwareSource(source: SoftwareSource) =
        prefs.edit().putString(KEY_SOFTWARE_SOURCE, source.key).apply()

    fun setInstallUsers(users: Set<Int>) =
        prefs.edit().putString(KEY_INSTALL_USERS, users.sorted().joinToString(",")).apply()

    private fun parseUsers(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.toSortedSet()
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        const val HISTORY_LIMIT_DEFAULT = 50
        const val HISTORY_LIMIT_MIN = 1
        const val HISTORY_LIMIT_MAX = 500

        const val SIG_MODE_OFF = "off"
        const val SIG_MODE_JAVA = "java"
        const val SIG_MODE_JAVA_FILE = "java_file"
        const val SIG_MODE_DEEP = "deep"
        const val SIG_MODE_DEFAULT = SIG_MODE_JAVA

        val SIG_MODE_OPTIONS = listOf(
            SIG_MODE_OFF to "关闭",
            SIG_MODE_JAVA to "标准",
            SIG_MODE_JAVA_FILE to "增强",
            SIG_MODE_DEEP to "极致",
        )

        val SIG_MODES: List<String> = SIG_MODE_OPTIONS.map { it.first }

        fun sigModeLabel(mode: String): String =
            SIG_MODE_OPTIONS.firstOrNull { it.first == normalizedSigMode(mode) }?.second
                ?: SIG_MODE_OPTIONS.first { it.first == SIG_MODE_DEFAULT }.second

        private fun normalizedSigMode(raw: String): String {
            val value = raw.trim().lowercase()
            return if (value in SIG_MODES) value else SIG_MODE_DEFAULT
        }

        private const val KEY_THEME = "theme_mode"
        private const val KEY_THEME_COLOR = "theme_color"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_OLED_DARK = "oled_dark"
        private const val KEY_BYPASS_LEVEL = "bypass_level"
        private const val KEY_HOOK_CORE = "hook_core"
        private const val KEY_SHOW_SYSTEM = "show_system"
        private const val KEY_INJECT_SIGNATURE = "inject_signature"
        private const val KEY_FILE_PROVIDER = "file_provider"
        private const val KEY_BOTTOM_BAR = "bottom_bar_style"
        private const val KEY_LIQUID_GLASS = "liquid_glass_level"
        private const val KEY_UI_STYLE = "ui_style"
        private const val KEY_EXPORT_TREE = "export_tree_uri"
        private const val KEY_EXPORT_LABEL = "export_tree_label"
        private const val KEY_HISTORY_LIMIT = "patch_history_limit"
        private const val KEY_DELETE_ARTIFACT = "patch_history_delete_artifact"
        private const val KEY_SKIP_DELETE_PROMPT = "patch_history_skip_delete_prompt"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_DISCLAIMER_AT = "disclaimer_accepted_at"
        private const val KEY_STORAGE_DENIED = "storage_denied_once"
        private const val KEY_UPDATE_CHECK_AT = "update_check_at"
        private const val KEY_UPDATE_CACHE = "update_check_cache"
        private const val KEY_UPDATE_SOURCE = "update_good_source"
        private const val KEY_RECEIVE_BETA = "receive_beta"
        private const val KEY_FORCE_OVERWRITE = "force_overwrite"
        private const val KEY_SIG_MODE = "sig_mode"

        private const val KEY_INJECT_OBFUSCATE = "inject_obfuscate"
        private const val KEY_INJECT_KEEP_CRASH_PERMISSION = "inject_keep_crash_permission"
        private const val KEY_LOG_LEVEL = "patch_log_level"
        private const val KEY_SOFTWARE_SOURCE = "software_source"
        private const val KEY_INSTALL_USERS = "install_users"
        const val LOG_LEVEL_DEFAULT = PatchLog.LEVEL_NORMAL
        const val BOTTOM_BAR_STANDARD = 0
        const val BOTTOM_BAR_LIQUID = 1
        const val LIQUID_GLASS_STANDARD = 0
        const val LIQUID_GLASS_ENHANCED = 1
        const val LIQUID_GLASS_HIGH_TRANSPARENCY = 2
    }
}

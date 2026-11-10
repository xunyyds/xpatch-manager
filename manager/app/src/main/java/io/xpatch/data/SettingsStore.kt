package io.xpatch.data

import android.content.Context

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

data class ManagerSettings(
    val themeMode: Int,
    val signatureBypassLevel: Int,
    val hookCore: HookCore,
    val showSystemApps: Boolean,
    val injectSignature: Boolean,
    val dynamicMode: Boolean = false,
    val fileProvider: Boolean = false,
    val bottomBarStyle: Int = SettingsStore.BOTTOM_BAR_STANDARD,
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
    val forceOverwrite: Boolean = false,
    val sigMode: String = SettingsStore.SIG_MODE_DEFAULT,
    val entryHook: Boolean = false,
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ManagerSettings = ManagerSettings(
        themeMode = prefs.getInt(KEY_THEME, THEME_SYSTEM),
        signatureBypassLevel = prefs.getInt(KEY_BYPASS_LEVEL, 3),
        hookCore = HookCore.entries.firstOrNull { it.index == prefs.getInt(KEY_HOOK_CORE, 0) }
            ?: HookCore.XPLANT,
        showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM, false),
        injectSignature = prefs.getBoolean(KEY_INJECT_SIGNATURE, true),
        dynamicMode = prefs.getBoolean(KEY_DYNAMIC_MODE, false),
        fileProvider = prefs.getBoolean(KEY_FILE_PROVIDER, false),
        bottomBarStyle = prefs.getInt(KEY_BOTTOM_BAR, SettingsStore.BOTTOM_BAR_STANDARD),
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
        forceOverwrite = prefs.getBoolean(KEY_FORCE_OVERWRITE, false),
        sigMode = normalizedSigMode(prefs.getString(KEY_SIG_MODE, "").orEmpty()),
        entryHook = prefs.getBoolean(KEY_ENTRY_HOOK, false),
    )

    fun setThemeMode(mode: Int) = prefs.edit().putInt(KEY_THEME, mode).apply()

    fun setSignatureBypassLevel(level: Int) =
        prefs.edit().putInt(KEY_BYPASS_LEVEL, level).apply()

    fun setHookCore(core: HookCore) = prefs.edit().putInt(KEY_HOOK_CORE, core.index).apply()

    fun setShowSystemApps(show: Boolean) = prefs.edit().putBoolean(KEY_SHOW_SYSTEM, show).apply()

    fun setInjectSignature(inject: Boolean) =
        prefs.edit().putBoolean(KEY_INJECT_SIGNATURE, inject).apply()

    fun setDynamicMode(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_DYNAMIC_MODE, enabled).apply()

    fun setFileProvider(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FILE_PROVIDER, enabled).apply()

    fun setBottomBarStyle(style: Int) =
        prefs.edit().putInt(KEY_BOTTOM_BAR, style).apply()

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

    fun setForceOverwrite(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_FORCE_OVERWRITE, enabled).apply()

    fun setSigMode(mode: String) =
        prefs.edit().putString(KEY_SIG_MODE, normalizedSigMode(mode)).apply()

    fun setEntryHook(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENTRY_HOOK, enabled).apply()

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
        private const val KEY_BYPASS_LEVEL = "bypass_level"
        private const val KEY_HOOK_CORE = "hook_core"
        private const val KEY_SHOW_SYSTEM = "show_system"
        private const val KEY_INJECT_SIGNATURE = "inject_signature"
        private const val KEY_DYNAMIC_MODE = "dynamic_mode"
        private const val KEY_FILE_PROVIDER = "file_provider"
        private const val KEY_BOTTOM_BAR = "bottom_bar_style"
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
        private const val KEY_FORCE_OVERWRITE = "force_overwrite"
        private const val KEY_SIG_MODE = "sig_mode"
        private const val KEY_ENTRY_HOOK = "entry_hook"
        const val BOTTOM_BAR_STANDARD = 0
        const val BOTTOM_BAR_LIQUID = 1
    }
}

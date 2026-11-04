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
    val fileProvider: Boolean = true,
    val bottomBarStyle: Int = SettingsStore.BOTTOM_BAR_STANDARD,
    val uiStyle: UiStyle = UiStyle.MATERIAL3,
    val exportTreeUri: String = "",
    val exportTreeLabel: String = "",
)

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ManagerSettings = ManagerSettings(
        themeMode = prefs.getInt(KEY_THEME, THEME_SYSTEM),
        signatureBypassLevel = prefs.getInt(KEY_BYPASS_LEVEL, 1),
        hookCore = HookCore.entries.firstOrNull { it.index == prefs.getInt(KEY_HOOK_CORE, 0) }
            ?: HookCore.XPLANT,
        showSystemApps = prefs.getBoolean(KEY_SHOW_SYSTEM, false),
        injectSignature = prefs.getBoolean(KEY_INJECT_SIGNATURE, true),
        dynamicMode = prefs.getBoolean(KEY_DYNAMIC_MODE, false),
        fileProvider = prefs.getBoolean(KEY_FILE_PROVIDER, true),
        bottomBarStyle = prefs.getInt(KEY_BOTTOM_BAR, SettingsStore.BOTTOM_BAR_STANDARD),
        uiStyle = UiStyle.of(prefs.getInt(KEY_UI_STYLE, UiStyle.MATERIAL3.index)),
        exportTreeUri = prefs.getString(KEY_EXPORT_TREE, "").orEmpty(),
        exportTreeLabel = prefs.getString(KEY_EXPORT_LABEL, "").orEmpty(),
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

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

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
        const val BOTTOM_BAR_STANDARD = 0
        const val BOTTOM_BAR_LIQUID = 1
    }
}

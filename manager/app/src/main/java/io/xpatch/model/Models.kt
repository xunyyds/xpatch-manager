package io.xpatch.model

import android.graphics.drawable.Drawable

data class ModuleInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val description: String,
    val apiLevel: Int,
    val enabledApps: Int,
    val icon: Drawable?,
    val recommendedApps: Set<String> = emptySet(),
    val recommendedLabels: List<String> = emptyList(),
    val availableApiLevels: List<Int> = emptyList(),
    val autoApiLevel: Int = 100,
    val modernModule: Boolean = false,
    val legacyModule: Boolean = false,
)

data class AppInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val isSystem: Boolean,
    val enabledModules: Int,
    val icon: Drawable?,
    val apkPath: String = "",
    val versionCode: Long = 0L,
    val hookCore: String? = null,
    val runtimeVersion: Int = 0,
    val size: Long = 0L,
)

data class ScopeEntry(
    val module: ModuleInfo,
    val app: AppInfo,
    val enabled: Boolean,
)



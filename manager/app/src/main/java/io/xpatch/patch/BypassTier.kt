package io.xpatch.patch

import io.xpatch.data.SettingsStore

enum class BypassTier(
    val key: String,
    val level: Int,
    val label: String,
    val needsSource: Boolean,
) {
    NONE("none", 3, "无过签", false),
    NO_SOURCE("nosource", 0, "无 source 过签", false),
    BASIC("basic", 3, "基础过签", false),
    STRICT("strict", 2, "严格过签", false),
    FILE("file", 2, "文件层过签", true),
    SECCOMP("seccomp", 1, "原生重定向", true),
    PLT("plt", 4, "GOT 重定向", true),
    EXTREME("extreme", 3, "最高档三层叠加", true),
    ;

    companion object {
        fun normalizedMode(sigMode: String): String {
            val value = sigMode.trim().lowercase()
            return if (value in SettingsStore.SIG_MODES) value else SettingsStore.SIG_MODE_DEFAULT
        }

        fun of(sigMode: String, bypassLevel: Int): BypassTier {
            val mode = normalizedMode(sigMode)
            if (mode == SettingsStore.SIG_MODE_OFF) return NONE
            if (bypassLevel == NO_SOURCE.level) return NO_SOURCE
            if (bypassLevel == PLT.level) return PLT
            if (mode == SettingsStore.SIG_MODE_DEEP && bypassLevel == EXTREME.level) return EXTREME
            if (bypassLevel == SECCOMP.level) return SECCOMP
            if (mode == SettingsStore.SIG_MODE_JAVA_FILE || mode == SettingsStore.SIG_MODE_DEEP) return FILE
            if (bypassLevel <= STRICT.level) return STRICT
            return BASIC
        }

        fun labelOf(key: String): String = entries.firstOrNull { it.key == key }?.label ?: key
    }
}

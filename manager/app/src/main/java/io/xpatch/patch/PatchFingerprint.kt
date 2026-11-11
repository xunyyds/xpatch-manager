package io.xpatch.patch

import io.xpatch.data.SettingsStore
import java.security.MessageDigest

object PatchFingerprint {

    fun of(sourceKey: String, options: PatchOptions): String {
        val raw = listOf(
            "schema=$SCHEMA",
            "source=$sourceKey",
            "core=${options.hookCore.index}",
            "bypass=${options.bypassLevel}",
            "inject=${options.injectSignature}",
            "profile=${options.signatureProfile?.id ?: "-"}",
            "fileProvider=${options.fileProvider}",
            "dynamic=${effectiveDynamic(options)}",
            "versionName=${options.versionName.orEmpty()}",
            "versionCode=${options.versionCode ?: -1}",
            "embed=${options.embedSource}",
            "forceOverwrite=${options.forceOverwrite}",
            "sigMode=${options.sigMode}",
            "entryHook=${options.entryHook}",
            "obfuscate=${options.obfuscate}",
            "keepCrashPerm=${options.obfuscateKeepCrashPermission}",
        ).joinToString("|")
        return sha256(raw)
    }

    fun summary(options: PatchOptions): String {
        val core = options.hookCore.label
        val tier = tierOf(options)
        val sign = if (options.injectSignature) {
            options.signatureProfile?.name ?: "默认签名"
        } else {
            "不签名"
        }
        val provider = if (options.fileProvider) "文件提供器" else "无文件提供器"
        val mode = if (effectiveDynamic(options)) "动态" else "静态"
        val entry = if (options.entryHook) "入口=工厂+provider" else "入口=工厂"
        val parts = mutableListOf(
            core,
            "$tier（${TIER_LABELS.getValue(tier)}）",
            "sigMode=" + sigModeOf(options),
            entry,
            sign,
            provider,
            mode,
        )
        if (options.forceOverwrite) parts.add("强行覆盖")
        if (options.obfuscate) {
            parts.add("路径混淆")
            if (options.obfuscateKeepCrashPermission) parts.add("保留熔断权限")
        }
        val name = options.versionName.orEmpty()
        val code = options.versionCode
        if (name.isNotBlank() || code != null) {
            parts.add("版本 " + (if (name.isNotBlank()) name else "-") + "(" + (code ?: "-") + ")")
        }
        return parts.joinToString(" · ")
    }

    private fun sigModeOf(options: PatchOptions): String {
        val value = options.sigMode.trim().lowercase()
        return if (value in SettingsStore.SIG_MODES) value else SettingsStore.SIG_MODE_DEFAULT
    }

    private fun effectiveDynamic(options: PatchOptions): Boolean {
        val mode = sigModeOf(options)
        return options.dynamicMode || options.embedSource ||
            (mode != SettingsStore.SIG_MODE_JAVA && mode != SettingsStore.SIG_MODE_OFF) ||
            (options.bypassLevel == 1 && mode != SettingsStore.SIG_MODE_OFF)
    }

    private fun tierOf(options: PatchOptions): String {
        val mode = sigModeOf(options)
        if (mode == SettingsStore.SIG_MODE_OFF) return "none"
        if (mode == SettingsStore.SIG_MODE_DEEP && options.bypassLevel == 3) return "extreme"
        if (options.bypassLevel == 1) return "seccomp"
        if (mode == SettingsStore.SIG_MODE_JAVA_FILE || mode == SettingsStore.SIG_MODE_DEEP) return "file"
        if (options.bypassLevel <= 2) return "strict"
        return "basic"
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            hex.append(HEX[(byte.toInt() shr 4) and 0x0F])
            hex.append(HEX[byte.toInt() and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private const val SCHEMA = 4

    private val TIER_LABELS = mapOf(
        "none" to "无过签",
        "basic" to "基础过签",
        "strict" to "严格过签",
        "file" to "文件层过签",
        "seccomp" to "原生重定向",
        "extreme" to "最高档三层叠加",
    )
}

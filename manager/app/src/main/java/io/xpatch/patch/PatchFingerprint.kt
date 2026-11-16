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
            "versionName=${options.versionName.orEmpty()}",
            "versionCode=${options.versionCode ?: -1}",
            "embed=${options.embedSource}",
            "forceOverwrite=${options.forceOverwrite}",
            "sigMode=${options.sigMode}",
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
        val parts = mutableListOf(
            core,
            "$tier（${BypassTier.labelOf(tier)}）",
            "sigMode=" + sigModeOf(options),
            sign,
            provider,
        )
        if (options.forceOverwrite) parts.add("强行覆盖")
        if (options.obfuscate) {
            parts.add("路径混淆")
            if (options.obfuscateKeepCrashPermission) parts.add("保留崩溃通知权限")
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

    private fun tierOf(options: PatchOptions): String =
        BypassTier.of(options.sigMode, options.bypassLevel).key

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

    private const val SCHEMA = 7
}

package io.xpatch.patch

import java.security.MessageDigest

object PatchFingerprint {

    fun of(sourceKey: String, options: PatchOptions): String {
        val raw = listOf(
            "source=$sourceKey",
            "core=${options.hookCore.index}",
            "bypass=${options.bypassLevel}",
            "inject=${options.injectSignature}",
            "profile=${options.signatureProfile?.id ?: "-"}",
            "fileProvider=${options.fileProvider}",
            "dynamic=${options.dynamicMode}",
            "versionName=${options.versionName.orEmpty()}",
            "versionCode=${options.versionCode ?: -1}",
            "embed=${options.embedSource}",
            "forceOverwrite=${options.forceOverwrite}",
        ).joinToString("|")
        return sha256(raw)
    }

    fun summary(options: PatchOptions): String {
        val core = options.hookCore.label
        val bypass = when (options.bypassLevel) {
            1 -> "seccomp"
            2 -> "hook"
            3 -> "none"
            else -> "level" + options.bypassLevel
        }
        val sign = if (options.injectSignature) {
            options.signatureProfile?.name ?: "默认签名"
        } else {
            "不签名"
        }
        val provider = if (options.fileProvider) "文件提供器" else "无文件提供器"
        val mode = if (options.dynamicMode) "动态" else "静态"
        val parts = mutableListOf(core, bypass, sign, provider, mode)
        if (options.forceOverwrite) parts.add("强行覆盖")
        val name = options.versionName.orEmpty()
        val code = options.versionCode
        if (name.isNotBlank() || code != null) {
            parts.add("版本 " + (if (name.isNotBlank()) name else "-") + "(" + (code ?: "-") + ")")
        }
        return parts.joinToString(" · ")
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
}

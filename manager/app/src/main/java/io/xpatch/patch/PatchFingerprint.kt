package io.xpatch.patch

import io.xpatch.data.SettingsStore
import java.security.MessageDigest

object PatchFingerprint {

    fun of(sourceKey: String, options: PatchOptions): String {
        val raw = listOf(
            // 摘要格式版本：第 2 版起产物语义变了（始终写工厂注入、文件层/原生档强制内嵌 xp/source.apk），
            // 旧记录必须失效并重新打包，否则会复用一份没有内嵌原包的旧产物。
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
        // 档位词表与 UI、PatchService 日志共用一套：none/basic/strict/file/seccomp。
        // 不再用 1/2/3 这类内部枚举值，避免用户把"数字更大"误当成"更强"。
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
        // 路径混淆改变了产物条目名与清单（换了注入集合），摘要里必须写出来，否则两条记录看起来配置相同。
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

    /** 与 PatchService 的强制规则保持一致：需要内嵌的档位实际都会走动态加载。 */
    private fun effectiveDynamic(options: PatchOptions): Boolean {
        val mode = sigModeOf(options)
        return options.dynamicMode || options.embedSource ||
            (mode != SettingsStore.SIG_MODE_JAVA && mode != SettingsStore.SIG_MODE_OFF) ||
            (options.bypassLevel == 1 && mode != SettingsStore.SIG_MODE_OFF)
    }

    /**
     * 六档归类：none(off/3)、basic(java/3)、strict(java/2)、file(java_file/2)、
     * seccomp(java/1)、extreme(deep/3，严格+文件层+dobby/inline 混合三层叠加)。
     * 非表内组合按"更强的一档"归类：显示比实际弱会误导用户，反过来只是保守。
     */
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

    // 摘要格式版本：第 4 版起注入路径混淆开关会改变产物条目名与清单（注入集合都变了），
    // 旧缓存产物必须失效并重新打包，否则会复用一份名字还是 xp/* 的旧包。
    // 第 3 版起产物新增 x86_64 原生库与「极致」档（deep/3），
    // 旧缓存产物必须失效并重新打包，否则会复用一份缺 x86_64 资产/旧档位的包。
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

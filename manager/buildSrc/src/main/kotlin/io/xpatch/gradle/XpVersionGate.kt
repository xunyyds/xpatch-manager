package io.xpatch.gradle

import java.io.File
import org.gradle.api.GradleException

object XpVersionGate {
    val MANAGER_PATTERN = Regex("""const\s+val\s+RUNTIME_VERSION\s*(?::\s*Int\s*)?=\s*(\d+)""")

    val RUNTIME_PATTERN = Regex("""static\s+(?:final\s+)?int\s+RUNTIME_VERSION\s*=\s*(\d+)\s*;""")

    val LATEST_PATTERN = Regex("""const\s+val\s+LATEST_RUNTIME_VERSION\s*(?::\s*Int\s*)?=\s*(\d+)""")

    fun readConstant(file: File, pattern: Regex, label: String): Int {
        if (!file.isFile) {
            throw GradleException("版本门禁失败：找不到 $label 的源码文件：${file.absolutePath}")
        }
        val values = pattern.findAll(file.readText(Charsets.UTF_8))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()
        return when {
            values.isEmpty() -> throw GradleException(
                "版本门禁失败：${file.absolutePath} 里找不到 $label 的常量声明" +
                    "（期望形如 `" + pattern.pattern + "`）"
            )
            values.size > 1 -> throw GradleException(
                "版本门禁失败：$label 在 ${file.absolutePath} 里有多个不同取值：" +
                    values.joinToString("、")
            )
            else -> values.single()
        }
    }
}

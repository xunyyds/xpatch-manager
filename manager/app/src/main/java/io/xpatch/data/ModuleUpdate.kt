package io.xpatch.data

object ModuleUpdate {

    fun compare(
        remoteVersion: String?,
        remoteCode: Long,
        installedVersion: String?,
        installedCode: Long?,
    ): Int {
        val byName = compareNames(remoteVersion, installedVersion)
        if (byName != null) return byName
        return compareCodes(remoteCode, installedCode)
    }

    fun isUpdate(
        remoteVersion: String?,
        remoteCode: Long,
        installedVersion: String?,
        installedCode: Long?,
    ): Boolean = compare(remoteVersion, remoteCode, installedVersion, installedCode) > 0

    private fun compareCodes(remote: Long, installed: Long?): Int {
        if (remote <= 0L || installed == null || installed <= 0L) return 0
        return when {
            remote > installed -> 1
            remote < installed -> -1
            else -> 0
        }
    }

    private fun compareNames(remote: String?, installed: String?): Int? {
        if (remote.isNullOrBlank() || installed.isNullOrBlank()) return null
        val left = segments(remote) ?: return null
        val right = segments(installed) ?: return null
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrElse(index) { 0L }
            val b = right.getOrElse(index) { 0L }
            if (a != b) return if (a > b) 1 else -1
        }
        val leftSuffix = suffix(remote)
        val rightSuffix = suffix(installed)
        return when {
            leftSuffix == rightSuffix -> 0
            leftSuffix.isEmpty() && rightSuffix.isNotEmpty() -> 1
            leftSuffix.isNotEmpty() && rightSuffix.isEmpty() -> -1
            else -> 0
        }
    }

    private fun segments(version: String): List<Long>? {
        val cleaned = normalize(version)
        val numbers = Regex("\\d+").findAll(cleaned)
            .map { it.value.toLongOrNull() ?: 0L }
            .toList()
        return numbers.ifEmpty { null }
    }

    private fun suffix(version: String): String =
        normalize(version).replace(Regex("\\d+"), "").trim('.', '-', '_', ' ', '+')

    private fun normalize(version: String): String =
        version.trim().lowercase().removePrefix("version").removePrefix("v").trim()
}

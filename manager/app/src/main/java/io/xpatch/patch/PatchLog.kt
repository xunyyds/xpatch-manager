package io.xpatch.patch

import java.util.Locale

class PatchLog private constructor(
    private val sink: (String) -> Unit,
    private val level: Int,
) {
    fun milestone(msg: String) = emit(LEVEL_MINIMAL, msg)

    fun step(msg: String) = emit(LEVEL_NORMAL, msg)

    fun debug(msg: String) = emit(LEVEL_VERBOSE, msg)

    fun asStepSink(): (String) -> Unit = { step(it) }

    private fun emit(msgLevel: Int, msg: String) {
        if (msgLevel <= level) sink(msg)
    }

    companion object {
        const val LEVEL_MINIMAL = 0
        const val LEVEL_NORMAL = 1
        const val LEVEL_VERBOSE = 2

        val LEVEL_OPTIONS = listOf(
            LEVEL_MINIMAL to "精简",
            LEVEL_NORMAL to "完整",
            LEVEL_VERBOSE to "详细",
        )

        fun normalize(level: Int): Int = level.coerceIn(LEVEL_MINIMAL, LEVEL_VERBOSE)

        fun labelOf(level: Int): String =
            LEVEL_OPTIONS.firstOrNull { it.first == normalize(level) }?.second
                ?: LEVEL_OPTIONS.first { it.first == LEVEL_NORMAL }.second

        fun of(sink: (String) -> Unit, level: Int): PatchLog = PatchLog(sink, normalize(level))

        fun silent(): PatchLog = PatchLog({}, LEVEL_VERBOSE)

        fun mb(bytes: Long): String = "${bytes / 1024 / 1024} MB"

        fun secs(ms: Long): String = "%.1fs".format(Locale.US, ms / 1000.0)
    }
}

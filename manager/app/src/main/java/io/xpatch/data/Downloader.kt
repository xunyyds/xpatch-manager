package io.xpatch.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Downloader {

    data class Progress(val read: Long, val total: Long, val bytesPerSecond: Long)

    private const val BUFFER_BYTES = 256 * 1024
    private const val STALL_WINDOW_MS = 5_000L
    private const val STALL_MIN_BYTES = 24L * 1024
    private const val PROGRESS_INTERVAL_MS = 200L
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    fun download(
        urls: List<String>,
        target: File,
        control: DownloadControl,
        userAgent: String,
        onProgress: (Progress) -> Unit = {},
    ): Boolean {
        val ordered = if (urls.size > 1) orderBySpeed(urls, userAgent) else urls
        for (url in ordered) {
            control.checkpoint()
            val ok = runCatching { downloadFrom(url, target, control, userAgent, onProgress) }.getOrDefault(false)
            if (ok) return true
            if (control.cancelled) return false
        }
        return false
    }

    private fun orderBySpeed(urls: List<String>, userAgent: String): List<String> {
        val cost = java.util.concurrent.ConcurrentHashMap<String, Long>()
        urls.forEach { url ->
            Thread {
                runCatching { cost[url] = probe(url, userAgent) }
            }.apply { isDaemon = true }.start()
        }
        val deadline = System.currentTimeMillis() + 3_500L
        while (System.currentTimeMillis() < deadline && cost.size < urls.size) {
            try {
                Thread.sleep(50L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val sorted = urls.sortedBy { cost[it] ?: Long.MAX_VALUE }
        return if (sorted.firstOrNull()?.let { cost[it] } == null) urls else sorted
    }

    private fun probe(url: String, userAgent: String): Long {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2_500
            connection.readTimeout = 2_500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Range", "bytes=0-65535")
            connection.connect()
            if (connection.responseCode !in 200..299) return Long.MAX_VALUE
            val start = System.currentTimeMillis()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            connection.inputStream.use { input ->
                while (total < 64 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                }
            }
            return if (total <= 0) Long.MAX_VALUE else System.currentTimeMillis() - start
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFrom(
        url: String,
        target: File,
        control: DownloadControl,
        userAgent: String,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        val existing = if (target.isFile) target.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0L) {
                connection.setRequestProperty("Range", "bytes=$existing-")
            }
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) return false

            val resuming = code == 206 && existing > 0L
            val startAt = if (resuming) existing else 0L
            val contentLength = connection.contentLengthLong
            val total = if (resuming && contentLength > 0) startAt + contentLength else contentLength

            var written = startAt
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            var reportBytes = written
            var reportAt = System.currentTimeMillis()
            onProgress(Progress(written, total, 0L))

            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                java.io.FileOutputStream(target, resuming).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        control.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        windowBytes += count

                        val now = System.currentTimeMillis()
                        if (now - reportAt >= PROGRESS_INTERVAL_MS) {
                            val bps = (written - reportBytes) * 1000L / maxOf(1L, now - reportAt)
                            onProgress(Progress(written, total, bps))
                            reportBytes = written
                            reportAt = now
                        }

                        if (now - windowStart >= STALL_WINDOW_MS) {
                            if (windowBytes < STALL_MIN_BYTES) {
                                return false
                            }
                            windowStart = now
                            windowBytes = 0L
                        }
                    }
                }
            }
            onProgress(Progress(written, total, 0L))
            return total <= 0L || written == total
        } finally {
            connection.disconnect()
        }
    }
}

package io.xpatch.patch

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import io.xpatch.IXPatchService
import java.io.File
import java.io.RandomAccessFile

class XPatchUserService : IXPatchService.Stub {

    constructor()

    @Keep
    constructor(context: Context)

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String): String = runCatching {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text
    }.getOrDefault("")

    override fun readText(path: String, maxBytes: Int): String = runCatching {
        val file = File(path)
        if (!file.isFile) return ""
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            val size = (length - start).toInt()
            if (size <= 0) return ""
            val bytes = ByteArray(size)
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")

    override fun openRead(path: String): ParcelFileDescriptor? = runCatching {
        val file = File(path)
        if (!file.isFile) {
            null
        } else {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }.getOrNull()

    override fun forceStop(packageName: String) {
        runCatching {
            exec("am force-stop " + shellQuote(packageName))
        }
    }

    override fun dexopt(packageName: String) {
        runCatching {
            exec("cmd package compile -m speed -f " + shellQuote(packageName))
        }
    }

    override fun getUid(): Int = Process.myUid()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

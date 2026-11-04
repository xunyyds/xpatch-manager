package io.xpatch.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ExportStore(private val context: Context) {

    data class Target(val uri: String, val label: String, val path: String)

    data class Saved(val uri: String, val path: String, val size: Long, val name: String)

    fun savedTarget(): Target? {
        val settings = SettingsStore(context).load()
        if (settings.exportTreeUri.isBlank()) return null
        val uri = settings.exportTreeUri
        return Target(uri, settings.exportTreeLabel, pathOf(uri))
    }

    fun saveTarget(uri: String) {
        val label = runCatching { documentsLabel(Uri.parse(uri)) }.getOrNull().orEmpty()
        SettingsStore(context).setExportTree(uri, label)
    }

    suspend fun save(source: File, fileName: String, treeUri: String): Saved =
        withContext(Dispatchers.IO) {
            val tree = Uri.parse(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val target = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                MIME_APK,
                fileName,
            ) ?: error("无法在目标目录创建文件")

            var written = 0L
            runCatching {
                context.contentResolver.openOutputStream(target, "w")?.use { out ->
                    source.inputStream().buffered(BUFFER).use { input ->
                        val buffer = ByteArray(BUFFER)
                        var read = input.read(buffer)
                        while (read > 0) {
                            out.write(buffer, 0, read)
                            written += read
                            read = input.read(buffer)
                        }
                    }
                } ?: error("无法写入目标文件")
            }.onFailure {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                throw it
            }

            Saved(
                uri = target.toString(),
                path = pathOf(treeUri),
                size = written,
                name = fileName,
            )
        }

    fun pathOf(treeUri: String): String {
        val tree = Uri.parse(treeUri)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        val tail = documentId.orEmpty().substringAfter(':', "")
        val volume = documentId.orEmpty().substringBefore(':')
        val root = when (volume) {
            "primary" -> "/storage/emulated/0"
            "home" -> "/storage/emulated/0/Documents"
            "downloads" -> "/storage/emulated/0/Download"
            else -> "/storage/$volume"
        }
        return if (tail.isBlank()) root else "$root/$tail"
    }

    private fun documentsLabel(uri: Uri): String? = runCatching {
        val document = DocumentsContract.buildDocumentUriUsingTree(
            uri,
            DocumentsContract.getTreeDocumentId(uri),
        )
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(document, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
        const val BUFFER = 256 * 1024
    }
}

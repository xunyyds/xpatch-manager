package io.xpatch.data

import android.content.Context
import android.content.Intent
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
        val uri = settings.exportTreeUri
        if (uri.isBlank()) return null
        if (!hasAccess(uri)) return null
        val label = settings.exportTreeLabel.ifBlank { labelOf(uri) }.ifBlank { pathOf(uri) }
        return Target(uri, label, pathOf(uri))
    }

    fun saveTarget(uri: String): Target? {
        if (uri.isBlank()) return null
        if (!takeAccess(uri)) return null
        val settings = SettingsStore(context)
        val previous = settings.load().exportTreeUri
        if (previous.isNotBlank() && previous != uri) release(previous)
        val label = labelOf(uri).ifBlank { pathOf(uri) }
        settings.setExportTree(uri, label)
        return Target(uri, label, pathOf(uri))
    }

    fun release(treeUri: String): Boolean = runCatching {
        if (treeUri.isBlank()) return@runCatching false
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

    fun hasAccess(treeUri: String): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri.toString() == treeUri && permission.isReadPermission && permission.isWritePermission
        }
    }.getOrDefault(false)

    suspend fun save(source: File, fileName: String, treeUri: String, mime: String = MIME_APK): Saved =
        withContext(Dispatchers.IO) {
            val tree = Uri.parse(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val target = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mime,
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
                name = displayNameOf(target) ?: fileName,
            )
        }

    fun delete(documentUri: String): Boolean = runCatching {
        if (documentUri.isBlank()) return@runCatching false
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(documentUri))
    }.getOrDefault(false)

    fun labelOf(treeUri: String): String = runCatching { documentsLabel(Uri.parse(treeUri)) }
        .getOrNull()
        .orEmpty()

    fun pathOf(treeUri: String): String {
        val tree = Uri.parse(treeUri)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
        val volume = documentId.orEmpty().substringBefore(':')
        val tail = documentId.orEmpty().substringAfter(':', "")
        val root = when (volume) {
            "primary" -> "/storage/emulated/0"
            "home" -> "/storage/emulated/0/Documents"
            "downloads" -> "/storage/emulated/0/Download"
            "raw" -> ""
            else -> "/storage/$volume"
        }
        return when {
            tail.isBlank() -> root.ifBlank { "/" }
            root.isBlank() -> tail
            else -> "$root/$tail"
        }
    }

    private fun takeAccess(treeUri: String): Boolean {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        return granted || hasAccess(treeUri)
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

    private fun displayNameOf(uri: Uri): String? = runCatching {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val MIME_APK = "application/vnd.android.package-archive"
        const val MIME_ZIP = "application/zip"

        private const val BUFFER = 256 * 1024
    }
}
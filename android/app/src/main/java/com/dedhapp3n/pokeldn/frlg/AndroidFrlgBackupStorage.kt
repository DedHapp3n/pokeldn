package com.dedhapp3n.pokeldn.frlg

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

internal class AndroidFrlgBackupFolder(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("frlg_backup_storage", Context.MODE_PRIVATE)
    private val folderPreference = FrlgBackupFolderPreference(object : FrlgStringPreference {
        override fun get(key: String) = preferences.getString(key, null)
        override fun put(key: String, value: String?) {
            preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
        }
    })

    fun uri(): Uri? = folderPreference.uri()?.let(Uri::parse)

    fun remember(uri: Uri) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        folderPreference.setUri(uri.toString())
    }

    fun displayName(): String? = uri()?.let { tree ->
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        appContext.contentResolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun externalDirectory(): FrlgExternalBackupDirectory? = uri()?.let { AndroidSafDirectory(appContext.contentResolver, it) }
}

private class AndroidSafDirectory(
    private val resolver: ContentResolver,
    private val tree: Uri,
) : FrlgExternalBackupDirectory {
    override fun writeUnique(preferredFilename: String, save: ByteArray): String {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val names = mutableSetOf<String>()
        resolver.query(children, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) names += cursor.getString(0)
        }
        val filename = FrlgBackupStore.uniqueFilename(preferredFilename, names::contains)
        val document = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", filename)
            ?: throw IllegalStateException("Could not create backup in the selected folder")
        try {
            resolver.openOutputStream(document, "w")?.use { FrlgSaveExport.write(save, it) }
                ?: throw IllegalStateException("Could not open backup document")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw error
        }
        return filename
    }
}

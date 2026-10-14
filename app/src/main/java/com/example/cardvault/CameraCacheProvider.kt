package com.example.cardvault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Grants the system camera app temporary write access to one cache-only capture file. */
class CameraCacheProvider : ContentProvider() {
    private fun captureFile(uri: Uri): File {
        require(uri.lastPathSegment == FILE_NAME) { "Unsupported camera output." }
        return File(requireNotNull(context).cacheDir, FILE_NAME)
    }

    override fun onCreate() = context != null

    override fun getType(uri: Uri) = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = captureFile(uri)
        val columns = projection ?: arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column -> when (column) {
                android.provider.OpenableColumns.DISPLAY_NAME -> FILE_NAME
                android.provider.OpenableColumns.SIZE -> file.length()
                else -> null
            } })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val flags = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY
        } else ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(captureFile(uri), flags)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object { private const val FILE_NAME = "card-capture.jpg" }
}

package net.de5.phoneagent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands a cached APK to the system installer as `content://`.
 *
 * Why not `file://`: since Android 7 a file URI in an Intent throws
 * FileUriExposedException, and FLAG_GRANT_READ_URI_PERMISSION is meaningless for
 * it. Why not androidx FileProvider: this project has zero third-party runtime
 * dependencies and stays that way, so the twenty lines of a read-only provider
 * are cheaper than the dependency.
 *
 * Not exported: the only reader is whichever component we grant the URI to,
 * through the Intent flag.
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = Admin.APK_MIME

    /** Enough for the installer to show a name and a size instead of "unknown". */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(columns.size)
        var i = 0
        while (i < columns.size) {
            row[i] = when (columns[i]) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
            i++
        }
        val cursor = MatrixCursor(columns, 1)
        cursor.addRow(row)
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri) ?: throw FileNotFoundException("no such apk: " + uri)
        // Read-only on purpose: the installer never writes back.
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    /**
     * Map a URI back to a file, refusing anything outside our cache dir.
     *
     * The path check is the whole point: a crafted URI (`../../databases/x`)
     * must not turn this provider into a file reader.
     */
    private fun resolve(uri: Uri): File? {
        val ctx = context ?: return null
        val dir = directory(ctx)
        val name = uri.lastPathSegment ?: return null
        val file = File(dir, name)
        val canonical = try {
            file.canonicalFile
        } catch (t: Throwable) {
            return null
        }
        return if (canonical.isFile && canonical.parentFile == dir.canonicalFile) canonical else null
    }

    companion object {

        /** Private files; cleared by the system whenever it needs the space. */
        fun directory(context: Context): File {
            val dir = File(context.cacheDir, "apk")
            try {
                dir.mkdirs()
            } catch (t: Throwable) {
                // ignore
            }
            return dir
        }

        private fun authority(context: Context): String = context.packageName + ".apk"

        /** The `content://` URI an Intent should carry for [file]. */
        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath("apk")
                .appendPath(file.name)
                .build()
    }
}

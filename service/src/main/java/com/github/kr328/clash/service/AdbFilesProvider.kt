package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.util.importedDir
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * ADB-accessible ContentProvider for profile config files.
 * No MANAGE_DOCUMENTS permission required — accessible via:
 *   adb shell content read --uri content://{appId}.adbfiles/{uuid}/config.yaml
 *   adb shell content write --uri content://{appId}.adbfiles/{uuid}/config.yaml
 *
 * Also supports listing profiles:
 *   adb shell content query --uri content://{appId}.adbfiles/profiles
 */
class AdbFilesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("invalid path: $uri")

        val uuid = segments[0]
        val fileName = segments.drop(1).joinToString("/")
        val profileDir = context!!.importedDir.resolve(uuid)
        val file = profileDir.resolve(fileName)

        if (!file.canonicalPath.startsWith(profileDir.canonicalPath))
            throw SecurityException("path traversal blocked")

        if (mode.contains("w")) {
            file.parentFile?.mkdirs()
        }

        if (!file.exists() && !mode.contains("w"))
            throw FileNotFoundException("$fileName not found in profile $uuid")

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val segments = uri.pathSegments
        if (segments.firstOrNull() == "profiles") {
            return runBlocking { listProfiles() }
        }
        if (segments.size >= 1) {
            return listProfileFiles(segments[0])
        }
        return null
    }

    private suspend fun listProfiles(): Cursor {
        val dao = ImportedDao()
        val uuids = dao.queryAllUUIDs()
        val cursor = MatrixCursor(arrayOf("uuid", "name", "type", "source"))
        for (uuid in uuids) {
            val p = dao.queryByUUID(uuid) ?: continue
            cursor.addRow(arrayOf(p.uuid.toString(), p.name, p.type.name, p.source))
        }
        return cursor
    }

    private fun listProfileFiles(uuid: String): Cursor {
        val dir = context!!.importedDir.resolve(uuid)
        val cursor = MatrixCursor(arrayOf("name", "size", "modified"))
        if (dir.exists()) {
            listFilesRecursive(dir, dir, cursor)
        }
        return cursor
    }

    private fun listFilesRecursive(root: File, dir: File, cursor: MatrixCursor) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                cursor.addRow(arrayOf(
                    f.relativeTo(root).path,
                    f.length(),
                    f.lastModified()
                ))
            } else if (f.isDirectory) {
                listFilesRecursive(root, f, cursor)
            }
        }
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

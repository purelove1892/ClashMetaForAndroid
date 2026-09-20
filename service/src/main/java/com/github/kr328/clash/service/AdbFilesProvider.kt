package com.github.kr328.clash.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.util.*

/**
 * ADB-accessible ContentProvider for profile management on non-root devices.
 * Runs in :background process — full access to Room DB, SharedPreferences, broadcasts.
 *
 * File operations:
 *   adb shell content read --uri content://{appId}.adbfiles/{uuid}/config.yaml
 *   adb shell content write --uri content://{appId}.adbfiles/{uuid}/config.yaml
 *
 * Profile CRUD:
 *   adb shell content query --uri content://{appId}.adbfiles/profiles
 *   adb shell content insert --uri content://{appId}.adbfiles/profiles --bind uuid:s:{uuid} --bind name:s:{name} --bind type:s:File
 *   adb shell content delete --uri content://{appId}.adbfiles/profiles/{uuid}
 *
 * Active profile:
 *   adb shell content query --uri content://{appId}.adbfiles/active
 *   adb shell content update --uri content://{appId}.adbfiles/active --bind uuid:s:{uuid}
 */
class AdbFilesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val segments = uri.pathSegments
        if (segments.size < 2) throw FileNotFoundException("invalid path: $uri")

        val first = segments[0]
        if (first == "profiles" || first == "active") throw FileNotFoundException("not a file path: $uri")

        val uuid = first
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

        if (segments.firstOrNull() == "active") {
            return queryActive()
        }

        if (segments.firstOrNull() == "profiles") {
            return runBlocking { listProfiles() }
        }

        if (segments.size >= 1) {
            return listProfileFiles(segments[0])
        }
        return null
    }

    private fun queryActive(): Cursor {
        val store = ServiceStore(context!!)
        val cursor = MatrixCursor(arrayOf("uuid"))
        store.activeProfile?.let { cursor.addRow(arrayOf(it.toString())) }
        return cursor
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

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val segments = uri.pathSegments
        if (segments.firstOrNull() != "profiles" || values == null) return null

        val uuidStr = values.getAsString("uuid") ?: UUID.randomUUID().toString()
        val uuid = UUID.fromString(uuidStr)
        val name = values.getAsString("name") ?: "Profile"
        val type = try {
            Profile.Type.valueOf(values.getAsString("type") ?: "File")
        } catch (_: Exception) { Profile.Type.File }
        val source = values.getAsString("source") ?: ""
        val interval = values.getAsLong("interval") ?: 0L

        runBlocking {
            ImportedDao().insert(Imported(
                uuid = uuid,
                name = name,
                type = type,
                source = source,
                interval = interval,
                upload = 0L,
                download = 0L,
                total = 0L,
                expire = 0L,
                createdAt = System.currentTimeMillis()
            ))
        }

        context!!.importedDir.resolve(uuidStr).mkdirs()

        return uri.buildUpon().appendPath(uuidStr).build()
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "profiles") return 0

        val uuid = try { UUID.fromString(segments[1]) } catch (_: Exception) { return 0 }

        runBlocking { ImportedDao().remove(uuid) }

        context!!.importedDir.resolve(segments[1]).deleteRecursively()
        return 1
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val segments = uri.pathSegments
        if (values == null) return 0

        if (segments.firstOrNull() == "active") {
            val uuidStr = values.getAsString("uuid") ?: return 0
            val uuid = try { UUID.fromString(uuidStr) } catch (_: Exception) { return 0 }

            val store = ServiceStore(context!!)
            store.activeProfile = uuid
            context!!.sendProfileChanged(uuid)
            return 1
        }

        return 0
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
}

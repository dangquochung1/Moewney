package app.meowney.core.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.meowney.AppContainer
import app.meowney.BuildConfig
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.TxnRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupManifest(val app: String = "meowney", val schemaVersion: Int, val appVersion: String, val createdAt: Long)

/**
 * Manual backup: one .zip with the database, the settings file and a manifest. Restoring
 * checks the manifest, swaps the files and restarts the app.
 */
class BackupManager(private val context: Context, private val container: AppContainer) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dbFile get() = context.getDatabasePath(MeowneyDb.FILE)
    private val settingsFile get() = File(context.filesDir, "datastore/settings.preferences_pb")

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        // fold the write-ahead log into the main file so one file is the whole database
        container.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        context.contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                val manifest = BackupManifest(schemaVersion = MeowneyDb.SCHEMA_VERSION, appVersion = BuildConfig.VERSION_NAME, createdAt = System.currentTimeMillis())
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(MeowneyDb.FILE))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                if (settingsFile.exists()) {
                    zip.putNextEntry(ZipEntry("settings.preferences_pb"))
                    settingsFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Throws with a readable message if the file is not a Meowney backup. Restarts the app on success. */
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = File(e.name).name // no paths from the archive
                    if (name in setOf("manifest.json", MeowneyDb.FILE, "settings.preferences_pb")) {
                        File(dir, name).outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val manifestFile = File(dir, "manifest.json")
        require(manifestFile.exists() && File(dir, MeowneyDb.FILE).exists()) { "File này không phải bản sao lưu Meowney." }
        val manifest = json.decodeFromString(BackupManifest.serializer(), manifestFile.readText())
        require(manifest.app == "meowney") { "File này không phải bản sao lưu Meowney." }
        require(manifest.schemaVersion <= MeowneyDb.SCHEMA_VERSION) { "Bản sao lưu từ phiên bản app mới hơn. Cập nhật app trước nhé." }

        container.db.close()
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
        File(dir, MeowneyDb.FILE).copyTo(dbFile, overwrite = true)
        File(dir, "settings.preferences_pb").takeIf { it.exists() }?.copyTo(settingsFile.apply { parentFile?.mkdirs() }, overwrite = true)
        dir.deleteRecursively()
        restart()
    }

    private fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        fun fileName(prefix: String, ext: String): String =
            "$prefix-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now()) + ".$ext"
    }
}

/** Transactions as a spreadsheet: UTF-8 with BOM so Excel shows Vietnamese correctly. */
object CsvExporter {
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun write(context: Context, uri: Uri, rows: List<TxnRow>) {
        context.contentResolver.openOutputStream(uri)!!.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("﻿")
            w.write("Ngày,Giờ,Loại,Số tiền,Ví,Ví nhận,Danh mục,Lọ,Ghi chú\r\n")
            for (r in rows) {
                val at = Instant.ofEpochMilli(r.occurredAt).atZone(ZoneId.systemDefault())
                val kind = when (r.type) {
                    "EXPENSE" -> "Chi"
                    "INCOME" -> "Thu"
                    "TRANSFER" -> "Chuyển"
                    "OPENING" -> "Số dư đầu"
                    "ADJUST" -> "Điều chỉnh"
                    "DEBT_OUT" -> if (r.debtPerson != null) "Nợ ra (${r.debtPerson})" else "Nợ ra"
                    "DEBT_IN" -> if (r.debtPerson != null) "Nợ vào (${r.debtPerson})" else "Nợ vào"
                    "JAR_MOVE" -> "Chuyển lọ (thói quen)"
                    else -> r.type
                }
                val signed = if (r.type == "EXPENSE") -r.amount else r.amount
                val cells = listOf(
                    dateFmt.format(at), timeFmt.format(at), kind, signed.toString(), r.walletName.orEmpty(),
                    r.toWalletName.orEmpty(), r.categoryName.orEmpty(), r.jarName.orEmpty(), r.note,
                )
                w.write(cells.joinToString(",") { csv(it) })
                w.write("\r\n")
            }
        }
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

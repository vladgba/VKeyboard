package x.vladgba.keyboard.core

import android.content.Context
import x.vladgba.keyboard.flex.FlexParser
import x.vladgba.keyboard.keyboard.TouchStats
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Creates and restores backup archives (ZIP) and individual files.
 * Zero external library dependencies: uses standard Java java.util.zip.
 */
object BackupManager {

    enum class BackupScope {
        FULL,           // Everything: settings, layouts, themes, dictionary, macros, touchstats
        CONFIG_ONLY,    // Settings, dictionary, macros
        LAYOUTS_THEMES  // Layouts and themes only
    }

    private val ALLOWED_EXTS = setOf(LAYOUT_EXT, THEME_EXT, DICT_EXT, MACROS_EXT, STATS_EXT, CLIP_PINS_EXT)

    class RestoreResult(val success: Boolean, val count: Int, val message: String)

    /**
     * Packs files from the app's private filesDir into a ZIP archive.
     */
    fun createBackup(ctx: Context, os: OutputStream, scope: BackupScope = BackupScope.FULL): Boolean {
        return try {
            ZipOutputStream(BufferedOutputStream(os)).use { zos ->
                val files = ctx.filesDir.listFiles() ?: emptyArray()
                for (file in files) {
                    if (!file.isFile) continue
                    val ext = file.name.substringAfterLast('.', "")
                    val base = file.name.substringBeforeLast('.')
                    if (ext !in ALLOWED_EXTS) continue

                    val include = when (scope) {
                        BackupScope.FULL -> true
                        BackupScope.CONFIG_ONLY -> (base == SETTINGS_FILENAME && ext == LAYOUT_EXT) || ext == DICT_EXT || ext == MACROS_EXT || ext == STATS_EXT || ext == CLIP_PINS_EXT
                        BackupScope.LAYOUTS_THEMES -> (base != SETTINGS_FILENAME && ext == LAYOUT_EXT) || ext == THEME_EXT
                    }
                    if (!include) continue

                    zos.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Restores files from a ZIP archive into the app's filesDir.
     * Includes safety checks against Zip Slip (directory traversal).
     */
    fun restoreBackup(ctx: Context, inputStream: InputStream): RestoreResult {
        var count = 0
        try {
            val destDir = ctx.filesDir
            val canonicalDest = destDir.canonicalPath
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) continue

                    // Strip any directory paths to enforce a flat directory in filesDir
                    val name = File(entry.name).name
                    val ext = name.substringAfterLast('.', "")
                    if (ext !in ALLOWED_EXTS) continue

                    val targetFile = File(destDir, name)
                    if (!targetFile.canonicalPath.startsWith(canonicalDest)) continue

                    targetFile.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                    count++
                    zis.closeEntry()
                }
            }

            if (count > 0) {
                reloadAll(ctx)
                return RestoreResult(true, count, "Restored $count files successfully")
            } else {
                return RestoreResult(false, 0, "No valid keyboard files found in archive")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return RestoreResult(false, count, e.message ?: "Failed to restore backup")
        }
    }

    /**
     * Exports a single file (like settings.txt) to an output stream.
     */
    fun exportSingleFile(ctx: Context, name: String, ext: String, os: OutputStream): Boolean = try {
        val file = PFile(ctx, name, ext)
        if (file.exists()) {
            os.write(file.read().toByteArray())
            true
        } else false
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Imports a single file (like settings.txt) from an input stream.
     */
    fun importSingleFile(ctx: Context, name: String, ext: String, inputStream: InputStream): Boolean = try {
        val text = inputStream.bufferedReader().use { it.readText() }
        if (name == SETTINGS_FILENAME && ext == LAYOUT_EXT) {
            // Validate syntax before overwriting
            FlexParser.parse(text)
        }
        val ok = PFile(ctx, name, ext).write(text)
        if (ok) {
            if (name == SETTINGS_FILENAME && ext == LAYOUT_EXT) {
                Settings.loadVars(ctx)
                Theme.invalidate()
            } else if (ext == THEME_EXT) {
                Theme.invalidate()
            }
        }
        ok
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Re-reads settings and invalidates caches after restoring.
     */
    fun reloadAll(ctx: Context) {
        Settings.loadVars(ctx)
        Theme.invalidate()
        TouchStats.reload(ctx)
    }
}

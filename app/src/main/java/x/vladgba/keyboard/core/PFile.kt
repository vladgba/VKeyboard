package x.vladgba.keyboard.core

import android.content.Context
import java.io.File

/**
 * A text file in the app's private storage (`filesDir/<name>.<ext>`).
 * Layouts use `.txt`, themes `.ini`, the text-expansion dictionary `.dic`.
 */
class PFile(ctx: Context, fileName: String, ext: String = LAYOUT_EXT) : File(ctx.filesDir, "$fileName.$ext") {

    /** File contents, or an empty string when the file is missing/unreadable. */
    fun read(): String = try {
        if (exists()) readText() else ""
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }

    /** Writes [s] atomically (temp file + rename) so a crash never leaves a half-written layout. */
    fun write(s: String): Boolean = try {
        val tmp = File(parentFile, "$name.tmp")
        tmp.writeText(s)
        if (!tmp.renameTo(this)) {
            writeText(s)
            tmp.delete()
        }
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    companion object {
        /** Reads a bundled asset, or null when it doesn't exist. */
        fun asset(ctx: Context, name: String, ext: String = LAYOUT_EXT): String? = try {
            ctx.assets.open("$name.$ext").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }

        /** Copies a bundled asset into private storage. Returns false when the asset is missing. */
        fun install(ctx: Context, name: String, ext: String = LAYOUT_EXT, overwrite: Boolean = false): Boolean {
            val file = PFile(ctx, name, ext)
            if (file.exists() && !overwrite) return true
            return asset(ctx, name, ext)?.let { file.write(it) } ?: false
        }

        /** Names (without extension) of the user's files with [ext]. */
        fun list(ctx: Context, ext: String): List<String> =
            (ctx.filesDir.listFiles() ?: emptyArray())
                .map { it.name }
                .filter { it.endsWith(".$ext") }
                .map { it.removeSuffix(".$ext") }
                .sorted()
    }
}

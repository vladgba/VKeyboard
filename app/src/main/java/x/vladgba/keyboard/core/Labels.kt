package x.vladgba.keyboard.core

import android.content.Context

/**
 * Translatable key labels: a layout can use `"key": "@string/kb_copy"` and the label follows the
 * app language. Unknown names are shown as written. Results are cached per name.
 */
object Labels {
    private const val PREFIX = "@string/"
    private val cache = HashMap<String, String>()

    fun resolve(ctx: Context, s: String): String {
        if (!s.startsWith(PREFIX)) return s
        return cache.getOrPut(s) {
            val name = s.removePrefix(PREFIX)
            @Suppress("DiscouragedApi") // names come from layout files, so they can only be looked up at runtime
            val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
            if (id != 0) ctx.getString(id) else name
        }
    }

    /** Call when the app language changes. */
    fun clear() = cache.clear()
}

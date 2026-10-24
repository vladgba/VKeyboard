package x.vladgba.keyboard.keyboard

import android.view.KeyEvent
import x.vladgba.keyboard.core.KEY_KEY
import x.vladgba.keyboard.flex.FlexNode

object ModifierMap {
    private fun state(mask: Int): Int =
        (if (mask and KeyEvent.META_SHIFT_MASK != 0) 1 else 0) or
        (if (mask and KeyEvent.META_ALT_MASK != 0) 2 else 0) or
        (if (mask and KeyEvent.META_CTRL_MASK != 0) 4 else 0) or
        (if (mask and KeyEvent.META_META_MASK != 0) 8 else 0) or
        (if (mask and KeyEvent.META_ALT_RIGHT_ON != 0) 16 else 0)

    private fun signature(spec: String): Int? {
        if (spec.equals("none", true)) return 0
        var bits = 0
        for (part in spec.lowercase().split('+')) bits = bits or when (part.trim()) {
            "shift" -> 1; "alt" -> 2; "ctrl", "control" -> 4; "meta", "super" -> 8; "altgr" -> 18
            else -> return null
        }
        return bits
    }

    fun resolve(key: FlexNode, layout: FlexNode, mask: Int): String? {
        val active = state(mask)
        val own = key.params["modmap"] as? FlexNode
        own?.params?.entries?.firstOrNull { signature(it.key) == active && it.value is String }
            ?.let { return it.value as String }
        val global = layout.params["modmap"] as? FlexNode ?: return null
        val table = global.params.entries.firstOrNull { signature(it.key) == active }?.value as? FlexNode ?: return null
        return table.params[key.str(KEY_KEY)] as? String
    }
}

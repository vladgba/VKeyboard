package x.vladgba.keyboard.keyboard

import android.graphics.Canvas
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A parsed layout file: a node whose children are rows, whose children are keys.
 * Inherits styles from [Theme] (and through it from [Settings]).
 *
 * Geometry is computed by [measure] for the real view width, and recomputed when the width or
 * orientation changes (the old code computed it once at load time, so a rotated keyboard kept
 * stale key widths).
 */
class KeybLayout(val c: Keyboard, glob: FlexNode) : FlexNode(glob, Theme) {
    val rows = ArrayList<Row>()
    val keys: List<Key> get() = rows.flatMap { it.keys }

    var width = 0
        private set
    var height = 1
        private set
    var loaded = false
        private set
    var lastModified = 0L

    private var measuredWidth = -1
    private var measuredPortrait = true
    private var measuredRefSize = -1f

    init {
        try {
            for (i in 0 until childCount()) {
                val rowNode = this[i]
                val row = Row(this, rowNode)
                rows.add(row)
                for (k in 0 until rowNode.childCount()) row.keys.add(Key(c, row, 0, 0, rowNode[k]))
            }
            loaded = rows.isNotEmpty()
        } catch (e: Exception) {
            c.prStack(e)
        }
    }

    /** Height of a row with height 1 for the current screen and height settings. */
    private fun refSize(portrait: Boolean): Float {
        val dm = c.ctx.resources.displayMetrics
        return float(ROW_HEIGHT, 1f) / 18f * max(dm.heightPixels, dm.widthPixels) *
                (if (portrait) Settings.float(SETTING_PORTRAIT_HEIGHT, 1f) else Settings.float(SETTING_LANDSCAPE_HEIGHT, 1f))
    }

    /**
     * Computes all key positions for [viewWidth]. Cheap no-op when nothing changed. The row size is
     * part of the check, so a changed height setting (or screen size) re-measures layouts that were
     * cached with the old size, not only the ones loaded afterwards.
     */
    fun measure(viewWidth: Int, portrait: Boolean, force: Boolean = false) {
        val refSize = refSize(portrait)
        if (!force && viewWidth == measuredWidth && portrait == measuredPortrait && refSize == measuredRefSize) return
        measuredWidth = viewWidth
        measuredPortrait = portrait
        measuredRefSize = refSize
        width = viewWidth

        var y = 0
        for (row in rows) {
            row.y = y
            row.height = (refSize * row.ownHeight()).toInt()
            row.layoutKeys(viewWidth)
            y += row.height
        }
        height = y.coerceAtLeast(1)
    }

    /** Recomputes geometry after an edit (rows/keys added, removed, resized). */
    fun relayout() = measure(if (measuredWidth > 0) measuredWidth else c.ctx.resources.displayMetrics.widthPixels, measuredPortrait, true)

    fun getKey(x: Int, y: Int): Key? {
        if (y < 0 || x < 0) return null
        for (row in rows) {
            if (y < row.y + row.height) {
                for (key in row.keys) if (key.width > 0 && x < key.x + key.width) return key
                return null
            }
        }
        return null
    }

    fun onDraw(canvas: Canvas) {
        for (row in rows) row.onDraw(canvas)
    }

    // ---------- editing ----------

    fun addRow(index: Int = 0): Row {
        val node = FlexNode()
        val i = index.coerceIn(0, rows.size)
        childs.add(i, node)
        return Row(this, node).also { rows.add(i, it); relayout() }
    }

    fun remove(row: Row) {
        val i = rows.indexOf(row)
        if (i < 0) return
        rows.removeAt(i)
        childs.removeAt(i)
        relayout()
    }

    fun addKey(row: Row, index: Int = 0): Key {
        val node = FlexNode()
        val i = index.coerceIn(0, row.keys.size)
        row.childs.add(i, node)
        return Key(c, row, 0, row.y, node).also { row.keys.add(i, it); relayout() }
    }

    /**
     * A row. Children are keys; params (height, colors, ...) apply to all its keys.
     */
    class Row(val layout: KeybLayout, val options: FlexNode) : FlexNode(options, layout) {
        val keys = ArrayList<Key>()
        var y = 0
        var height = 0

        /** The row's own height multiplier (not inherited: the layout-level value is already in refSize). */
        fun ownHeight(): Float = (params[ROW_HEIGHT] as? String)?.toFloatOrNull() ?: 1f

        fun layoutKeys(viewWidth: Int) {
            var total = 0f
            for (key in keys) if (!key.bool(KEY_DO_NOT_SHOW)) total += key.float(KEY_WIDTH, 1f)
            // Accumulate in float so rounding errors don't leave a gap at the right edge.
            var acc = 0f
            var x = 0
            for (key in keys) {
                key.x = x
                key.y = y
                if (key.bool(KEY_DO_NOT_SHOW) || total <= 0f) {
                    key.width = 0
                    continue
                }
                acc += viewWidth * key.float(KEY_WIDTH, 1f) / total
                val right = acc.roundToInt()
                key.width = right - x
                x = right
            }
        }

        fun add(key: Key, index: Int = keys.size) {
            val i = index.coerceIn(0, keys.size)
            keys.add(i, key)
            childs.add(i, key)
        }

        fun remove(key: Key) {
            val i = keys.indexOf(key)
            if (i < 0) return
            keys.removeAt(i)
            childs.removeAt(i)
        }

        fun onDraw(canvas: Canvas) {
            for (key in keys) key.onDraw(canvas)
        }
    }
}

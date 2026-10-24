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
class KeybLayout(val c: Keyboard, glob: FlexNode, private val hasNumpad: Boolean = false) : FlexNode(glob, Theme) {
    val rows = ArrayList<Row>()
    val sideRows = ArrayList<Row>()
    val keys: List<Key> get() = (rows + sideRows).flatMap { it.keys }

    var width = 0
        private set
    var height = 1
        private set
    var loaded = false
        private set
    var rtl = false
        private set
    var lastModified = 0L

    private var measuredWidth = -1
    private var measuredPortrait = true
    private var measuredRefSize = -1f
    private var measuredBottomPad = -1
    private var measuredGap = -1
    private var measuredSide = -1

    init {
        try {
            for (i in 0 until childCount()) {
                val rowNode = this[i]
                val row = Row(this, rowNode)
                rows.add(row)
                for (k in 0 until rowNode.childCount()) row.keys.add(Key(c, row, 0, 0, rowNode[k]))
            }
            loaded = rows.isNotEmpty()
            rtl = bool("rtl") || rows.any { row -> row.keys.any { key ->
                key.str(KEY_KEY).any { ch -> Character.getDirectionality(ch) in listOf(
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) }
            } }
            if (hasNumpad) for (node in Numpad.rows(Settings.bool(SETTING_INVERT_NUMPAD, false))) {
                val row = Row(this, node)
                for (key in node.childs.filterIsInstance<FlexNode>()) row.keys.add(Key(c, row, 0, 0, key))
                sideRows.add(row)
            }
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
        val bottomPad = Settings.num(SETTING_BOTTOM_PADDING, 0)
        val mode = Settings.num(SETTING_SIDE_NUMPAD, 0)
        val visible = hasNumpad && (mode in 1..2 || (mode == 3 && !portrait))
        val sideWidth = if (visible) viewWidth * Settings.num(SETTING_NUMPAD_WIDTH, 25).coerceIn(15, 45) / 100 else 0
        val left = visible && mode == 2
        val mainWidth = viewWidth - sideWidth
        val gap = splitGap(mainWidth)
        val sideSignature = if (left) -sideWidth else sideWidth
        if (!force && viewWidth == measuredWidth && portrait == measuredPortrait && refSize == measuredRefSize &&
            bottomPad == measuredBottomPad && gap == measuredGap && sideSignature == measuredSide) return
        measuredWidth = viewWidth
        measuredPortrait = portrait
        measuredRefSize = refSize
        measuredBottomPad = bottomPad
        measuredGap = gap
        measuredSide = sideSignature
        width = viewWidth

        var y = 0
        for (row in rows) {
            row.y = y
            row.height = (refSize * row.ownHeight()).toInt()
            row.layoutKeys(mainWidth, gap)
            if (left) for (key in row.keys) key.x += sideWidth
            y += row.height
        }
        for ((i, row) in sideRows.withIndex()) {
            row.y = y * i / sideRows.size
            row.height = y * (i + 1) / sideRows.size - row.y
            row.layoutKeys(sideWidth)
            for (key in row.keys) key.x += if (left) 0 else mainWidth
        }
        val bottomPadPx = if (bottomPad > 0) (bottomPad * c.ctx.resources.displayMetrics.density).roundToInt() else 0
        height = (y + bottomPadPx).coerceAtLeast(1)
    }

    /**
     * Width of the empty middle when the keyboard is split, 0 when it isn't: never in the layout
     * editor; on wide screens (keyboard at least 600dp wide, e.g. unfolded foldables, tablets,
     * phones in landscape) or always, depending on the `split` setting.
     */
    private fun splitGap(viewWidth: Int): Int {
        if (c.isEditor) return 0
        val mode = Settings.num(SETTING_SPLIT, 1)
        val wide = viewWidth / c.ctx.resources.displayMetrics.density >= 600f
        if (mode == 0 || (mode == 1 && !wide)) return 0
        return (viewWidth * Settings.num(SETTING_SPLIT_GAP, 25).coerceIn(5, 60) / 100f).roundToInt()
    }

    /** Recomputes geometry after an edit (rows/keys added, removed, resized). */
    fun relayout() = measure(if (measuredWidth > 0) measuredWidth else c.ctx.resources.displayMetrics.widthPixels, measuredPortrait, true)

    fun getKey(x: Int, y: Int): Key? {
        if (y < 0 || x < 0) return null
        for (row in sideRows) if (y >= row.y && y < row.y + row.height) {
            row.keys.firstOrNull { it.width > 0 && x >= it.x && x < it.x + it.width }?.let { return it }
        }
        for (row in rows) {
            if (y < row.y + row.height) {
                // Keys left of the split gap end before it, so a touch in the gap hits nothing.
                for (key in row.keys) if (key.width > 0 && x < key.x + key.width) return if (x >= key.x) key else null
                return null
            }
        }
        return null
    }

    fun onDraw(canvas: Canvas) {
        // Only the keyboard's own area: the IME frame doesn't clip its children (popups reach
        // above the keyboard), so drawColor() would also paint the transparent head-room.
        val p = c.paint
        val old = p.style
        p.style = android.graphics.Paint.Style.FILL
        p.color = c.color(COLOR_KEYBOARD_BACKGROUND, this)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.style = old
        for (row in rows) row.onDraw(canvas)
        for (row in sideRows) if (row.keys.any { it.width > 0 }) row.onDraw(canvas)
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

        /**
         * Places the keys across [viewWidth]. With a [gap] (split keyboard) the row is laid out in
         * `viewWidth - gap` and the keys right of the key boundary nearest the middle move right by
         * [gap]; a wide key lying across the middle (the space bar) is stretched over the gap instead.
         */
        fun layoutKeys(viewWidth: Int, gap: Int = 0) {
            val w = viewWidth - gap
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
                acc += w * key.float(KEY_WIDTH, 1f) / total
                val right = acc.roundToInt()
                key.width = right - x
                x = right
            }
            if (gap <= 0) return

            val mid = w / 2f
            val visible = keys.filter { it.width > 0 }
            val across = visible.firstOrNull { k ->
                val l = k.x.toFloat()
                val r = (k.x + k.width).toFloat()
                k.float(KEY_WIDTH, 1f) >= 2f && l < mid && r > mid && minOf(mid - l, r - mid) >= k.width * 0.25f
            }
            if (across != null) {
                val start = across.x
                across.width += gap
                for (k in keys) if (k !== across && k.x > start) k.x += gap
                return
            }
            // Split at the key boundary closest to the middle.
            val boundary = visible.map { it.x + it.width }.minByOrNull { kotlin.math.abs(it - mid) } ?: return
            for (k in keys) if (k.x >= boundary) k.x += gap
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

package x.vladgba.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser
import kotlin.math.min

/**
 * Draws a layout file as a miniature keyboard (rows, key widths, labels, swipe hints) in the
 * current theme's colors. Cheap: it only reads the parsed tree, no Keyboard instance needed.
 * [overlay] can paint on top (used by the touch heatmap); [keys] holds the key rectangles.
 */
@SuppressLint("ViewConstructor")
internal class LayoutPreview(ctx: Context, private val rowHeightDp: Int = 26) : View(ctx) {
    class KeyRect(val row: Int, val index: Int, val rect: RectF, val label: String)

    var layout: FlexNode = FlexNode()
        set(v) { field = v; requestLayout(); invalidate() }

    var overlay: ((Canvas, List<KeyRect>) -> Unit)? = null

    val keys = ArrayList<KeyRect>()
    private val theme: FlexNode = currentTheme(ctx)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private fun color(key: String) = ThemeColors.color(theme, key)

    private fun rows(): List<FlexNode> = layout.childs.filterIsInstance<FlexNode>()

    private fun rowUnits(row: FlexNode) = (row.params[ROW_HEIGHT] as? String)?.toFloatOrNull() ?: 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val units = rows().sumOf { rowUnits(it).toDouble() }.toFloat().coerceAtLeast(1f)
        setMeasuredDimension(w, (units * context.dp(rowHeightDp)).toInt() + context.dp(4))
    }

    override fun onDraw(canvas: Canvas) {
        keys.clear()
        p.color = color(COLOR_KEYBOARD_BACKGROUND)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        val rows = rows()
        val units = rows.sumOf { rowUnits(it).toDouble() }.toFloat().coerceAtLeast(1f)
        val pad = context.dp(2).toFloat()
        val unitH = (height - pad * 2) / units
        var y = pad
        for ((ri, row) in rows.withIndex()) {
            val h = unitH * rowUnits(row)
            val keyNodes = row.childs.filterIsInstance<FlexNode>()
            val total = keyNodes.sumOf { if (hidden(it, row)) 0.0 else width(it, row).toDouble() }.toFloat()
            var x = pad
            for ((ki, k) in keyNodes.withIndex()) {
                if (hidden(k, row) || total <= 0f) continue
                val w = (width - pad * 2) * width(k, row) / total
                val label = Labels.resolve(context, k.params[KEY_KEY] as? String ?: "")
                val r = RectF(x, y, x + w, y + h)
                keys += KeyRect(ri, ki, r, label)
                if ((k.params[KEY_VISIBLE] as? String)?.trim() != "0") drawKey(canvas, k, r, label)
                x += w
            }
            y += h
        }
        overlay?.invoke(canvas, keys)
    }

    private fun prop(k: FlexNode, row: FlexNode, name: String) =
        (k.params[name] as? String) ?: (row.params[name] as? String) ?: (layout.params[name] as? String)

    private fun hidden(k: FlexNode, row: FlexNode) = prop(k, row, KEY_DO_NOT_SHOW)?.trim() in setOf("1", "true")

    private fun width(k: FlexNode, row: FlexNode) = prop(k, row, KEY_WIDTH)?.toFloatOrNull() ?: 1f

    private fun drawKey(c: Canvas, k: FlexNode, r: RectF, label: String) {
        val inset = min(r.height(), r.width()) / 14f + 0.5f
        val radius = r.height() / 6f
        val body = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        p.color = color(COLOR_KEY_BORDER)
        c.drawRoundRect(RectF(body.left, body.top, body.right, body.bottom + inset), radius, radius, p)
        p.color = color(if ((k.params[KEY_MODE] as? String) == KEY_MODE_META) COLOR_KEY_BACKGROUND_META else COLOR_KEY_BACKGROUND)
        c.drawRoundRect(body, radius, radius, p)

        // top-right swipe hint (position 2), like the number row hints on letter keys
        val hint = k.childs.getOrNull(2) as? String
        if (!hint.isNullOrBlank()) {
            p.color = color(COLOR_KEY_SECONDARY)
            p.textSize = r.height() / 4.5f
            c.drawText(hint.take(2), body.right - body.width() / 5, body.top + p.textSize, p)
        }
        if (label.isNotBlank()) {
            p.color = color(COLOR_TEXT_PRIMARY)
            p.textSize = min(r.height() / 2.3f, body.width() / (label.length.coerceAtLeast(1) * 0.62f))
            c.drawText(label, r.centerX(), r.centerY() + (p.textSize - p.descent()) / 2.2f, p)
        }
    }

    companion object {
        /** The theme the keyboard currently uses (day or night, following the system). */
        fun currentTheme(ctx: Context): FlexNode {
            Settings.loadVars(ctx)
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val name = Settings.str(if (night) THEME_NIGHT else THEME_DAY)
            return Theme.upgrade(FlexParser.parse(PFile(ctx, name, THEME_EXT).read()))
        }
    }
}

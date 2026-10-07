package x.vladgba.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import kotlin.math.min

/**
 * Draws a small sample keyboard with a theme's colors, the same way the real keys are drawn:
 * normal, pressed, active and locked modifier keys, swipe hints and the swipe popup.
 * Tapping a part of the preview reports which color it uses ([onPick]).
 */
@SuppressLint("ViewConstructor")
internal class ThemePreview(ctx: Context, private val compact: Boolean = false) : View(ctx) {
    /** Color lookup by theme key. */
    var colors: (String) -> Int = { 0xff888888.toInt() }
        set(v) { field = v; invalidate() }

    /** Called with the theme key of the tapped element. */
    var onPick: ((String) -> Unit)? = null

    /** Theme key whose elements get an outline (to show where a color is used). */
    var highlight: String? = null
        set(v) { field = v; invalidate() }

    private class Region(val rect: RectF, val key: String)
    private val regions = ArrayList<Region>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(2).toFloat()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(5).toFloat(), ctx.dp(3).toFloat()), 0f)
    }

    private enum class Kind { NORMAL, PRESSED, META, LOCKED }
    private class K(val label: String, val w: Float, val kind: Kind = Kind.NORMAL, val hints: Map<Int, String> = emptyMap())

    private val rows = if (compact) listOf(
        listOf(K("q", 1f, hints = mapOf(2 to "1")), K("w", 1f, Kind.PRESSED, mapOf(2 to "2")), K("e", 1f, hints = mapOf(2 to "3")),
            K("⇧", 1.2f, Kind.META), K("Ctrl", 1.2f, Kind.LOCKED), K("⏎", 1.2f)),
    ) else listOf(
        listOf(K("q", 1f, hints = mapOf(2 to "1", 5 to "%")), K("w", 1f, hints = mapOf(2 to "2")), K("e", 1f, Kind.PRESSED, mapOf(2 to "3", 7 to "é")),
            K("r", 1f, hints = mapOf(2 to "4")), K("t", 1f, hints = mapOf(2 to "5"))),
        listOf(K("⇧", 1.3f, Kind.META), K("a", 1f, hints = mapOf(0 to "@")), K("s", 1f, hints = mapOf(0 to "$")), K("d", 1f), K("Ctrl", 1.3f, Kind.LOCKED)),
        listOf(K("123", 1.3f), K(",", 1f), K(ctx.getString(R.string.kb_space_name), 2.4f), K("⏎", 1.3f)),
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rowH = context.dp(if (compact) 40 else 46)
        setMeasuredDimension(w, rowH * rows.size + context.dp(if (compact) 4 else 8))
    }

    override fun onDraw(canvas: Canvas) {
        regions.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val kbW = if (compact) w else w * 0.70f
        drawKeyboard(canvas, 0f, 0f, kbW, h)
        if (!compact) drawPopupDemo(canvas, kbW, 0f, w - kbW, h)
        highlight?.let { key ->
            outline.color = 0xffff3d00.toInt()
            for (r in regions) if (r.key == key) canvas.drawRoundRect(r.rect, 6f, 6f, outline)
        }
    }

    private fun region(l: Float, t: Float, r: Float, b: Float, key: String) = regions.add(Region(RectF(l, t, r, b), key))

    private fun keyGeometry(rowH: Float): Triple<Float, Float, Float> {
        val padding = min(Settings.float(KEY_PADDING, rowH / 16f), rowH / 6f) / 2f + 1f
        val radius = min(Settings.float(KEY_BORDER_RADIUS, rowH / 6f), rowH / 3f) / 1.5f
        val shadow = rowH / 30f
        return Triple(padding, radius, shadow)
    }

    private fun drawKeyboard(c: Canvas, x0: Float, y0: Float, w: Float, h: Float) {
        p.color = colors(COLOR_KEYBOARD_BACKGROUND)
        c.drawRect(x0, y0, x0 + w, y0 + h, p)
        region(x0, y0, x0 + w, y0 + h, COLOR_KEYBOARD_BACKGROUND)

        val pad = context.dp(4).toFloat()
        val rowH = (h - pad) / rows.size
        val (padding, radius, shadow) = keyGeometry(rowH)
        var y = y0 + pad / 2
        for (row in rows) {
            val units = row.sumOf { it.w.toDouble() }.toFloat()
            var x = x0 + pad / 2
            val unitW = (w - pad) / units
            for (k in row) {
                drawKey(c, k, x, y, unitW * k.w, rowH, padding, radius, shadow)
                x += unitW * k.w
            }
            y += rowH
        }
    }

    private fun drawKey(c: Canvas, k: K, x: Float, y: Float, w: Float, h: Float, padding: Float, radius: Float, shadow: Float) {
        // border / shadow (as in Key.drawSelf)
        p.color = colors(COLOR_KEY_BORDER)
        val border = RectF(x + padding - shadow / 3, y + padding, x + w - padding + shadow / 3, y + h - padding + shadow)
        c.drawRoundRect(border, radius, radius, p)
        region(border.left, border.bottom - shadow - context.dp(3), border.right, border.bottom + context.dp(3), COLOR_KEY_BORDER)

        val bodyKey = when (k.kind) {
            Kind.NORMAL -> COLOR_KEY_BACKGROUND
            Kind.PRESSED -> COLOR_KEY_PRESSED_BACKGROUND
            Kind.META -> COLOR_KEY_BACKGROUND_META
            Kind.LOCKED -> COLOR_KEY_PRESSED_MOD_BACKGROUND
        }
        p.color = colors(bodyKey)
        val body = RectF(x + padding, y + padding, x + w - padding, y + h - padding)
        c.drawRoundRect(body, radius, radius, p)
        region(body.left, body.top, body.right, body.bottom - context.dp(3), bodyKey)

        // swipe hints (positions as in Key.drawExtChars)
        if (k.hints.isNotEmpty()) {
            p.color = colors(COLOR_KEY_SECONDARY)
            p.textSize = h / 4.2f
            val xs = floatArrayOf(w / 5, w / 2, w - w / 5, w / 5, w - w / 5, w / 5, w / 2, w - w / 5)
            val ys = floatArrayOf(h / 5, h / 5, h / 5, h / 2, h / 2, h - h / 5, h - h / 5, h - h / 5)
            for ((i, s) in k.hints) {
                val tx = x + xs[i]
                val ty = y + ys[i] + (p.textSize - p.descent()) / 2
                c.drawText(s, tx, ty, p)
                region(tx - p.textSize / 1.5f, ty - p.textSize, tx + p.textSize / 1.5f, ty + p.descent(), COLOR_KEY_SECONDARY)
            }
        }

        p.color = colors(COLOR_TEXT_PRIMARY)
        p.textSize = if (k.label.length > 2) h / 3.4f else h / 2.3f
        val ty = y + (h + p.textSize - p.descent()) / 2
        c.drawText(k.label, x + w / 2, ty, p)
        val tw = p.measureText(k.label) / 2 + context.dp(3)
        region(x + w / 2 - tw, ty - p.textSize, x + w / 2 + tw, ty + p.descent(), COLOR_TEXT_PRIMARY)

        if (k.kind == Kind.LOCKED) {
            val m = w / 8f
            c.drawRect(x + w / 2 - m, body.bottom - padding - 4, x + w / 2 + m, body.bottom - padding, p)
        }
    }

    /** The popup shown while swiping on a key, over the dimmed keyboard. */
    private fun drawPopupDemo(c: Canvas, x0: Float, y0: Float, w: Float, h: Float) {
        p.color = colors(COLOR_KEYBOARD_BACKGROUND)
        c.drawRect(x0, y0, x0 + w, y0 + h, p)
        p.color = colors(COLOR_KEY_SHADOW)
        c.drawRect(x0, y0, x0 + w, y0 + h, p)
        region(x0, y0, x0 + w, y0 + h, COLOR_KEY_SHADOW)

        val size = min(w - context.dp(10), h - context.dp(10))
        val left = x0 + (w - size) / 2
        val top = y0 + (h - size) / 2
        val cell = size / 3
        val radius = cell / 4
        p.color = colors(COLOR_KEY_BORDER)
        c.drawRoundRect(RectF(left - 2, top - 2, left + size + 2, top + size + 4), radius, radius, p)
        p.color = colors(COLOR_KEY_POPUP_BACKGROUND)
        val box = RectF(left, top, left + size, top + size)
        c.drawRoundRect(box, radius, radius, p)
        region(box.left, box.top, box.right, box.bottom, COLOR_KEY_POPUP_BACKGROUND)

        val chars = listOf("1", "2", "3", "!", "e", "?", "é", "è", "ê")
        val selected = 1
        p.textSize = cell / 1.8f
        for ((i, s) in chars.withIndex()) {
            val cx = left + cell * (i % 3) + cell / 2
            val cy = top + cell * (i / 3) + cell / 2
            if (i == selected) {
                p.color = colors(COLOR_KEY_POPUP_SELECTED)
                c.drawCircle(cx, cy, cell * 0.48f, p)
                region(cx - cell / 2, cy - cell / 2, cx + cell / 2, cy + cell / 2, COLOR_KEY_POPUP_SELECTED)
            }
            p.color = colors(COLOR_TEXT_PREVIEW)
            val ty = cy + (p.textSize - p.descent()) / 2
            c.drawText(s, cx, ty, p)
            region(cx - p.textSize / 2, ty - p.textSize, cx + p.textSize / 2, ty + p.descent(), COLOR_TEXT_PREVIEW)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pick = onPick ?: return false
        if (e.action == MotionEvent.ACTION_DOWN) return true
        if (e.action != MotionEvent.ACTION_UP) return true
        // Last drawn element wins: text over key body over keyboard background.
        regions.lastOrNull { it.rect.contains(e.x, e.y) }?.let { pick(it.key) }
        return true
    }
}

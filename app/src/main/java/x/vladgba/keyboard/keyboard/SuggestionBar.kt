package x.vladgba.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import x.vladgba.keyboard.core.*

/**
 * Word suggestions above the keys: three slots, the best one in the middle (bold). Tapping a slot
 * replaces the word being typed with it. Colors come from the keyboard theme.
 */
@SuppressLint("ViewConstructor")
class SuggestionBar(ctx: Context, private val kb: Keyboard) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var pressedSlot = -1

    /** Slot (0 left, 1 middle, 2 right) → rank of the suggestion shown there (0 = best). */
    private val rankAt = intArrayOf(1, 0, 2)

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        val node = kb.currentLayout
        // Only the bar's own area: the IME frame doesn't clip its children, drawColor() would cover the keys.
        paint.color = kb.color(COLOR_KEYBOARD_BACKGROUND, node)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val slotW = width / 3f
        val words = kb.suggestions
        paint.textSize = height * 0.42f
        for (slot in 0..2) {
            val left = slot * slotW
            if (slot == pressedSlot) {
                paint.color = kb.color(COLOR_KEY_PRESSED_BACKGROUND, node)
                canvas.drawRect(left, 0f, left + slotW, height.toFloat(), paint)
            }
            if (slot > 0) {
                paint.color = kb.color(COLOR_KEY_SECONDARY, node)
                canvas.drawRect(left - 0.5f, height * 0.25f, left + 0.5f, height * 0.75f, paint)
            }
            val word = words.getOrNull(rankAt[slot]) ?: continue
            paint.color = kb.color(COLOR_TEXT_PRIMARY, node)
            paint.typeface = if (rankAt[slot] == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val text = TextUtils.ellipsize(word, android.text.TextPaint(paint), slotW * 0.9f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, left + slotW / 2f, height / 2f + (paint.textSize - paint.descent()) / 2f, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val slot = (e.x / (width / 3f)).toInt().coerceIn(0, 2)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedSlot = slot
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val was = pressedSlot
                pressedSlot = -1
                invalidate()
                if (was == slot) kb.acceptSuggestion(rankAt[slot])
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedSlot = -1
                invalidate()
            }
        }
        return true
    }
}

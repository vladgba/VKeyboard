package x.vladgba.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/**
 * What the input method shows: the keyboard at the bottom plus transparent head-room above it,
 * and an overlay on top that draws key popups. A popup of a top-row key can therefore reach
 * above the keyboard, over the app, instead of being squeezed inside the keyboard.
 *
 * The head-room never takes touches and never resizes the app: [KeybWrapper.onComputeInsets]
 * reports only the keyboard itself as content and touchable area.
 */
@SuppressLint("ViewConstructor")
class ImeFrame(ctx: Context, private val kb: Keyboard) : ViewGroup(ctx) {
    val keyboardView: KeyboardView = kb.view
    private val overlay = Overlay(ctx)

    /** Space reserved above the keyboard, in px. */
    var headroom = 0
        private set

    init {
        (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
        addView(keyboardView)
        addView(overlay)
        keyboardView.overlay = overlay
        setWillNotDraw(true)
        clipChildren = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboardView.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val w = keyboardView.measuredWidth
        val h = keyboardView.measuredHeight
        headroom = kb.popupHeadroom()
        overlay.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h + headroom, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h + headroom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        keyboardView.layout(0, headroom, w, headroom + keyboardView.measuredHeight)
        overlay.layout(0, 0, w, b - t)
    }

    /** Draws popups in the keyboard's coordinates, shifted down by the head-room. Takes no touches. */
    inner class Overlay(ctx: Context) : View(ctx) {
        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(0f, headroom.toFloat())
            kb.drawPopups(canvas, keyboardView.width, keyboardView.height, headroom)
            canvas.restore()
        }
    }
}

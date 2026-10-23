package x.vladgba.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import x.vladgba.keyboard.core.COLOR_KEYBOARD_BACKGROUND
import x.vladgba.keyboard.core.SETTING_EDGE_GESTURE_GUARD
import x.vladgba.keyboard.core.Settings

/**
 * What the input method shows: the keyboard at the bottom plus transparent head-room above it,
 * and an overlay on top that draws key popups. A popup of a top-row key can therefore reach
 * above the keyboard, over the app, instead of being squeezed inside the keyboard.
 *
 * The head-room never takes touches and never resizes the app: [KeybWrapper.onComputeInsets]
 * reports only the keyboard itself as content and touchable area.
 *
 * Below the keyboard sits a strip as tall as the navigation bar: since Android 15 the input
 * method window is drawn edge-to-edge, so without it the bottom row hides under the nav buttons.
 */
@SuppressLint("ViewConstructor")
class ImeFrame(ctx: Context, private val kb: Keyboard) : ViewGroup(ctx) {
    val keyboardView: KeyboardView = kb.view
    private val overlay = Overlay(ctx)

    /** Space reserved above the keyboard, in px. */
    var headroom = 0
        private set

    /** Clipboard history shown in place of the keys, or null. */
    private var drawer: ClipDrawer? = null

    /** Shows the clipboard history in place of the keys (same size). */
    fun showClipboard() {
        if (drawer != null) {
            drawer?.refresh()
            return
        }
        drawer = ClipDrawer(context, kb) { hideDrawer() }.also { addView(it) }
        keyboardView.visibility = INVISIBLE
        requestLayout()
    }

    /** Back to the keys. */
    fun hideDrawer() {
        val d = drawer ?: return
        removeView(d)
        drawer = null
        keyboardView.visibility = VISIBLE
        requestLayout()
    }

    /** Space reserved below the keyboard for the navigation bar, in px. */
    private var navInset = 0

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
        drawer?.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h + headroom + navInset)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
        if (bottom != navInset) {
            navInset = bottom
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (navInset > 0) {
            val p = kb.paint
            p.color = kb.color(COLOR_KEYBOARD_BACKGROUND, kb.currentLayout)
            val top = (headroom + keyboardView.height).toFloat()
            val old = p.style
            p.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(0f, top, width.toFloat(), top + navInset, p)
            p.style = old
        }
        super.dispatchDraw(canvas)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        keyboardView.layout(0, headroom, w, headroom + keyboardView.measuredHeight)
        overlay.layout(0, 0, w, b - t)
        drawer?.layout(0, headroom, w, headroom + keyboardView.measuredHeight)
        updateGestureExclusion(w)
    }

    /**
     * Gesture navigation (Android 10+): a swipe that starts at the screen edge over the keyboard
     * (e.g. sliding along the space bar from the side) goes to the keyboard instead of
     * triggering Back. The system honours at most 200dp of height per edge, counted from the bottom.
     */
    private fun updateGestureExclusion(w: Int) {
        if (Build.VERSION.SDK_INT < 29) return
        systemGestureExclusionRects =
            if (Settings.bool(SETTING_EDGE_GESTURE_GUARD, true)) listOf(Rect(0, headroom, w, headroom + keyboardView.measuredHeight))
            else emptyList()
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

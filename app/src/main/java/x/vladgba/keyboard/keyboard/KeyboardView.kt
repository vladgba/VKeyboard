package x.vladgba.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View

/** The keyboard surface. All logic is delegated to [Keyboard]. */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val kb: Keyboard) : View(ctx) {
    /** Set inside the input method: popups are drawn there, so they can leave the keyboard. */
    var overlay: View? = null

    override fun invalidate() {
        super.invalidate()
        overlay?.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val specWidth = MeasureSpec.getSize(widthMeasureSpec)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED || specWidth == 0)
            resources.displayMetrics.widthPixels else specWidth
        kb.currentLayout.measure(width, kb.isPortrait)
        setMeasuredDimension(width, kb.currentLayout.height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != kb.currentLayout.width) {
            kb.currentLayout.measure(w, kb.isPortrait)
            if (h != kb.currentLayout.height) requestLayout()
        }
    }

    override fun onDraw(canvas: Canvas) = kb.draw(canvas, width, height, popups = overlay == null)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent) = kb.onTouch(me)

    override fun onConfigurationChanged(cfg: Configuration) {
        super.onConfigurationChanged(cfg)
        kb.onConfigurationChanged(cfg)
    }
}

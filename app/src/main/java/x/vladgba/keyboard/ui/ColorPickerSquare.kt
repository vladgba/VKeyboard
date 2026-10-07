package x.vladgba.keyboard.ui



import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Shader.TileMode
import android.util.AttributeSet
import android.view.View

class ColorPickerSquare : View {
    private var paint: Paint? = null
    private var linGrad: Shader? = null
    val color = floatArrayOf(1f, 1f, 1f)

    constructor(c: Context, attr: AttributeSet) : super(c, attr)
    constructor(c: Context, attr: AttributeSet, def: Int) : super(c, attr, def)

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (paint == null) {
            paint = Paint()
            linGrad = LinearGradient(0f, 0f, 0f, this.measuredHeight.toFloat(), -0x1, -0x1000000, TileMode.CLAMP)
        }

        paint!!.setShader(
            ComposeShader(
                linGrad!!, LinearGradient(
                    0f, 0f, this.measuredWidth.toFloat(), 0f, -0x1, Color.HSVToColor(color), TileMode.CLAMP
                ), PorterDuff.Mode.MULTIPLY
            )
        )

        canvas.drawRect(0f, 0f, this.measuredWidth.toFloat(), this.measuredHeight.toFloat(), paint!!)
    }

    fun setHue(hue: Float) {
        color[0] = hue
        invalidate()
    }
}

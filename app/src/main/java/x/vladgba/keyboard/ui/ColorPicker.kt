package x.vladgba.keyboard.ui

import x.vladgba.keyboard.R

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
/**
 * HSV color picker with alpha. Optional: [onChange] reports every change live (for previews),
 * [title], and [atBottom] places the dialog at the bottom so content above it stays visible.
 * Also has a hex field and quick swatches.
 */
class ColorPicker(
    context: Context, inputColor: Int, private val adjustAlpha: Boolean, private val listener: ColorPickerListener,
    private val onChange: ((Int) -> Unit)? = null, title: CharSequence? = null, atBottom: Boolean = false,
) {
    interface ColorPickerListener {
        fun onCancel(dialog: ColorPicker)
        fun onOk(dialog: ColorPicker, color: Int)
    }

    private val view: View = LayoutInflater.from(context).inflate(R.layout.picker_dialog, null)
    private val dialog: AlertDialog
    private val viewHue: View = view.findViewById(R.id.picker_view_hue)
    private val viewSatValue: ColorPickerSquare = view.findViewById(R.id.picker_view_saturation_brightness)
    private val viewCursor: ImageView = view.findViewById(R.id.picker_cursor)
    private val viewAlphaCursor: ImageView = view.findViewById(R.id.picker_cursor_alpha)
    private val viewOldColor: View = view.findViewById(R.id.picker_color_old)
    private val viewNewColor: View = view.findViewById(R.id.picker_color_new)
    private val viewAlphaOverlay: View = view.findViewById(R.id.picker_view_alpha_overlay)
    private val viewTarget: ImageView = view.findViewById(R.id.picker_target)
    private val viewAlphaCheckered: ImageView = view.findViewById(R.id.picker_view_alpha)
    private val viewContainer: ViewGroup = view.findViewById(R.id.view_container)
    private val currentColorHsv = FloatArray(3)
    private var alpha: Int
    private var hex: EditText? = null
    private var updatingHex = false

    fun generateColor() = alpha shl 24 or (Color.HSVToColor(currentColorHsv) and 0x00ffffff)
    fun show() = dialog.show()

    init {
        var initialColor = inputColor
        if (!adjustAlpha) initialColor = initialColor or (0xFF shl 24)
        Color.colorToHSV(initialColor, currentColorHsv)
        alpha = Color.alpha(initialColor)

        viewAlphaOverlay.visibility = if (adjustAlpha) View.VISIBLE else View.GONE
        viewAlphaCursor.visibility = if (adjustAlpha) View.VISIBLE else View.GONE
        viewAlphaCheckered.visibility = if (adjustAlpha) View.VISIBLE else View.GONE

        viewSatValue.setHue(currentColorHsv[0])
        viewOldColor.setBackgroundColor(initialColor)
        viewNewColor.setBackgroundColor(initialColor)
        viewHue.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                var y = event.y
                if (y < 0f) y = 0f
                if (y > viewHue.measuredHeight) y = viewHue.measuredHeight - 0.001f

                var correctedHue = 360f - 360f / viewHue.measuredHeight * y
                if (correctedHue == 360f) correctedHue = 0f
                currentColorHsv[0] = correctedHue

                viewSatValue.setHue(correctedHue)
                moveCursor()
                if (adjustAlpha) updateAlphaView()
                viewNewColor.setBackgroundColor(generateColor())
                changed()
                true
            } else false
        }

        if (adjustAlpha) viewAlphaCheckered.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                var y = event.y
                if (y < 0f) y = 0f
                if (y > viewAlphaCheckered.measuredHeight) y = viewAlphaCheckered.measuredHeight - 0.001f

                val a = (255f - 255f / viewAlphaCheckered.measuredHeight * y).roundToInt()
                alpha = a

                moveAlphaCursor()
                viewNewColor.setBackgroundColor(generateColor())
                changed()
                true
            } else false
        }

        viewSatValue.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE || event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
                var x = event.x
                var y = event.y
                if (x < 0f) x = 0f
                if (x > viewSatValue.measuredWidth) x = viewSatValue.measuredWidth.toFloat()
                if (y < 0f) y = 0f
                if (y > viewSatValue.measuredHeight) y = viewSatValue.measuredHeight.toFloat()
                currentColorHsv[1] = 1f / viewSatValue.measuredWidth * x
                currentColorHsv[2] = 1f - 1f / viewSatValue.measuredHeight * y

                moveTarget()
                if (adjustAlpha) updateAlphaView()
                viewNewColor.setBackgroundColor(generateColor())
                changed()
                true
            } else false
        }

        val hexInput = EditText(context).apply {
            setSingleLine()
            textSize = 15f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "AARRGGBB"
        }
        hex = hexInput
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (updatingHex) return
                val t = s.toString().trim().removePrefix("#")
                if (t.length != 6 && t.length != 8) return
                val v = t.toLongOrNull(16) ?: return
                setColor(if (t.length == 6) (v or 0xff000000).toInt() else v.toInt(), fromHex = true)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val dp = context.resources.displayMetrics.density
        for (c in SWATCHES) swatches.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke((1 * dp).toInt(), 0x33000000)
            }
            contentDescription = "#" + Integer.toHexString(c).uppercase()
            setOnClickListener { setColor(if (adjustAlpha) c else c or (0xFF shl 24)) }
        }, LinearLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt()).apply { setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) })
        val hexRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "#"; textSize = 16f })
            addView(hexInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = (16 * dp).toInt()
            addView(view)
            addView(hexRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(side, 0, side, 0) })
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(side, (4 * dp).toInt(), side, (4 * dp).toInt())
                addView(swatches)
            })
        }
        updateHex()

        dialog = AlertDialog.Builder(context).setPositiveButton(android.R.string.ok) { _, _ ->
            listener.onOk(this@ColorPicker, generateColor())
        }.setNegativeButton(android.R.string.cancel) { _, _ ->
            listener.onCancel(this@ColorPicker)
        }.setOnCancelListener {
            listener.onCancel(this@ColorPicker)
        }.create()

        if (title != null) dialog.setTitle(title)
        dialog.setView(ScrollView(context).apply { addView(wrapper) }, 0, 0, 0, 0)
        if (atBottom) dialog.window?.let { w ->
            w.setGravity(Gravity.BOTTOM)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val vto = view.viewTreeObserver
        vto.addOnGlobalLayoutListener(object : OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                moveCursor()
                moveTarget()
                if (adjustAlpha) {
                    moveAlphaCursor()
                    updateAlphaView()
                }
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    private fun updateHex() {
        val h = hex ?: return
        updatingHex = true
        val c = generateColor()
        h.setText(if (adjustAlpha) String.format("%08X", c) else String.format("%06X", c and 0xffffff))
        updatingHex = false
    }

    private fun changed(fromHex: Boolean = false) {
        if (!fromHex) updateHex()
        onChange?.invoke(generateColor())
    }

    /** Moves all cursors to [color] (from the hex field or a swatch). */
    fun setColor(color: Int, fromHex: Boolean = false) {
        Color.colorToHSV(color, currentColorHsv)
        alpha = if (adjustAlpha) Color.alpha(color) else 0xFF
        viewSatValue.setHue(currentColorHsv[0])
        moveCursor()
        moveTarget()
        if (adjustAlpha) {
            moveAlphaCursor()
            updateAlphaView()
        }
        viewNewColor.setBackgroundColor(generateColor())
        changed(fromHex)
    }

    private fun moveCursor() {
        var y = viewHue.measuredHeight - currentColorHsv[0] * viewHue.measuredHeight / 360f
        if (y == viewHue.measuredHeight.toFloat()) y = 0f
        val layoutParams = viewCursor.layoutParams as RelativeLayout.LayoutParams
        layoutParams.leftMargin = (viewHue.left - viewContainer.paddingLeft)
        layoutParams.topMargin =
            (viewHue.top + y - floor((viewCursor.measuredHeight / 2f).toDouble()) - viewContainer.paddingTop).toInt()
        viewCursor.layoutParams = layoutParams
    }

    private fun moveTarget() {
        val x = currentColorHsv[1] * viewSatValue.measuredWidth
        val y = (1f - currentColorHsv[2]) * viewSatValue.measuredHeight
        val layoutParams = viewTarget.layoutParams as RelativeLayout.LayoutParams
        layoutParams.leftMargin =
            (viewSatValue.left + x - floor((viewTarget.measuredWidth / 2f).toDouble()) - viewContainer.paddingLeft).toInt()
        layoutParams.topMargin =
            (viewSatValue.top + y - floor((viewTarget.measuredHeight / 2f).toDouble()) - viewContainer.paddingTop).toInt()
        viewTarget.layoutParams = layoutParams
    }

    private fun moveAlphaCursor() {
        val measuredHeight = viewAlphaCheckered.measuredHeight
        val y = measuredHeight - alpha * measuredHeight / 255f
        val layoutParams = viewAlphaCursor.layoutParams as RelativeLayout.LayoutParams
        layoutParams.leftMargin = (viewAlphaCheckered.left - viewContainer.paddingLeft)
        layoutParams.topMargin =
            (viewAlphaCheckered.top + y - floor((viewAlphaCursor.measuredHeight / 2f).toDouble()) - viewContainer.paddingTop).toInt()
        viewAlphaCursor.layoutParams = layoutParams
    }

    private fun updateAlphaView() {
        val gd = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.HSVToColor(currentColorHsv), 0x0)
        )
        viewAlphaOverlay.background = gd
    }

    companion object {
        /** Quick picks: neutrals, then a hue wheel, then translucent blacks/whites for borders and shadows. */
        val SWATCHES = intArrayOf(
            0xffffffff.toInt(), 0xfff1f3f4.toInt(), 0xffdadce0.toInt(), 0xff9aa0a6.toInt(), 0xff5f6368.toInt(),
            0xff3c4043.toInt(), 0xff202124.toInt(), 0xff000000.toInt(),
            0xffd93025.toInt(), 0xffe8710a.toInt(), 0xfff9ab00.toInt(), 0xff1e8e3e.toInt(), 0xff12b5cb.toInt(),
            0xff1a73e8.toInt(), 0xff9334e6.toInt(), 0xffe52592.toInt(),
            0x22000000, 0x55000000, 0x88000000.toInt(), 0x33ffffff, 0x80ffffff.toInt(),
        )
    }
}

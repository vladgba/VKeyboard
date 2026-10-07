package x.vladgba.keyboard.keyboard

import android.content.ComponentName
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.keyboard.KeybLayout.Row
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.min

/**
 * One key of a layout. Shares its data with the layout tree, so editing it edits the layout file.
 *
 * Positional children (0..7) are the popup characters around the key:
 * ```
 *  0 1 2
 *  3 . 4
 *  5 6 7
 * ```
 */
class Key(private val c: Keyboard, var row: Row, var x: Int, var y: Int, opts: FlexNode) : FlexNode(opts, row) {

    enum class InputMode { LABEL, CODE, TEXT }

    enum class State {
        PRESSED, LONG_PRESSED, HOLD_REPEAT,
        MOVED, HARD_PRESSED, HARD_REPEAT,
        HOLD_MOVED, HOLD_HARD_PRESSED,
        RELEASED,
    }

    class Point(var x: Int, var y: Int)

    /** A recorded macro step: either a key event or committed text. */
    class Record(var keyText: String?) {
        private var keyIndex = 0
        private var keyMod = 0
        private var keyState = 0

        constructor(key: Int, mod: Int, state: Int) : this(null) {
            keyIndex = key
            keyMod = mod
            keyState = state
        }

        fun replay(kb: Keyboard) {
            if (keyText.isNullOrEmpty()) kb.keyModified(keyState, keyIndex, keyMod)
            else {
                SystemClock.sleep(50) // let the previous key event be processed first
                kb.onText(keyText!!)
            }
        }
    }

    var inputMode = InputMode.LABEL
        private set

    var width = 0
    val height get() = row.height

    var state = State.RELEASED
        private set

    val record = ArrayList<Record>()
    var recording = false

    /** Where the key is drawn while being dragged in the layout editor. */
    var floatingPos: Point? = null

    private var press = Point(0, 0)
    private var relative: Point? = null
    var charPos = 0
        private set

    val longPressRunnable = Runnable { longPress() }
    private val hardRepeatRunnable = Runnable { hardPressAction() }
    private val action = KeyAction(c)

    init {
        if (num(KEY_CODE) != 0) inputMode = InputMode.CODE
        if (str(KEY_TEXT).isNotEmpty()) inputMode = InputMode.TEXT
    }

    private val mode get() = str(KEY_MODE)
    val isMeta get() = mode == KEY_MODE_META

    // ======================= touch handling =======================

    fun press(curX: Int, curY: Int) {
        c.sounds.play(str(KEY_SOUND_PRESS))
        c.vibrate(this, KEY_VIBRATE_PRESS)
        state = State.PRESSED
        press = Point(curX, curY)
        charPos = 0

        if (isMeta && has(KEY_CODE) && has(KEY_MOD_META)) {
            c.metaPressed(this)
            c.toggleMeta(this)
            relative = null
            return
        }

        c.handler.postDelayed(longPressRunnable, num(SENSE_HOLD_PRESS, 300).toLong())
        relative = Point(curX, curY)
    }

    fun drag(curX: Int, curY: Int, me: MotionEvent, pointerIndex: Int) {
        if (isMeta) return
        if (bool(KEY_CLIPBOARD)) {
            charPos = getExtPos(curX, curY)
            return
        }
        if (state == State.HARD_PRESSED || state == State.HOLD_HARD_PRESSED || state == State.LONG_PRESSED) return
        hardPress(me, pointerIndex)
        if (relative == null) return
        joystick(curX, curY)
        procExtChars(curX, curY)
    }

    fun release(curX: Int, curY: Int, pid: Int) {
        stopTimers()
        c.sounds.play(str(KEY_SOUND_RELEASE))
        if (charPos == 0) c.vibrate(this, KEY_VIBRATE_RELEASE)

        if (isMeta) {
            // Held while other keys were pressed: behaves like a physical modifier and is released now.
            if (state == State.PRESSED) c.metaReleased(this)
            state = State.RELEASED
            return
        }

        when (state) {
            State.PRESSED -> {
                if (mode == KEY_MODE_RANDOM && this[KEY_RANDOM].childCount() > 0) {
                    val rnd = this[KEY_RANDOM]
                    c.onText(rnd.str((0 until rnd.childCount()).random()))
                } else if (bool(KEY_VOICE)) {
                    c.voice.toggle()
                } else if (has(KEY_MACRO) && str(KEY_MACRO).isNotBlank()) {
                    c.runMacro(str(KEY_MACRO).trim())
                } else if (has(KEY_COMBO) && c.sendCombo(str(KEY_COMBO), requireModifier = false)) {
                    // key combination sent
                } else if (!(recordAction(curX, curY) || clipboardAction() || caseAction() || langSwitch() || textInput())) {
                    c.onKey(code())
                }
            }
            State.MOVED -> prExtChars()
            State.HOLD_MOVED -> prExtChars(this[KEY_HOLD])
            else -> {}
        }

        state = State.RELEASED
        doActions()
        c.releaseOneShotMeta()
    }

    /** Touch was cancelled by the system: reset without producing input. */
    fun cancel() {
        stopTimers()
        state = State.RELEASED
        charPos = 0
    }

    private fun stopTimers() {
        c.handler.removeCallbacks(longPressRunnable)
        c.handler.removeCallbacks(hardRepeatRunnable)
    }

    // ======================= long / hard press =======================

    private fun longPress() {
        val holdStr = str(KEY_HOLD)
        val repeat = bool(KEY_HOLD_REPEAT)
        if (holdStr.isBlank() && !repeat) return
        if (repeat) c.handler.postDelayed(longPressRunnable, num(SENSE_HOLD_PRESS_REPEAT, 50).coerceAtLeast(10).toLong())

        state = when (state) {
            State.PRESSED -> State.LONG_PRESSED
            State.LONG_PRESSED -> State.HOLD_REPEAT
            else -> state
        }

        if (holdStr.isNotBlank()) emit(holdStr)
        else primaryAction()
        c.vibrate(this, KEY_VIBRATE_TICK)
    }

    private fun hardPress(me: MotionEvent, pointerIndex: Int) {
        if (state != State.PRESSED) return
        if (str(KEY_HARD_PRESS).isBlank()) return
        val sense = num(SENSE_HARD_PRESS)
        if (sense <= 0) return
        try {
            if ((me.getPressure(pointerIndex) * 1000).toInt() > sense) hardPressAction()
        } catch (_: Exception) {
        }
    }

    private fun hardPressAction() {
        state = when (state) {
            State.PRESSED -> State.HARD_PRESSED
            State.HARD_PRESSED -> State.HARD_REPEAT
            State.LONG_PRESSED -> State.HOLD_HARD_PRESSED
            else -> state
        }
        if (state == State.RELEASED) return
        c.handler.removeCallbacks(longPressRunnable)
        if (bool(KEY_HARD_REPEAT) && (state == State.HARD_PRESSED || state == State.HARD_REPEAT))
            c.handler.postDelayed(hardRepeatRunnable, num(SENSE_HOLD_PRESS, 300).toLong())
        emit(str(KEY_HARD_PRESS))
    }

    /**
     * Negative numbers are key codes (like `code`), `"ctrl+c"` is a key combination,
     * `"macro:name"` runs a macro, anything else is text.
     */
    private fun emit(value: String) {
        val n = if (value.startsWith("-")) value.toIntOrNull() else null
        if (n != null) c.onKey(n) else if (!c.runSpecial(value)) c.onText(value)
    }

    /** What a plain tap produces (used for hold-to-repeat). */
    private fun primaryAction() {
        if (inputMode == InputMode.TEXT) c.onText(text()) else c.onKey(code())
    }

    // ======================= popup chars / joystick =======================

    private fun getExtPos(x: Int, y: Int): Int {
        val ofs = num(SENSE_ADDITIONAL_CHARS)
        if (abs(press.x - x) < ofs && abs(press.y - y) < ofs) return 0
        if (charPos == 0) c.handler.removeCallbacks(longPressRunnable)
        val deg = Math.toDegrees(atan2((press.y - y).toDouble(), (press.x - x).toDouble()))
        return SECTORS[ceil(((if (deg < 0) 360.0 else 0.0) + deg + 22.5) / 45.0).toInt() - 1]
    }

    private fun procExtChars(curX: Int, curY: Int) {
        if (childCount() == 0) return
        relative?.let { it.x = curX; it.y = curY }
        val prev = charPos
        charPos = getExtPos(curX, curY)
        if (charPos != 0 && prev != charPos) {
            if (state == State.PRESSED) state = State.MOVED
            else if (state == State.LONG_PRESSED) state = State.HOLD_MOVED
            c.vibrate(this, KEY_VIBRATE_ADDITIONAL)
        }
    }

    private fun joystick(curX: Int, curY: Int) {
        if (mode != KEY_MODE_JOY) return
        val r = relative ?: return
        while (true) r.x = joyTick(curX, r.x, SENSE_HORIZONTAL_TICK, KEY_RIGHT_ACTION, KEY_LEFT_ACTION) ?: break
        while (true) r.y = joyTick(curY, r.y, SENSE_VERTICAL_TICK, KEY_BOTTOM_ACTION, KEY_TOP_ACTION) ?: break
    }

    private fun joyTick(cur: Int, initial: Int, senseName: String, pos: String, neg: String): Int? {
        val tick = num(senseName)
        if (tick < 1) return null
        return when {
            cur - tick > initial -> joyAction(pos).let { initial + tick }
            cur + tick < initial -> joyAction(neg).let { initial - tick }
            else -> null
        }
    }

    private fun joyAction(dir: String) {
        c.handler.removeCallbacks(longPressRunnable)
        if (state == State.PRESSED) state = State.MOVED
        val v = str(dir).trim()
        val n = FlexNode.parseInt(v)
        when {
            v.isEmpty() -> c.onKey(code())
            n != null -> c.onKey(n)
            else -> emit(v)
        }
        c.vibrate(this, KEY_VIBRATE_TICK)
    }

    private fun prExtChars(node: FlexNode = this): Boolean {
        if (node.childCount() == 0 || charPos < 1) return false
        val s = node.str(charPos - 1)
        if (s.isEmpty()) return true
        if (c.runSpecial(s)) return true
        if (s.length > 1 && s.startsWith("-")) s.toIntOrNull()?.let { c.onKey(it); return true }
        if (s.codePointCount(0, s.length) > 1) c.onText(s)
        else c.onKey(s.codePointAt(0))
        return true
    }

    // ======================= special actions =======================

    private fun recordAction(curX: Int, curY: Int): Boolean {
        if (str(KEY_RECORD).isBlank()) return false
        when (getExtPos(curX, curY)) {
            1, 3, 6, 8 -> record.clear()
            4, 5 -> c.recKey = this.also { recording = true }
            2, 7 -> c.recKey?.recording = false
            else -> for (r in record.toList()) r.replay(c)
        }
        return true
    }

    private fun clipboardAction(): Boolean {
        if (!bool(KEY_CLIPBOARD)) return false
        try {
            val ic = c.inputConnection ?: return true
            if (c.ctrlPressed()) {
                val tx = ic.getSelectedText(0)?.toString() ?: return true
                action.apply {
                    when {
                        c.shiftPressed() -> char2utfEscape(tx)
                        tx.contains("\\u") -> utf2char(tx)
                        tx.contains("0b") -> bin2char(tx)
                        tx.contains("0x") -> hex2char(tx)
                        tx.startsWith("0") -> oct2char(" $tx")
                        else -> dec2char(tx)
                    }
                }
                return true
            }
            if (charPos < 1) return true
            if (c.shiftPressed()) {
                // Shift + swipe: store the selection into this slot
                this[charPos - 1] = ic.getSelectedText(0)?.toString() ?: return true
            } else {
                val s = str(charPos - 1)
                if (s.isNotBlank()) c.onText(s)
            }
        } catch (e: Exception) {
            c.prStack(e)
        }
        return true
    }

    /** `"shift": "upperAll" | "lowerAll"` – changes case of the selection while Shift is active. */
    private fun caseAction(): Boolean {
        if (!c.shiftPressed()) return false
        val act = str(KEY_ACTION_ON_SHIFT)
        if (act != ACTION_UPPER_ALL && act != ACTION_LOWER_ALL) return false
        val tx = c.inputConnection?.getSelectedText(0)?.toString() ?: return true
        c.setText(if (act == ACTION_UPPER_ALL) tx.uppercase(Locale.getDefault()) else tx.lowercase(Locale.getDefault()))
        return true
    }

    private fun langSwitch(): Boolean {
        val target = str(KEY_LAYOUT)
        if (target.isBlank() || charPos != 0) return false
        c.goToLayout(target)
        return true
    }

    private fun textInput(): Boolean {
        if (inputMode != InputMode.TEXT) return false
        val t = text()
        c.onText(if (t.length == 1) c.getShifted(t[0].code, c.letterShift()).toChar().toString() else t)
        if (str(KEY_TEXT_CURSOR_OFFSET).isBlank()) return true
        // Move the cursor back: positive offset = from text start, negative = from text end
        val pos = num(KEY_TEXT_CURSOR_OFFSET)
        val steps = if (pos < 0) -pos else t.length - pos
        repeat(steps.coerceAtLeast(0)) { c.onKey(-KeyEvent.KEYCODE_DPAD_LEFT) }
        return true
    }

    private fun doActions() {
        val actions = this[KEY_ACTION]
        for (act in actions.childs) {
            if (act !is FlexNode || act.params.isEmpty()) continue
            try {
                when (act.str("type")) {
                    ACTION_SU -> action.suExec(act.str("cmd"))
                    ACTION_APP -> c.ctx.startActivity(
                        Intent(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                            component = ComponentName(act.str("pkg"), act.str("class"))
                        })
                }
            } catch (e: Exception) {
                c.prStack(e)
            }
        }
    }

    fun code(node: FlexNode = this): Int = when (inputMode) {
        InputMode.CODE -> node.num(KEY_CODE)
        InputMode.TEXT -> node.str(KEY_TEXT).let { if (it.isEmpty()) 0 else it.codePointAt(0) }
        InputMode.LABEL -> Labels.resolve(c.ctx, node.str(KEY_KEY)).let { if (it.isEmpty()) 0 else it.codePointAt(0) }
    }

    private fun text(node: FlexNode = this): String = when (inputMode) {
        InputMode.TEXT -> node.str(KEY_TEXT)
        InputMode.CODE -> node.num(KEY_CODE).let { if (it > 0) String(Character.toChars(it)) else "" }
        InputMode.LABEL -> Labels.resolve(c.ctx, node.str(KEY_KEY))
    }

    // ======================= drawing =======================

    fun onDraw(canvas: Canvas) {
        if (floatingPos != null) {
            // Being dragged in the layout editor: leave a dashed outline in its old place;
            // the key itself is drawn on top of everything by drawFloating().
            drawPlaceholder(canvas)
            return
        }
        canvas.save()
        drawSelf(canvas, x, y)
        canvas.restore()
    }

    /** The dragged key, lifted above the layout (layout editor). */
    fun drawFloating(canvas: Canvas) {
        val pos = floatingPos ?: return
        if (width <= 0) return
        val p = c.paint
        p.color = c.color(COLOR_KEY_SHADOW, this)
        val lift = height / 10f
        canvas.drawRoundRect(RectF(pos.x + lift / 2, pos.y + lift, pos.x + width + lift / 2, pos.y + height + lift), height / 6f, height / 6f, p)
        canvas.save()
        drawSelf(canvas, pos.x, pos.y)
        canvas.restore()
    }

    private fun drawPlaceholder(canvas: Canvas) {
        if (width <= 0) return
        val p = c.paint
        p.color = c.color(COLOR_KEYBOARD_BACKGROUND, this)
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(), p)
        val inset = height / 10f
        val old = p.style
        p.style = Paint.Style.STROKE
        p.strokeWidth = height / 30f + 1f
        p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(height / 10f, height / 14f), 0f)
        p.color = c.color(COLOR_KEY_SECONDARY, this)
        canvas.drawRoundRect(RectF(x + inset, y + inset, x + width - inset, y + height - inset), height / 6f, height / 6f, p)
        p.pathEffect = null
        p.style = old
    }

    private fun drawSelf(canvas: Canvas, x: Int, y: Int) {
        if (width <= 0 || bool(KEY_DO_NOT_SHOW)) return
        canvas.clipRect(x, y, x + width, y + height)
        val p = c.paint
        p.textAlign = Paint.Align.CENTER

        p.color = c.color(COLOR_KEYBOARD_BACKGROUND, this)
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat(), p)

        if (!bool(KEY_VISIBLE, true)) return

        val padding = float(KEY_PADDING, height / 16f)
        val radius = float(KEY_BORDER_RADIUS, height / 6f)
        val shadow = float(KEY_SHADOW, height / 36f)

        val margin = padding + radius + 2
        val hb = str(KEY_HIDE_BORDERS)
        val lpad = if (hb.contains("l")) -margin else 0f
        val rpad = if (hb.contains("r")) margin else 0f
        val tpad = if (hb.contains("t")) -margin else 0f
        val bpad = if (hb.contains("b")) margin else 0f

        // border / shadow
        p.color = c.color(COLOR_KEY_BORDER, this)
        canvas.drawRoundRect(
            RectF(x + lpad + padding - shadow / 3, y + tpad + padding, x + rpad + width - padding + shadow / 3, y + bpad + height - padding + shadow),
            radius, radius, p
        )

        // body
        val pressed = state != State.RELEASED && !c.isEditor
        p.color = when {
            isMeta && c.isMetaLocked(this) -> c.color(COLOR_KEY_PRESSED_MOD_BACKGROUND, this)
            isMeta && c.isMetaActive(this) -> c.color(COLOR_KEY_BACKGROUND_META, this)
            pressed || bool(KEY_SELECTED) -> c.color(COLOR_KEY_PRESSED_BACKGROUND, this)
            else -> c.color(COLOR_KEY_BACKGROUND, this)
        }
        val body = RectF(x + lpad + padding, y + tpad + padding, x + rpad + width - padding, y + bpad + height - padding)
        canvas.drawRoundRect(body, radius, radius, p)

        // popup chars hint
        p.textSize = height / float(KEY_TEXT_SIZE_SECONDARY, 5f)
        drawExtChars(canvas, p, x, y, width / 5f, width / 2f, width - width / 5f, height / 5f, height / 2f, height - height / 5f, false)

        // label
        p.color = c.color(COLOR_TEXT_PRIMARY, this)
        p.textSize = height / float(KEY_TEXT_SIZE_PRIMARY, 2.5f)
        val label = shiftedLabel()
        if (label.isNotBlank()) canvas.drawText(label, x + width / 2f, y + (height + p.textSize - p.descent()) / 2, p)

        // locked modifier marker
        if (isMeta && c.isMetaLocked(this) || bool(KEY_SELECTED)) {
            p.color = c.color(COLOR_TEXT_PRIMARY, this)
            val w = width / 4f
            canvas.drawRect(x + width / 2f - w / 2, body.bottom - padding - 4, x + width / 2f + w / 2, body.bottom - padding, p)
        }
    }

    /** Draws the 3x3 preview popup for the pressed key (on top of the whole keyboard). */
    fun drawPopup(canvas: Canvas, viewW: Int, viewH: Int, above: Int = 0) {
        val isClipboard = bool(KEY_CLIPBOARD)
        if (childCount() == 0 && !isClipboard) return
        canvas.save()
        val p = c.popupPaint
        p.textAlign = Paint.Align.CENTER
        p.textSize = height / float(KEY_TEXT_SIZE_PRIMARY, 2.5f)

        // dim the keyboard
        p.color = c.color(COLOR_KEY_SHADOW, this)
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), p)

        // Keep the popup on screen: sideways inside the keyboard, upwards it may use the [above]
        // head-room the input method keeps over the keyboard (drawn over the app).
        val dx = (-(x - width)).coerceAtLeast(0) - ((x + width * 2) - viewW).coerceAtLeast(0)
        val dy = (-(y - height) - above).coerceAtLeast(0) - ((y + height * 2) - viewH).coerceAtLeast(0)
        canvas.translate(dx.toFloat(), dy.toFloat())

        val x1 = -width / 2f
        val x2 = width / 2f
        val x3 = width * 1.5f
        val y1 = -height / 2f
        val y2 = height / 2f
        val y3 = height * 1.5f

        val showSlotText = isClipboard && charPos > 0 && str(charPos - 1).isNotEmpty()
        if (!showSlotText) canvas.clipRect(x - width, y - height, x + width * 2, y + height * 2)

        val padding = float(KEY_PADDING, height / 16f)
        val radius = float(KEY_BORDER_RADIUS, height / 16f)
        p.color = c.color(COLOR_KEY_BORDER, this)
        canvas.drawRoundRect(RectF(x + x1 * 2, y + y1 * 2, x - x1 + x3, y - y1 + y3), radius, radius, p)
        p.color = c.color(COLOR_KEY_POPUP_BACKGROUND, this)
        canvas.drawRoundRect(
            RectF(x + x1 * 2 + padding / 3, y + y1 * 2, x - x1 + x3 - padding / 3, y - y1 + y3 - padding),
            radius, radius, p
        )
        if (showSlotText) {
            p.color = c.color(COLOR_TEXT_PRIMARY, this)
            p.textSize = height / float(KEY_TEXT_SIZE_SECONDARY, 5f)
            canvas.drawText(str(charPos - 1), viewW / 2f - dx, height / 5 + (p.textSize - p.descent()) / 2, p)
        }
        drawExtChars(canvas, p, x, y, x1, x2, x3, y1, y2, y3, true)
        canvas.restore()
    }

    private fun drawExtChars(
        cv: Canvas, p: Paint, x: Int, y: Int,
        x1: Float, x2: Float, x3: Float, y1: Float, y2: Float, y3: Float,
        popup: Boolean
    ) {
        if (childCount() == 0) return
        val sh = c.letterShift()
        val xi = floatArrayOf(x1, x2, x3, x1, x3, x1, x2, x3)
        val yi = floatArrayOf(y1, y1, y1, y2, y2, y3, y3, y3)
        for (i in 0..7) {
            if (popup) {
                if (charPos == i + 1) {
                    p.color = c.color(COLOR_KEY_POPUP_SELECTED, this)
                    cv.drawCircle(x + xi[i], y + yi[i], min(width, height) * 0.6f, p)
                }
                p.color = c.color(COLOR_TEXT_PREVIEW, this)
            } else p.color = c.color(COLOR_KEY_SECONDARY, this)
            drawChar(i, x + xi[i], y + yi[i], cv, p, sh)
        }
        if (!popup) return
        if (charPos == 0) {
            p.color = c.color(COLOR_KEY_POPUP_SELECTED, this)
            cv.drawCircle(x + x2, y + y2, min(width, height) * 0.6f, p)
        }
        p.color = c.color(COLOR_TEXT_PREVIEW, this)
        cv.drawText(shiftedLabel(), x + x2, y + y2 + (p.textSize - p.descent()) / 2, p)
    }

    private fun drawChar(pos: Int, ox: Float, oy: Float, canvas: Canvas, paint: Paint, sh: Boolean) {
        val raw = str(pos)
        if (raw.isBlank()) return
        val s = if (raw == VOICE_ACTION) "🎤" else raw
        // Bug fix: shifted popup chars were computed but never drawn.
        val shown = if (s.length == 1) c.getShifted(s[0].code, sh).toChar().toString() else s
        canvas.drawText(shown, ox, oy + (paint.textSize - paint.descent()) / 2, paint)
    }

    private fun shiftedLabel(): String {
        val lb = Labels.resolve(c.ctx, str(KEY_KEY))
        return if (c.letterShift() && lb.length == 1) c.getShifted(lb[0].code, true).toChar().toString() else lb
    }

    companion object {
        /** Maps a 45° sector (starting from the left, clockwise) to popup slot 1..8. */
        private val SECTORS = intArrayOf(4, 1, 2, 3, 5, 8, 7, 6, 4)
    }
}

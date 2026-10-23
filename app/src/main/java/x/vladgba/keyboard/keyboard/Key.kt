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
import kotlin.math.max
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
/** Popup bubble directions, in swipe order: up-left, up, up-right, left, right, down-left, down, down-right. */
private const val POPUP_DIAG = 0.7071f
private val POPUP_DIR_X = floatArrayOf(-POPUP_DIAG, 0f, POPUP_DIAG, -1f, 1f, -POPUP_DIAG, 0f, POPUP_DIAG)
private val POPUP_DIR_Y = floatArrayOf(-POPUP_DIAG, -1f, -POPUP_DIAG, 0f, 0f, POPUP_DIAG, 1f, POPUP_DIAG)

/** Selected bubbles grow by this factor. */
internal const val POPUP_SELECTED_SCALE = 1.14f

/**
 * Distance from the key centre to the popup bubbles for bubble radius [r] and middle bubble
 * radius [rMain]: far enough that neighbouring bubbles (45 degrees apart) and the middle bubble
 * never overlap, even with one of them enlarged as selected.
 */
internal fun popupRing(r: Float, rMain: Float, unit: Float): Float {
    val gap = unit * 0.04f
    val s = POPUP_SELECTED_SCALE
    val betweenNeighbours = (r * s + r + gap) / 0.7654f   // chord of 45 degrees = 2 sin(22.5) ring
    val fromMiddle = max(rMain * s + r, rMain + r * s) + gap
    return max(betweenNeighbours, fromMiddle)
}

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

    // Joystick drag tracking: last finger position/time and smoothed horizontal speed (px/ms).
    private var joyLastX = 0
    private var joyLastY = 0
    private var joyLastTime = 0L
    private var joySpeed = 0f
    private var joyDirX = 0f   // smoothed recent movement, to tell sideways drags from vertical ones
    private var joyDirY = 0f
    // Path since the last joystick step, to keep circles from firing steps.
    private var joyPathLen = 0f
    private var joyAnchorX = 0
    private var joyAnchorY = 0

    /** The finger's path on this key (x, y pairs), for circle / roundtrip gestures. */
    private val path = ArrayList<Float>()
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
        joyLastX = curX
        joyLastY = curY
        joyLastTime = SystemClock.uptimeMillis()
        joySpeed = 0f
        joyDirX = 0f
        joyDirY = 0f
        joyPathLen = 0f
        joyAnchorX = curX
        joyAnchorY = curY
        path.clear()
        path.add(curX.toFloat())
        path.add(curY.toFloat())

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
        recordPath(curX, curY)
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
            recordPath(curX, curY)
            if (detectGesture() == Gesture.CIRCLE) {
                val custom = str(KEY_CIRCLE).trim()
                if (custom.isNotEmpty()) emit(custom) else if (isShiftKey()) c.lockMeta(this)
            }
            state = State.RELEASED
            return
        }

        if (state == State.PRESSED || state == State.MOVED) {
            recordPath(curX, curY)
            if (gestureAction()) {
                state = State.RELEASED
                charPos = 0
                c.releaseOneShotMeta()
                return
            }
        }

        when (state) {
            State.PRESSED -> {
                if (mode == KEY_MODE_RANDOM && this[KEY_RANDOM].childCount() > 0) {
                    val rnd = this[KEY_RANDOM]
                    c.onText(rnd.str((0 until rnd.childCount()).random()))
                } else if (bool(KEY_VOICE) || isTrVoiceKey()) {
                    c.voice.toggle(trVoiceSpec())
                } else if (bool(KEY_COMPOSE) || str(KEY_KEY).trim() == COMPOSE_ACTION) {
                    c.compose.toggle()
                    c.invalidate()
                } else if (str(KEY_KEY).trim().let { it == DELETE_WORD_ACTION || it == DELETE_WORD_FORWARD_ACTION || it == CLIPBOARD_ACTION || DeadKeys.isDeadKey(it) }) {
                    c.runSpecial(str(KEY_KEY))
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

    /**
     * Joystick mode, e.g. the space bar as a cursor slider: every `horTick`/`verTick` px of drag
     * fires the left/right/top/bottom action once.
     *  - Faster drags need less distance per step (`joyAcceleration`), slow drags stay precise.
     *  - Mostly sideways movement doesn't count towards vertical steps, so scrubbing along a line
     *    doesn't jump to the next one.
     *  - With Ctrl held (word jumps) steps need twice the distance.
     */
    private fun joystick(curX: Int, curY: Int) {
        if (mode != KEY_MODE_JOY) return
        val r = relative ?: return

        val now = SystemClock.uptimeMillis()
        val dx = curX - joyLastX
        val dy = curY - joyLastY
        val dt = (now - joyLastTime).coerceAtLeast(1L).toFloat()
        joySpeed = joySpeed * 0.6f + abs(dx) / dt * 0.4f
        joyDirX = joyDirX * 0.8f + dx * 0.2f
        joyDirY = joyDirY * 0.8f + dy * 0.2f
        // Vertical dampening: drift while moving mostly sideways doesn't add up to a line jump.
        if (abs(joyDirY) * 2 < abs(joyDirX)) r.y += dy
        joyLastX = curX
        joyLastY = curY
        joyLastTime = now

        // Keys with circle gestures (e.g. Backspace) only step while the drag is roughly straight,
        // so drawing a circle doesn't fire cursor/delete steps on the way.
        joyPathLen += kotlin.math.hypot(dx.toFloat(), dy.toFloat())
        if (hasGestures()) {
            val disp = kotlin.math.hypot((curX - joyAnchorX).toFloat(), (curY - joyAnchorY).toFloat())
            if (disp > 0f && joyPathLen > disp * 1.35f) return
        }

        val ctrl = c.metaState and KeyEvent.META_CTRL_ON != 0
        val hFactor = if (ctrl) 0.5f else joyAcceleration()
        val vFactor = if (ctrl) 0.5f else 1f
        val beforeX = r.x
        val beforeY = r.y
        while (true) r.x = joyTick(curX, r.x, SENSE_HORIZONTAL_TICK, hFactor, KEY_RIGHT_ACTION, KEY_LEFT_ACTION) ?: break
        while (true) r.y = joyTick(curY, r.y, SENSE_VERTICAL_TICK, vFactor, KEY_BOTTOM_ACTION, KEY_TOP_ACTION) ?: break
        if (r.x != beforeX || r.y != beforeY) {
            joyPathLen = 0f
            joyAnchorX = curX
            joyAnchorY = curY
        }
    }

    // ======================= circle / roundtrip gestures =======================

    enum class Gesture { CIRCLE, ANTICIRCLE, ROUNDTRIP }

    private fun gesturesOn() = !c.isEditor && Settings.bool(SETTING_CIRCLE_GESTURES, true)

    private fun isBackspace() = inputMode == InputMode.CODE && num(KEY_CODE) == -KeyEvent.KEYCODE_DEL

    private fun isShiftKey() = isMeta && num(KEY_MOD_META) and KeyEvent.META_SHIFT_MASK != 0

    private fun letterLabel(): String? {
        if (inputMode != InputMode.LABEL) return null
        val l = Labels.resolve(c.ctx, str(KEY_KEY))
        return l.takeIf { it.codePointCount(0, it.length) == 1 && Character.isLetter(it.codePointAt(0)) }
    }

    /** A clockwise circle on this key types its letter as a capital (the default circle action). */
    fun circleCapitalizes() = Settings.bool(SETTING_CIRCLE_GESTURES, true) &&
        str(KEY_CIRCLE).isBlank() && letterLabel() != null

    /** True if a circle or roundtrip on this key would do something. */
    private fun hasGestures(): Boolean {
        if (!gesturesOn()) return false
        if (str(KEY_CIRCLE).isNotBlank() || str(KEY_ANTICIRCLE).isNotBlank() || str(KEY_ROUNDTRIP).isNotBlank()) return true
        return isBackspace() || isShiftKey() || letterLabel() != null
    }

    private fun recordPath(x: Int, y: Int) {
        if (!hasGestures() || path.size < 2) return
        val lx = path[path.size - 2]
        val ly = path[path.size - 1]
        val step = min(width, height) * 0.06f
        if (kotlin.math.hypot(x - lx, y - ly) < step && path.size > 2) return
        path.add(x.toFloat())
        path.add(y.toFloat())
        // Moving well away from the press point is a gesture, not a hold: no long-press / repeat.
        if (state == State.PRESSED &&
            kotlin.math.hypot(x - path[0], y - path[1]) > min(width, height) * 0.3f)
            c.handler.removeCallbacks(longPressRunnable)
    }

    /**
     * Circle: the path turns by at least ~290 degrees in total and ends near where it started
     * (clockwise on screen = [Gesture.CIRCLE]). Roundtrip: it went out at least as far as the
     * popup threshold and came back close to the start.
     */
    private fun detectGesture(): Gesture? {
        if (!gesturesOn() || path.size < 10) return null
        val unit = min(width, height).toFloat()
        val sx = path[0]
        val sy = path[1]
        var maxD = 0f
        var turn = 0.0
        var pdx = 0f
        var pdy = 0f
        var i = 2
        while (i < path.size) {
            val x = path[i]
            val y = path[i + 1]
            maxD = max(maxD, kotlin.math.hypot(x - sx, y - sy))
            val dx = x - path[i - 2]
            val dy = y - path[i - 1]
            if (pdx != 0f || pdy != 0f) turn += atan2((pdx * dy - pdy * dx).toDouble(), (pdx * dx + pdy * dy).toDouble())
            pdx = dx
            pdy = dy
            i += 2
        }
        if (maxD < unit * 0.35f) return null
        val endD = kotlin.math.hypot(path[path.size - 2] - sx, path[path.size - 1] - sy)
        if (abs(turn) >= Math.PI * 1.6 && endD < maxD * 0.75f)
            return if (turn > 0) Gesture.CIRCLE else Gesture.ANTICIRCLE
        val far = max(num(SENSE_ADDITIONAL_CHARS).toFloat(), unit * 0.5f)
        if (maxD >= far && endD < maxD * 0.35f) return Gesture.ROUNDTRIP
        return null
    }

    /** Runs the action of a recognised gesture. False if there was none, so the key acts as usual. */
    private fun gestureAction(): Boolean {
        val g = detectGesture() ?: return false
        val custom = str(when (g) {
            Gesture.CIRCLE -> KEY_CIRCLE
            Gesture.ANTICIRCLE -> KEY_ANTICIRCLE
            Gesture.ROUNDTRIP -> KEY_ROUNDTRIP
        }).trim()
        when {
            custom.isNotEmpty() -> emit(custom)
            g != Gesture.CIRCLE -> return false
            isBackspace() -> c.deleteWordBefore()
            else -> {
                val letter = letterLabel() ?: return false
                c.onText(letter.uppercase(java.util.Locale.getDefault()))
            }
        }
        c.vibrate(this, KEY_VIBRATE_ADDITIONAL)
        return true
    }

    /** Step-size divisor from the drag speed: 1 when slow, growing with speed (up to 4x). */
    private fun joyAcceleration(): Float {
        val strength = num(SENSE_JOY_ACCELERATION, 100).coerceIn(0, 300) / 100f
        if (strength <= 0f) return 1f
        val dpPerMs = joySpeed / c.ctx.resources.displayMetrics.density
        return (1f + strength * (dpPerMs - 0.15f).coerceAtLeast(0f) * 1.5f).coerceAtMost(4f)
    }

    private fun joyTick(cur: Int, initial: Int, senseName: String, factor: Float, pos: String, neg: String): Int? {
        val base = num(senseName)
        if (base < 1) return null
        val tick = (base / factor).toInt().coerceAtLeast(4)
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
        // Selection slider: with Shift on or text selected, cursor steps grow/shrink the selection.
        if (n != null && c.selectionMode()) when (-n) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { c.extendSelection(-1); c.vibrate(this, KEY_VIBRATE_TICK); return }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { c.extendSelection(1); c.vibrate(this, KEY_VIBRATE_TICK); return }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                c.extendSelectionVertically(-n); c.vibrate(this, KEY_VIBRATE_TICK); return
            }
        }
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

        // modifier marker: solid bar for locked/selected, distinct pip dot for one-shot / auto-shift
        val isLocked = isMeta && c.isMetaLocked(this)
        val isOneShot = isMeta && !isLocked && c.isMetaActive(this)
        val isAutoShift = c.autoShift && has(KEY_MOD_META) && (num(KEY_MOD_META) and KeyEvent.META_SHIFT_MASK != 0)
        if (isLocked || bool(KEY_SELECTED)) {
            p.color = c.color(COLOR_TEXT_PRIMARY, this)
            val w = width / 4f
            canvas.drawRect(x + width / 2f - w / 2, body.bottom - padding - 4, x + width / 2f + w / 2, body.bottom - padding, p)
        } else if (isOneShot || isAutoShift) {
            p.color = c.color(COLOR_TEXT_PRIMARY, this)
            val dotR = (height / 24f).coerceAtLeast(3f)
            canvas.drawCircle(x + width / 2f, body.bottom - padding - dotR - 1f, dotR, p)
        }
    }

    /** Draws the 3x3 preview popup for the pressed key (on top of the whole keyboard). */
    /**
     * The long-press popup: the key's extra characters as small round bubbles around the key,
     * one per swipe direction, with the key's own character in the middle bubble.
     *
     * The popup never moves: the middle bubble is always right over the key. A bubble that would
     * stick out past the keyboard's sides or bottom (or above the [above] head-room the input
     * method keeps over the keyboard, drawn over the app) is left out, so keys at the screen edge
     * only show the characters pointing away from it.
     */
    fun drawPopup(canvas: Canvas, viewW: Int, viewH: Int, above: Int = 0) {
        val isClipboard = bool(KEY_CLIPBOARD)
        if (childCount() == 0 && !isClipboard) return
        canvas.save()
        val p = c.popupPaint
        p.textAlign = Paint.Align.CENTER
        p.style = Paint.Style.FILL

        // dim the keyboard
        p.color = c.color(COLOR_KEY_SHADOW, this)
        canvas.drawRect(0f, 0f, viewW.toFloat(), viewH.toFloat(), p)

        val unit = min(width, height).toFloat()
        val r = unit * popupBubblePct(KEY_POPUP_BUBBLE_SIZE) / 200f          // bubble radius
        val rMain = unit * popupBubblePct(KEY_POPUP_MAIN_BUBBLE_SIZE) / 200f // middle bubble radius
        val ring = popupRing(r, rMain, unit)   // distance from the key centre to a bubble centre
        val cx = x + width / 2f
        val cy = y + height / 2f

        // Where each extra character sits (same order as the swipe directions).
        val used = BooleanArray(8) { isClipboard || str(it).isNotBlank() }
        val bx = FloatArray(8) { cx + POPUP_DIR_X[it] * ring }
        val by = FloatArray(8) { cy + POPUP_DIR_Y[it] * ring }

        // Only the bubbles that hold a character and fit entirely on screen.
        val shown = BooleanArray(8) {
            used[it] && bx[it] - r >= 0f && bx[it] + r <= viewW &&
                by[it] - r >= -above && by[it] + r <= viewH
        }

        // Text size per px of bubble radius: the default look at the default 60% bubble, and it
        // scales with the bubble.
        val textSize = height / float(KEY_TEXT_SIZE_PRIMARY, 2.5f) * 0.68f / (0.3f * unit)
        val sh = c.letterShift()
        val selected = c.color(COLOR_KEY_POPUP_SELECTED, this)
        for (i in 0..7) {
            if (!shown[i]) continue
            val label = popupLabel(i, sh)
            drawBubble(canvas, p, bx[i], by[i], r, label, textSize * r, charPos == i + 1, selected)
        }
        drawBubble(canvas, p, cx, cy, rMain, shiftedLabel(), textSize * rMain, charPos == 0, selected)
        canvas.restore()

        // Clipboard slot: show what the selected slot holds, centred above the keyboard.
        if (isClipboard && charPos > 0 && str(charPos - 1).isNotEmpty()) {
            p.color = c.color(COLOR_TEXT_PRIMARY, this)
            p.textSize = height / float(KEY_TEXT_SIZE_SECONDARY, 5f)
            canvas.drawText(str(charPos - 1), viewW / 2f, height / 5 + (p.textSize - p.descent()) / 2, p)
        }
    }

    private fun popupBubblePct(key: String) = num(key, 60).coerceIn(20, 150).toFloat()

    private fun popupLabel(pos: Int, sh: Boolean): String {
        val raw = str(pos)
        if (raw.isBlank()) return ""
        val s = when {
            raw == VOICE_ACTION -> "🎤"
            raw.startsWith(TR_VOICE_PREFIX) || raw.startsWith(TR_VOICE_ALT_PREFIX) -> "🎤🌐"
            raw == COMPOSE_ACTION -> "⎄"
            raw == DELETE_WORD_ACTION -> "⌫⌫"
            raw == DELETE_WORD_FORWARD_ACTION -> "⌦⌦"
            raw == CLIPBOARD_ACTION -> "📋"
            DeadKeys.isDeadKey(raw) -> DeadKeys.symbol(DeadKeys.nameOf(raw))
            raw == COMPLETE_EMOJI_ACTION -> "✓🙂"
            raw.startsWith(COMPLETE_ACTION_PREFIX) -> "✓" + raw.removePrefix(COMPLETE_ACTION_PREFIX)
            else -> raw
        }
        return if (s.length == 1) c.getShifted(s[0].code, sh).toChar().toString() else s
    }

    /** One popup bubble; the selected one is a little larger and tinted. */
    private fun drawBubble(
        cv: Canvas, p: Paint, bx: Float, by: Float, r: Float,
        label: String, textSize: Float, isSelected: Boolean, selectedColor: Int
    ) {
        val rr = if (isSelected) r * POPUP_SELECTED_SCALE else r
        p.style = Paint.Style.FILL
        p.color = c.color(COLOR_KEY_BORDER, this)
        cv.drawCircle(bx, by, rr + r * 0.06f, p)
        p.color = c.color(COLOR_KEY_POPUP_BACKGROUND, this)
        cv.drawCircle(bx, by, rr, p)
        if (isSelected) {
            p.color = selectedColor
            cv.drawCircle(bx, by, rr, p)
        }
        if (label.isEmpty()) return
        p.color = c.color(COLOR_TEXT_PREVIEW, this)
        p.textSize = textSize * (if (isSelected) POPUP_SELECTED_SCALE else 1f)
        // Longer labels (words, emoji pairs) shrink to fit inside the bubble.
        val w = p.measureText(label)
        if (w > rr * 1.6f) p.textSize *= rr * 1.6f / w
        cv.drawText(label, bx, by + (p.textSize - p.descent()) / 2, p)
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
        val s = when {
            raw == VOICE_ACTION -> "🎤"
            raw.startsWith(TR_VOICE_PREFIX) || raw.startsWith(TR_VOICE_ALT_PREFIX) -> "🎤🌐"
            raw == COMPOSE_ACTION -> "⎄"
            raw == DELETE_WORD_ACTION -> "⌫⌫"
            raw == DELETE_WORD_FORWARD_ACTION -> "⌦⌦"
            raw == CLIPBOARD_ACTION -> "📋"
            DeadKeys.isDeadKey(raw) -> DeadKeys.symbol(DeadKeys.nameOf(raw))
            raw == COMPLETE_EMOJI_ACTION -> "✓🙂"
            raw.startsWith(COMPLETE_ACTION_PREFIX) -> "✓" + raw.removePrefix(COMPLETE_ACTION_PREFIX)
            else -> raw
        }
        // Bug fix: shifted popup chars were computed but never drawn.
        val shown = if (s.length == 1) c.getShifted(s[0].code, sh).toChar().toString() else s
        canvas.drawText(shown, ox, oy + (paint.textSize - paint.descent()) / 2, paint)
    }

    private fun shiftedLabel(): String {
        val raw = str(KEY_KEY).trim()
        val lb = when {
            raw == VOICE_ACTION || bool(KEY_VOICE) -> "🎤"
            raw.startsWith(TR_VOICE_PREFIX) || raw.startsWith(TR_VOICE_ALT_PREFIX) || isTrVoiceKey() -> "🎤🌐"
            raw == COMPOSE_ACTION || bool(KEY_COMPOSE) -> "⎄"
            raw == DELETE_WORD_ACTION -> "⌫⌫"
            raw == DELETE_WORD_FORWARD_ACTION -> "⌦⌦"
            raw == CLIPBOARD_ACTION -> "📋"
            DeadKeys.isDeadKey(raw) -> DeadKeys.symbol(DeadKeys.nameOf(raw))
            else -> Labels.resolve(c.ctx, raw)
        }
        return if (c.letterShift() && lb.length == 1) c.getShifted(lb[0].code, true).toChar().toString() else lb
    }

    private fun isTrVoiceKey(): Boolean {
        val v = str(KEY_VOICE).trim()
        val k = str(KEY_KEY).trim()
        return v.startsWith(TR_VOICE_PREFIX) || v.startsWith(TR_VOICE_ALT_PREFIX) ||
               k.startsWith(TR_VOICE_PREFIX) || k.startsWith(TR_VOICE_ALT_PREFIX)
    }

    private fun trVoiceSpec(): TranslationSpec? {
        val v = str(KEY_VOICE).trim()
        if (v.startsWith(TR_VOICE_PREFIX) || v.startsWith(TR_VOICE_ALT_PREFIX)) return VoiceInput.parseTrVoiceSpec(v)
        val k = str(KEY_KEY).trim()
        if (k.startsWith(TR_VOICE_PREFIX) || k.startsWith(TR_VOICE_ALT_PREFIX)) return VoiceInput.parseTrVoiceSpec(k)
        return null
    }

    companion object {
        /** Maps a 45° sector (starting from the left, clockwise) to popup slot 1..8. */
        private val SECTORS = intArrayOf(4, 1, 2, 3, 5, 8, 7, 6, 4)
    }
}

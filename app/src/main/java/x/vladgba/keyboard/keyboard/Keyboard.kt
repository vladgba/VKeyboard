package x.vladgba.keyboard.keyboard

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.KeyEvent.*
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import java.util.concurrent.Future
import x.vladgba.keyboard.KeybWrapper
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser
import x.vladgba.keyboard.ui.EditInterface
import x.vladgba.keyboard.ui.RawEditor
import kotlin.math.min

/**
 * Keyboard controller: layouts, modifiers, text output and hardware keys.
 * Drawing and touch dispatch live in [KeyboardView]; the IME service is [KeybWrapper].
 *
 * With [ime] == null the keyboard runs inside the layout editor: touches go to [editInterface]
 * and nothing is sent to any app.
 */
class Keyboard(val ctx: Context, val ime: KeybWrapper?) {
    val view = KeyboardView(ctx, this)
    val isEditor get() = ime == null
    val inputConnection: InputConnection? get() = ime?.currentInputConnection
    val voice = VoiceInput(this)
    val compose = ComposeHelper()
    private var currentAppPackage = ""
    private var appSwitchedLayout = false
    /** Ignore the rest of a touch that only stopped voice typing. */
    private var swallowTouch = false

    var isPortrait = true
        private set
    var isNight = false
        private set

    // ---- modifiers ----
    var metaState = 0
    private var hardMetaState = 0
    private enum class Latch { ONE_SHOT, LOCKED }
    private val metaLatches = LinkedHashMap<Key, Latch>()
    /** Modifier keys that were held down while another key was pressed (i.e. used as a combination). */
    private val comboUsed = HashSet<Key>()

    // ---- layouts ----
    var currentLayoutName = ""
    private var lastTextLayout = ""
    private var autoNumSwitched = false
    private val loaded = HashMap<String, KeybLayout>()
    /** Number row setting the cached layouts were built with. */
    private var loadedNumberRow = -1
    lateinit var currentLayout: KeybLayout
        private set

    // ---- touch ----
    val pointers = HashMap<Int, Key>()
    var lastPointerId = 0
    val handler = Handler(Looper.getMainLooper())
    var recKey: Key? = null
    var editInterface: EditInterface? = null

    // ---- input field ----
    internal var keybType = KeybType.NORMAL
    private var imeAction = EditorInfo.IME_ACTION_NONE
    private var multiLine = false
    /** Password or incognito field: nothing about touches is recorded. */
    private var privateField = false
    private var editorInputType = 0
    /** The field asks for a capital letter here (start of a sentence...). Applies to the next letter only. */
    var autoShift = false
        private set
    private var lastSpaceTime = 0L

    val paint = Paint().apply { isAntiAlias = true }
    val popupPaint = Paint().apply { isAntiAlias = true }
    val sounds = KeySounds()
    private val textExpander = TextExpander(ctx)
    private var vibrationMs = 0L

    init {
        Bootstrap.ensureInstalled(ctx)
        updateNightState()
        updateOrientation()
        Settings.loadVars(ctx)
        InjectedEvent.DEV = Settings.str(SETTING_INPUT_DEVICE)
        Theme.load(ctx, isNight)
        currentLayoutName = Settings.str(SETTING_DEF_LAYOUT)
        lastTextLayout = currentLayoutName
        reloadLayout()
    }

    fun invalidate() = view.invalidate()

    // ======================= lifecycle / configuration =======================

    fun onConfigurationChanged(cfg: Configuration) {
        Labels.clear() // the language may have changed
        val orientation = updateOrientation(cfg)
        val night = updateNightState(cfg)
        if (night) Theme.load(ctx, isNight)
        if (orientation || night) {
            view.requestLayout()
            invalidate()
        }
    }

    private fun updateOrientation(cfg: Configuration = ctx.resources.configuration): Boolean {
        val portrait = cfg.orientation != Configuration.ORIENTATION_LANDSCAPE
        if (portrait == isPortrait) return false
        isPortrait = portrait
        return true
    }

    private fun updateNightState(cfg: Configuration = ctx.resources.configuration): Boolean {
        val night = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night == isNight) return false
        isNight = night
        return true
    }

    /** Called by the IME for every new input field. */
    /** The last typing (non-number) layout, e.g. to pick the voice language on the 123 layout. */
    val lastTypingLayout get() = lastTextLayout

    fun onStartInput(info: EditorInfo) {
        cancelSuggestions()
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        voice.cancel()
        compose.cancel()
        currentAppPackage = info.packageName ?: ""
        setKeybType(info.inputType)
        editorInputType = info.inputType
        imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) imeAction = EditorInfo.IME_ACTION_NONE
        multiLine = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val variation = info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        privateField = variation in setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ) || info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        updateSuggestionsShown(info)
        scheduleSuggestions()
    }

    /** Called when the keyboard becomes visible. */
    fun onShow() {
        Settings.loadVars(ctx)
        InjectedEvent.DEV = Settings.str(SETTING_INPUT_DEVICE)
        Theme.load(ctx, isNight)
        textExpander.reload()

        if (currentAppPackage.isNotEmpty()) {
            val appNode = Settings[SETTING_APP_LAYOUTS]
            val target = (if (appNode.has(currentAppPackage)) appNode.str(currentAppPackage)
                          else Settings.str("appLayout.$currentAppPackage")).trim()
            if (target.isNotEmpty() && PFile(ctx, target, LAYOUT_EXT).exists()) {
                if (currentLayoutName != target) {
                    appSwitchedLayout = true
                    currentLayoutName = target
                    lastTextLayout = target
                }
            } else if (appSwitchedLayout) {
                appSwitchedLayout = false
                val def = Settings.str(SETTING_DEF_LAYOUT)
                currentLayoutName = def
                lastTextLayout = def
            }
        }

        if (Settings.bool(SETTING_AUTO_NUM_LAYOUT, true)) {
            val numeric = keybType in setOf(KeybType.NUMBER, KeybType.PHONE, KeybType.DATETIME, KeybType.DATE, KeybType.TIME)
            if (numeric && PFile(ctx, NUM_FILENAME).exists()) {
                if (currentLayoutName != NUM_FILENAME) autoNumSwitched = true
                currentLayoutName = NUM_FILENAME
            } else if (!numeric && autoNumSwitched) {
                autoNumSwitched = false
                currentLayoutName = lastTextLayout
            }
        }
        reloadLayout()
        updateAutoShift()
    }

    fun onHide() {
        cancelSuggestions()
        voice.cancel()
        compose.cancel()
        resetTouches()
        TouchStats.flush(ctx)
        UserWords.submit { UserWords.flush(ctx) }
    }

    fun resetTouches() {
        for (key in pointers.values) key.cancel()
        pointers.clear()
        comboUsed.clear()
        invalidate()
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            // Keep only the visible layout; others are re-parsed on demand.
            loaded.keys.retainAll { it == currentLayoutName }
        }
    }

    fun onDestroy() {
        cancelSuggestions()
        voice.release()
        handler.removeCallbacksAndMessages(null)
        sounds.release()
    }

    // ======================= drawing / touch (called by KeyboardView) =======================

    fun draw(canvas: Canvas, w: Int, h: Int, popups: Boolean = true) {
        try {
            currentLayout.onDraw(canvas)
            if (!isEditor) { if (popups) drawPopups(canvas, w, h, 0) }
            else editInterface?.drawOverlay(canvas, w, h)
            if (voice.active) drawVoiceOverlay(canvas, w, h)
            else if (compose.active) drawComposeOverlay(canvas, w, h)
        } catch (e: Exception) {
            prStack(e)
        }
    }

    /** Popups of the pressed keys; [above] px over the keyboard's top edge may be used. */
    fun drawPopups(canvas: Canvas, w: Int, h: Int, above: Int) {
        if (isEditor || voice.active || compose.active) return
        try {
            for (key in pointers.values) key.drawPopup(canvas, w, h, above)
        } catch (e: Exception) {
            prStack(e)
        }
    }

    /** Room the input method keeps above the keyboard for popups: one top-row key, plus a margin. */
    fun popupHeadroom(): Int {
        if (isEditor) return 0
        val top = currentLayout.rows.firstOrNull { r -> r.keys.any { it.width > 0 } } ?: return 0
        val keyH = top.keys.filter { it.width > 0 && it.childCount() > 0 }.maxOfOrNull { it.height } ?: top.height
        return keyH + (ctx.resources.displayMetrics.density * 4).toInt()
    }

    fun onTouch(me: MotionEvent): Boolean {
        // While listening, the whole keyboard is the stop button.
        if (!isEditor && (voice.active || swallowTouch)) {
            when (me.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swallowTouch = true
                    voice.stop()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swallowTouch = false
            }
            return true
        }
        val index = me.actionIndex
        val pid = me.getPointerId(index)
        val x = me.getX(index).toInt()
        val y = me.getY(index).toInt()
        try {
            when (me.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val key = keyAt(x, y) ?: return true
                    if (isEditor) editInterface?.onPress(key, x, y)
                    else {
                        lastPointerId = pid
                        if (!key.isMeta) markHeldModifiersUsed()
                        pointers[pid] = key
                        recordTouch(key, x, y)
                        key.press(x, y)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isEditor) editInterface?.onMove(x, y)
                    else for (i in 0 until me.pointerCount) {
                        pointers[me.getPointerId(i)]?.drag(me.getX(i).toInt(), me.getY(i).toInt(), me, i)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (isEditor) editInterface?.onRelease(x, y)
                    else pointers.remove(pid)?.release(x, y, pid)
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isEditor) editInterface?.onCancel() else resetTouches()
                }
            }
            invalidate()
        } catch (e: Exception) {
            prStack(e)
        }
        return true
    }

    private fun recordTouch(key: Key, x: Int, y: Int) {
        if (privateField) return
        val store = Settings.bool(SETTING_TOUCH_STATS)
        if (!store && TouchStats.listener == null) return
        if (key.width <= 0 || key.height <= 0) return
        val id = keyIds()[key] ?: return
        TouchStats.record(
            ctx, store, currentLayoutName, id,
            (x - (key.x + key.width / 2f)) / (key.width / 2f), (y - (key.y + key.height / 2f)) / (key.height / 2f),
        )
    }

    // ======================= touch stats / hitbox autotune =======================

    private var idsLayout: KeybLayout? = null
    private var idsKeyCount = -1
    private var ids: Map<Key, String> = emptyMap()

    /** Stable touch-stats ids of the current layout's keys (they follow a key when it moves). */
    private fun keyIds(): Map<Key, String> {
        val layout = currentLayout
        val count = layout.rows.sumOf { it.keys.size }
        if (layout !== idsLayout || count != idsKeyCount) {
            val rows = layout.rows.map { it.keys }
            val list = TouchStats.keyIds(rows)
            val m = HashMap<Key, String>()
            for ((r, row) in rows.withIndex()) for ((i, k) in row.withIndex()) m[k] = list[r][i]
            ids = m
            idsLayout = layout
            idsKeyCount = count
            TouchStats.migrate(ctx, currentLayoutName, list)
        }
        return ids
    }

    private var shiftsLayout: KeybLayout? = null
    private var shiftsVersion = -1
    private var shifts: Map<Key, FloatArray> = emptyMap()

    private fun hitboxShifts(): Map<Key, FloatArray> {
        val ids = keyIds()
        if (shiftsLayout !== currentLayout || shiftsVersion != TouchStats.version) {
            val byId = TouchStats.hitboxShifts(ctx, currentLayoutName)
            shifts = if (byId.isEmpty()) emptyMap() else ids.mapNotNull { (k, id) -> byId[id]?.let { k to it } }.toMap()
            shiftsLayout = currentLayout
            shiftsVersion = TouchStats.version
        }
        return shifts
    }

    /**
     * The key under a touch. With hitbox autotune on, each key's touch area (not its drawing) is
     * moved toward where the user really touches it, learned from the touch stats; where shifted
     * areas overlap, the key whose tuned center is closer wins. Untuned keys keep their exact area.
     */
    private fun keyAt(x: Int, y: Int): Key? {
        val base = currentLayout.getKey(x, y)
        if (isEditor || !Settings.bool(SETTING_HITBOX_AUTOTUNE)) return base
        val shifts = hitboxShifts()
        if (shifts.isEmpty()) return base
        var best: Key? = null
        var bestD = Float.MAX_VALUE
        for (row in currentLayout.rows) for (k in row.keys) {
            if (k.width <= 0 || k.height <= 0) continue
            val hw = k.width / 2f
            val hh = k.height / 2f
            val s = shifts[k]
            val cx = k.x + hw + (s?.get(0) ?: 0f) * hw
            val cy = k.y + hh + (s?.get(1) ?: 0f) * hh
            val nx = (x - cx) / hw
            val ny = (y - cy) / hh
            if (nx < -1f || nx >= 1f || ny < -1f || ny >= 1f) continue
            val d = nx * nx + ny * ny
            if (d < bestD) { bestD = d; best = k }
        }
        return best ?: base
    }

    // ======================= voice overlay =======================

    private val voicePaint = Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER }

    private fun drawVoiceOverlay(canvas: Canvas, w: Int, h: Int) {
        val p = voicePaint
        val node = currentLayout
        p.color = color(COLOR_KEY_SHADOW, node)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        val cardW = w * 0.9f
        val cardH = h * 0.78f
        val left = (w - cardW) / 2
        val top = (h - cardH) / 2
        val radius = min(cardW, cardH) / 10
        p.color = color(COLOR_KEY_BORDER, node)
        canvas.drawRoundRect(android.graphics.RectF(left - 2, top - 2, left + cardW + 2, top + cardH + 4), radius, radius, p)
        p.color = color(COLOR_KEY_POPUP_BACKGROUND, node)
        canvas.drawRoundRect(android.graphics.RectF(left, top, left + cardW, top + cardH), radius, radius, p)

        // Microphone with a ring that grows with the voice level.
        val cx = w / 2f
        val cy = top + cardH * 0.36f
        val r = cardH * 0.17f
        val accent = color(COLOR_KEY_PRESSED_MOD_BACKGROUND, node)
        val listening = voice.state == VoiceInput.State.LISTENING
        if (listening) {
            p.color = (accent and 0x00ffffff) or 0x44000000
            canvas.drawCircle(cx, cy, r * (1.15f + voice.level * 0.65f), p)
        }
        p.color = accent
        canvas.drawCircle(cx, cy, r, p)
        p.textSize = r * 1.1f
        canvas.drawText("🎤", cx, cy + p.textSize * 0.36f, p)

        // What was heard so far, or the status.
        val status = when (voice.state) {
            VoiceInput.State.STARTING -> ctx.getString(R.string.vo_starting)
            VoiceInput.State.LISTENING -> voice.partial.ifEmpty { ctx.getString(R.string.vo_listening) }
            VoiceInput.State.PROCESSING -> voice.partial.ifEmpty {
                ctx.getString(if (voice.isTranslating) R.string.vo_translating else R.string.vo_processing)
            }
            VoiceInput.State.IDLE -> ""
        }
        p.color = color(COLOR_TEXT_PREVIEW, node)
        p.textSize = cardH * 0.11f
        val maxW = cardW * 0.9f
        var shown = status
        while (shown.length > 1 && p.measureText(shown) > maxW) shown = shown.substring(1)
        if (shown != status) shown = "…" + shown
        canvas.drawText(shown, cx, top + cardH * 0.72f, p)

        p.color = color(COLOR_KEY_SECONDARY, node)
        p.textSize = cardH * 0.075f
        val footer = voice.translationSpec?.let { spec ->
            val src = if (spec.sourceLang == "auto") voice.language().substringBefore("-") else spec.sourceLang
            ctx.getString(R.string.vo_tap_to_stop, "$src → ${spec.targetLang}")
        } ?: ctx.getString(R.string.vo_tap_to_stop, voice.language())
        canvas.drawText(footer, cx, top + cardH * 0.9f, p)
    }

    private fun drawComposeOverlay(canvas: Canvas, w: Int, h: Int) {
        val p = voicePaint
        val text = compose.dead?.let { "${DeadKeys.symbol(it)}  …" } ?: "⎄ Compose: ${compose.firstChar ?: "…"}"
        val density = ctx.resources.displayMetrics.density
        p.textSize = 14f * density
        val tw = p.measureText(text)
        val padH = 16f * density
        val padV = 8f * density
        val cardW = tw + padH * 2
        val cardH = p.textSize + padV * 2
        val left = (w - cardW) / 2f
        val top = 8f * density
        val r = cardH / 2f
        val node = currentLayout
        p.color = color(COLOR_KEY_PRESSED_MOD_BACKGROUND, node)
        canvas.drawRoundRect(RectF(left, top, left + cardW, top + cardH), r, r, p)
        p.color = color(COLOR_TEXT_PRIMARY, node)
        canvas.drawText(text, w / 2f, top + cardH / 2f + (p.textSize - p.descent()) / 2f, p)
    }

    // ======================= hardware keys =======================

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEditor || keyCode < 0) return false
        try {
            if (Settings.bool(SETTING_DEBUG))
                log("Down:$keyCode;${event.device?.name}/${event.deviceId};S${event.scanCode};M${event.metaState};'${event.unicodeChar}'")
            if (hardwareKey(keyCode.toString(), ACTION_DOWN, true)) return true
            if (isModifierKey(keyCode)) {
                hardMetaState = event.metaState
                metaState = metaState or hardMetaState
                invalidate()
            }
        } catch (e: Exception) {
            prStack(e)
        }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isEditor || keyCode < 0) return false
        try {
            if (Settings.bool(SETTING_DEBUG))
                log("Up:$keyCode;${event.device?.name}/${event.deviceId};S${event.scanCode};M${event.metaState};'${event.unicodeChar}'")
            if (hardwareKey(keyCode.toString(), ACTION_UP, false)) return true
            if (isModifierKey(keyCode) || keyCode == KEYCODE_CAPS_LOCK || keyCode == KEYCODE_NUM_LOCK || keyCode == KEYCODE_SCROLL_LOCK) {
                metaState = metaState and hardMetaState.inv()
                hardMetaState = event.metaState
                metaState = metaState or hardMetaState
                invalidate()
            }
        } catch (e: Exception) {
            prStack(e)
        }
        return false
    }

    private fun isModifierKey(keyCode: Int) = keyCode in setOf(
        KEYCODE_CTRL_LEFT, KEYCODE_CTRL_RIGHT, KEYCODE_META_LEFT, KEYCODE_META_RIGHT,
        KEYCODE_SHIFT_LEFT, KEYCODE_SHIFT_RIGHT, KEYCODE_ALT_LEFT, KEYCODE_ALT_RIGHT
    )

    /**
     * Hardware key remapping: with `"redefineHardwareActions": 1` in a layout (or settings), a param
     * named by the key code (e.g. `"24": (code: 59, meta: 129)` for Volume Up) redefines that key.
     */
    private fun hardwareKey(key: String, action: Int, pressing: Boolean): Boolean {
        if (!currentLayout.bool(SETTING_REDEFINE_HW_ACTION) || !currentLayout.has(key)) return false
        val keyData = currentLayout[key]
        if (!keyData.bool(KEY_DO_IF_HIDDEN) && !view.isShown) return false

        if (injectedEvent(keyData, pressing)) return true

        if (keyData.has(KEY_TEXT) && pressing) {
            val txt = keyData.str(KEY_TEXT)
            if (txt.isNotEmpty()) {
                onText(txt)
                return true
            }
        }

        val mask = keyData.num(KEY_MOD_META)
        metaState = if (pressing) metaState or mask else metaState and mask.inv()
        if (keyData.has(KEY_CODE)) keyModified(action, keyData.num(KEY_CODE))
        invalidate()

        if (pressing && keyData.has(KEY_SWITCH_LAYOUT)) {
            val target = if (keyData.bool(KEY_SWITCH_LAYOUT)) Settings.str(SETTING_DEF_LAYOUT) else keyData.str(KEY_SWITCH_LAYOUT)
            if (target.isNotBlank() && target != currentLayoutName) switchLayout(target)
        }
        return true
    }

    private fun injectedEvent(kd: FlexNode, pressing: Boolean): Boolean {
        if (!kd.has("inject")) return false
        val su = kd.str(ACTION_SU)
        val inject = kd.bool("inject")
        if (!pressing) return true
        Thread {
            if (su.isNotBlank()) KeyAction.suExecBlocking(su)
            if (inject) {
                InjectedEvent.press(kd.num("x"), kd.num("y"), 1024, 1, 4)
                if (kd.has("mx") || kd.has("my")) InjectedEvent.move(kd.num("mx", -1), kd.num("my", -1), 4)
                Thread.sleep(20L)
                InjectedEvent.release(0, 4)
            }
        }.start()
        return true
    }

    // ======================= output =======================

    fun keyModified(keyAct: Int, key: Int, meta: Int = metaState) {
        val ic = inputConnection ?: return
        recKey?.let { if (it.recording) it.record.add(Key.Record(key, meta, keyAct)) }
        val time = System.currentTimeMillis()
        ic.sendKeyEvent(
            KeyEvent(time, time, keyAct, key, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, FLAG_SOFT_KEYBOARD or FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun clickModified(key: Int, meta: Int = metaState) {
        keyModified(ACTION_DOWN, key, meta)
        keyModified(ACTION_UP, key, meta)
    }

    /**
     * Key output: a-z go out as key events (so Ctrl/Alt combos work), negative values are
     * Android key codes, everything else is a Unicode code point committed as text.
     */
    fun onKey(i: Int) {
        if (Settings.bool(SETTING_DEBUG)) log("key: $i")
        if (isEditor || i == 0) return
        val space = i == -KEYCODE_SPACE
        if (space && doubleSpacePeriod()) return
        lastSpaceTime = if (space) android.os.SystemClock.uptimeMillis() else 0L
        when {
            i in LATIN_KEYS -> {
                textExpander.clearUndo()
                if (compose.active) {
                    val code = getShifted(i, letterShift())
                    feedCompose(Character.toChars(code).concatToString())
                    return
                }
                clickModified(i - LATIN_OFFSET, if (autoShiftApplies()) metaState or META_SHIFT_ON else metaState)
            }
            i < 0 -> {
                val code = -i
                if (compose.active && (code == KEYCODE_DEL || code == KEYCODE_ESCAPE)) {
                    compose.cancel()
                    invalidate()
                    return
                }
                // Space after a dead key types the accent on its own.
                if (compose.dead != null && code == KEYCODE_SPACE) {
                    feedCompose(" ")
                    return
                }
                if (code == KEYCODE_DEL) {
                    if (undoSpaceAccept()) return
                    val ic = inputConnection
                    if (ic != null && textExpander.undo(ic)) return
                } else {
                    autoAccepted = null
                    textExpander.clearUndo()
                }
                if (code == KEYCODE_SPACE && metaState == 0 && spaceAccept()) return
                if (code == KEYCODE_SPACE || code == KEYCODE_ENTER) wordBoundary(if (code == KEYCODE_SPACE) " " else "\n")
                if (code == KEYCODE_ENTER && performEditorAction()) return
                clickModified(code)
            }
            else -> onText(if (Character.isValidCodePoint(i)) String(Character.toChars(getShifted(i, letterShift()))) else return)
        }
        scheduleAutoShift()
    }

    fun onText(chars: CharSequence) {
        if (isEditor) return
        if (compose.active) {
            feedCompose(chars.toString())
            return
        }
        val s = chars.toString()
        autoAccepted = null
        if (!TextExpander.isSeparator(chars)) textExpander.clearUndo()
        lastSpaceTime = 0L
        scheduleAutoShift()
        recKey?.let { rk ->
            if (rk.recording) {
                val last = rk.record.lastOrNull()
                if (last != null && !last.keyText.isNullOrEmpty()) last.keyText += s
                else rk.record.add(Key.Record(s))
            }
        }
        if (smartPunctuation(s)) return
        if (TextExpander.isSeparator(chars)) wordBoundary(s)
        setText(s)
    }

    private fun feedCompose(s: String) {
        val res = compose.feed(s)
        invalidate()
        if (res != null) {
            scheduleAutoShift()
            setText(res)
        }
    }

    /** If typing a punctuation mark immediately after "word ", swallow the space: "word " + "," -> "word," */
    private fun smartPunctuation(punc: String): Boolean {
        if (punc.length != 1 || punc[0] !in ".,!?:;") return false
        if (!Settings.bool(SETTING_SMART_PUNCTUATION, true)) return false
        if (metaState != 0 || privateField) return false
        val ic = inputConnection ?: return false
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
        if (before.length >= 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(punc, 1)
            ic.endBatchEdit()
            scheduleAutoShift()
            return true
        }
        return false
    }

    fun setText(text: String, del: Int = 0) = inputConnection?.apply {
        beginBatchEdit()
        if (del > 0) deleteSurroundingText(del, 0)
        commitText(text, 1)
        endBatchEdit()
    }

    /** A space, Enter or punctuation is about to be typed after a word: learn it, then expand abbreviations. */
    private fun wordBoundary(sep: String) {
        learnLastWord()
        expandText(sep)
    }

    /** Counts the word just before the cursor in the learned dictionary (see [UserWords]). */
    private fun learnLastWord() {
        if (isEditor || privateField || !Settings.bool(SETTING_LEARN_WORDS, true)) return
        val before = inputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'' || before[i - 1] == '’' || before[i - 1] == '-')) i--
        if (i > 0 && before[i - 1].isLetterOrDigit()) return  // e.g. digits glued to the word
        val word = before.substring(i).trim('\'', '’', '-')
        if (word.isEmpty() || textExpander.isAbbreviation(word)) return
        UserWords.submit { UserWords.learn(ctx, word) }
    }

    private fun expandText(sep: String = "") {
        if (!Settings.bool(SETTING_TEXT_EXPANSION, true)) return
        inputConnection?.let { textExpander.expand(it, sep) }
    }

    /** Enter in single-line fields triggers the field's action (Go/Search/Send/...) like other keyboards. */
    private fun performEditorAction(): Boolean {
        if (multiLine || metaState != 0) return false
        if (imeAction == EditorInfo.IME_ACTION_NONE || imeAction == EditorInfo.IME_ACTION_UNSPECIFIED) return false
        return inputConnection?.performEditorAction(imeAction) ?: false
    }

    // ======================= modifiers =======================

    /** Shift for letters: the Shift key, or automatic capitals (never together with Ctrl/Alt/Meta). */
    fun letterShift() = shiftPressed() || autoShiftApplies()

    private fun autoShiftApplies() =
        autoShift && !shiftPressed() && metaState and (META_CTRL_MASK or META_ALT_MASK or META_META_MASK) == 0

    private val autoShiftRunnable = Runnable { updateAutoShift() }

    /** Re-checks after the editor has applied the last input. */
    private fun scheduleAutoShift() {
        handler.removeCallbacks(autoShiftRunnable)
        handler.post(autoShiftRunnable)
    }

    /** The cursor moved (tap in the text, arrows...): capitals may be needed now or no longer. */
    // ======================= word suggestions =======================

    /** One entry of the suggestion bar: a word, or an emoji to add after the word. */
    data class Suggestion(val text: String, val emoji: Boolean = false)

    /** Current suggestions, best first (at most 3; an emoji, if any, is last). */
    var suggestions: List<Suggestion> = emptyList()
        private set

    /** Last word replaced by space auto-complete (typed → accepted), for Backspace to undo. */
    private var autoAccepted: Pair<String, String>? = null

    /** Word whose auto-complete was undone: the next space keeps it as typed. */
    private var autoRejected: String? = null

    /** The suggestion bar is shown for this field. */
    var suggestionsShown = false
        private set

    /** Called when [suggestions] or [suggestionsShown] change (the IME frame redraws / resizes). */
    var onSuggestionsChanged: (() -> Unit)? = null

    /** The part of the word before the cursor the suggestions complete. */
    private var suggestionPrefix = ""

    private val suggestRunnable = Runnable { refreshSuggestions() }
    private var suggestionTask: Future<*>? = null
    private var suggestionRequest = 0
    private var suggestionReady = false
    private var suggestionKnown = false

    private fun cancelSuggestions() {
        suggestionRequest++
        suggestionTask?.cancel(false)
        suggestionTask = null
        handler.removeCallbacks(suggestRunnable)
        suggestionReady = false
        setSuggestions(emptyList())
    }

    private fun scheduleSuggestions() {
        if (!suggestionsShown) return
        suggestionRequest++
        suggestionTask?.cancel(false)
        handler.removeCallbacks(suggestRunnable)
        handler.post(suggestRunnable)
    }

    /** Whether this field gets the bar: text fields only, not passwords or "no suggestions" fields. */
    private fun updateSuggestionsShown(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val noSuggest = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        val shown = !isEditor && Settings.bool(SETTING_SUGGESTIONS, true) && cls == InputType.TYPE_CLASS_TEXT &&
            !privateField && !noSuggest
        if (shown != suggestionsShown) {
            suggestionsShown = shown
            onSuggestionsChanged?.invoke()
        }
        if (!shown) cancelSuggestions()
    }

    private fun setSuggestions(list: List<Suggestion>) {
        if (list == suggestions) return
        suggestions = list
        onSuggestionsChanged?.invoke()
    }

    /** Learned words that complete the word before the cursor, in the case being typed. */
    private fun refreshSuggestions() {
        if (!suggestionsShown || (selStart >= 0 && selStart != selEnd)) return cancelSuggestions()
        val before = inputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return cancelSuggestions()
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'' || before[i - 1] == '’')) i--
        val prefix = before.substring(i)
        // A word whose auto-complete was undone stays protected until a different word is typed.
        if (prefix != suggestionPrefix && prefix != autoRejected) autoRejected = null
        if (prefix != suggestionPrefix) {
            suggestionReady = false
            setSuggestions(emptyList())
        }
        suggestionPrefix = prefix
        if (prefix.isEmpty() || (i > 0 && before[i - 1].isDigit())) return cancelSuggestions()
        if (suggestionReady) return
        val upper = prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() }
        val capital = prefix[0].isUpperCase()
        fun cased(w: UserWords.Word) = when {
            upper -> w.form.uppercase()
            capital -> w.form.replaceFirstChar { it.uppercaseChar() }
            else -> w.form
        }
        val request = ++suggestionRequest
        suggestionTask?.cancel(false)
        val emojiEnabled = Settings.bool(SETTING_EMOJI_SUGGEST, true)
        suggestionTask = UserWords.submit {
            try {
                val matches = UserWords.query(ctx, prefix)
                val words = matches.words.map { Suggestion(cased(it)) }
                val emoji = if (emojiEnabled) EmojiKeywords.lookup(ctx, prefix).firstOrNull() else null
                val result = if (emoji != null) words.take(2) + Suggestion(emoji, emoji = true) else words
                handler.post {
                    if (request == suggestionRequest && suggestionsShown) {
                        suggestionKnown = matches.known
                        suggestionReady = true
                        setSuggestions(result)
                    }
                }
            } catch (e: Exception) {
                Log.w("VKeyboard", "Word suggestions unavailable", e)
            }
        }
    }

    /** Space auto-complete: replaces a word that isn't a learned word with the top suggestion. */
    private fun spaceAccept(): Boolean {
        if (!suggestionsShown || !Settings.bool(SETTING_SPACE_ACCEPT, false)) return false
        refreshSuggestions()
        val typed = suggestionPrefix
        val top = suggestions.firstOrNull()?.takeIf { !it.emoji } ?: return false
        if (!suggestionReady || typed.isEmpty() || typed == autoRejected || suggestionKnown || top.text.equals(typed, true)) return false
        acceptSuggestion(0)
        autoAccepted = typed to top.text
        return true
    }

    /** Backspace right after space auto-complete: back to the word as typed. */
    private fun undoSpaceAccept(): Boolean {
        val (typed, accepted) = autoAccepted ?: return false
        autoAccepted = null
        val ic = inputConnection ?: return false
        val tail = "$accepted "
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText(typed, 1)
        ic.endBatchEdit()
        autoRejected = typed
        return true
    }

    /** Adds the emoji suggestion after the word being typed. */
    fun acceptEmoji() {
        if (!canAcceptSuggestion()) return
        val e = suggestions.firstOrNull { it.emoji } ?: return
        val ic = inputConnection ?: return
        ic.commitText(" " + e.text + " ", 1)
        setSuggestions(emptyList())
    }

    /** Replaces the word being typed with suggestion [rank] (0 = best) and adds a space. */
    fun acceptSuggestion(rank: Int) {
        if (!canAcceptSuggestion()) return
        val s = suggestions.getOrNull(rank) ?: return
        if (s.emoji) return acceptEmoji()
        val word = s.text
        val ic = inputConnection ?: return
        ic.beginBatchEdit()
        if (suggestionPrefix.isNotEmpty()) ic.deleteSurroundingText(suggestionPrefix.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        if (!privateField && Settings.bool(SETTING_LEARN_WORDS, true)) UserWords.submit { UserWords.learn(ctx, word) }
        textExpander.clearUndo()
        setSuggestions(emptyList())
        scheduleAutoShift()
    }

    /** A delayed result must never replace text from another word, selection or private field. */
    private fun canAcceptSuggestion(): Boolean {
        if (!suggestionsShown || privateField || !suggestionReady || selStart != selEnd) return false
        val before = inputConnection?.getTextBeforeCursor(48, 0)?.toString() ?: return false
        var start = before.length
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'' || before[start - 1] == '’')) start--
        return before.substring(start) == suggestionPrefix
    }

    /** Last selection reported by the editor (start == end: just a cursor; -1: unknown). */
    private var selStart = -1
    private var selEnd = -1

    fun onSelectionChanged(start: Int = selStart, end: Int = selEnd) {
        selStart = start
        selEnd = end
        textExpander.clearUndo()
        scheduleAutoShift()
        scheduleSuggestions()
    }

    // ======================= selection slider =======================

    /** Cursor-key steps extend the selection: Shift is on (not just auto-capitals) or text is selected. */
    fun selectionMode() = !isEditor && Settings.bool(SETTING_SELECTION_SLIDER, true) &&
        (shiftPressed() || (selStart >= 0 && selStart != selEnd))

    /**
     * One step of the selection slider: moves the selection's moving end by one character
     * ([dir] -1 / +1) and keeps the other end where it is, so dragging back shrinks it again.
     * Goes through the editor's text directly; where that isn't available (e.g. terminals) or
     * for word steps (Ctrl), sends Shift + the arrow key instead.
     */
    fun extendSelection(dir: Int) {
        val ic = inputConnection ?: return
        val ctrl = metaState and META_CTRL_MASK != 0
        val et = if (ctrl) null else ic.getExtractedText(ExtractedTextRequest(), 0)
        if (et?.text == null || et.selectionStart < 0) {
            clickModified(if (dir < 0) KEYCODE_DPAD_LEFT else KEYCODE_DPAD_RIGHT, metaState or META_SHIFT_ON)
            return
        }
        val anchor = et.startOffset + et.selectionStart
        val focus = et.startOffset + et.selectionEnd
        val next = (focus + dir).coerceIn(et.startOffset, et.startOffset + et.text.length)
        if (next == focus) return
        ic.setSelection(anchor, next)
        selStart = anchor
        selEnd = next
    }

    /**
     * Deletes the word before the cursor: trailing spaces, then a run of letters/digits (or of
     * other symbols). A selection is simply deleted. Without access to the text, sends Ctrl+Backspace.
     */
    fun deleteWordBefore() {
        val ic = inputConnection ?: return
        textExpander.clearUndo()
        if (selStart >= 0 && selStart != selEnd) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(256, 0)?.toString()
        if (before == null) {
            clickModified(KEYCODE_DEL, metaState or META_CTRL_ON)
            return
        }
        val n = before.length - wordStart(before)
        if (n > 0) ic.deleteSurroundingText(n, 0)
        scheduleAutoShift()
    }

    /**
     * Deletes the word after the cursor: leading spaces, then a run of letters/digits (or of
     * other symbols). A selection is simply deleted. Without access to the text, sends
     * Ctrl+Forward Delete.
     */
    fun deleteWordAfter() {
        val ic = inputConnection ?: return
        textExpander.clearUndo()
        if (selStart >= 0 && selStart != selEnd) {
            ic.commitText("", 1)
            return
        }
        val after = ic.getTextAfterCursor(256, 0)?.toString()
        if (after == null) {
            clickModified(KEYCODE_FORWARD_DEL, metaState or META_CTRL_ON)
            return
        }
        val n = wordEnd(after)
        if (n > 0) ic.deleteSurroundingText(0, n)
        scheduleAutoShift()
    }

    private fun isWordChar(ch: Char) = ch.isLetterOrDigit() || ch == '_'

    /** Index in [s] where the last word (plus the spaces after it) begins. */
    private fun wordStart(s: CharSequence): Int {
        var i = s.length
        while (i > 0 && s[i - 1].isWhitespace()) i--
        if (i > 0) {
            val word = isWordChar(s[i - 1])
            while (i > 0 && !s[i - 1].isWhitespace() && isWordChar(s[i - 1]) == word) i--
        }
        return i
    }

    /** Length of the first word in [s], including the spaces before it. */
    private fun wordEnd(s: CharSequence): Int {
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        if (i < s.length) {
            val word = isWordChar(s[i])
            while (i < s.length && !s[i].isWhitespace() && isWordChar(s[i]) == word) i++
        }
        return i
    }

    /** Up/down while selecting: Shift + the arrow key, which editors extend line by line. */
    fun extendSelectionVertically(keyCode: Int) {
        clickModified(keyCode, metaState or META_SHIFT_ON)
    }

    fun updateAutoShift() {
        val want = !isEditor && Settings.bool(SETTING_AUTO_CAPS, true) && !privateField &&
                (inputConnection?.getCursorCapsMode(editorInputType) ?: 0) != 0
        if (want != autoShift) {
            autoShift = want
            invalidate()
        }
    }

    /** "word" + space + space (quickly) → "word. " like on most phone keyboards. */
    private fun doubleSpacePeriod(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSpaceTime > 700 || !Settings.bool(SETTING_DOUBLE_SPACE, true)) return false
        if (metaState != 0 || privateField || keybType != KeybType.NORMAL) return false
        val ic = inputConnection ?: return false
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
        if (before.length < 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(". ", 1)
        ic.endBatchEdit()
        lastSpaceTime = 0L
        scheduleAutoShift()
        return true
    }

    /** Switches by name or by a special target (@NEXT, @PREV, @NUM, @TEXT, @EMOJI). */
    fun goToLayout(target: String) {
        when (target) {
            LAYOUT_PREV -> prevLayout()
            LAYOUT_NEXT -> nextLayout()
            LAYOUT_NUM -> numLayout()
            LAYOUT_TEXT -> textLayout()
            LAYOUT_EMOJI -> emojiLayout()
            else -> switchLayout(target)
        }
    }

    fun ctrlPressed() = metaState and META_CTRL_MASK != 0
    fun altPressed() = metaState and META_ALT_MASK != 0
    fun shiftPressed() = metaState and META_SHIFT_MASK != 0
    fun metaPressed() = metaState and META_META_MASK != 0

    fun getShifted(code: Int, sh: Boolean) =
        if (sh && Character.isLetter(code)) Character.toUpperCase(code) else code

    fun isMetaActive(key: Key) = key.num(KEY_MOD_META).let { it != 0 && metaState and it == it }
    fun isMetaLocked(key: Key) = metaLatches[key] == Latch.LOCKED

    /**
     * Modifier key tap. With `metaOneShot` (default): off → one-shot → locked → off.
     * Otherwise a simple on/off toggle.
     */
    fun toggleMeta(key: Key) {
        val active = isMetaActive(key)
        val oneShot = Settings.bool(SETTING_META_ONE_SHOT, true)
        when {
            !active -> {
                metaState = metaState or key.num(KEY_MOD_META)
                keyModified(ACTION_DOWN, key.num(KEY_CODE))
                metaLatches[key] = if (oneShot) Latch.ONE_SHOT else Latch.LOCKED
            }
            oneShot && metaLatches[key] == Latch.ONE_SHOT -> metaLatches[key] = Latch.LOCKED
            else -> metaOff(key)
        }
        invalidate()
    }

    /** Turns modifier [key] on and keeps it on (like tapping it twice), e.g. Caps Lock from a circle on Shift. */
    fun lockMeta(key: Key) {
        if (!isMetaActive(key)) {
            metaState = metaState or key.num(KEY_MOD_META)
            keyModified(ACTION_DOWN, key.num(KEY_CODE))
        }
        metaLatches[key] = Latch.LOCKED
        invalidate()
    }

    fun metaOff(key: Key) {
        if (!isMetaActive(key)) {
            metaLatches.remove(key)
            return
        }
        metaState = metaState and key.num(KEY_MOD_META).inv() or hardMetaState
        keyModified(ACTION_UP, key.num(KEY_CODE))
        metaLatches.remove(key)
        invalidate()
    }

    /** Releases one-shot modifiers after a regular key was used. Modifiers still held by a finger stay on. */
    fun releaseOneShotMeta() {
        if (metaLatches.isEmpty()) return
        val held = pointers.values
        for ((key, latch) in metaLatches.toList()) if (latch == Latch.ONE_SHOT && key !in held) metaOff(key)
    }

    // ======================= key combinations =======================

    /** A finger went down on a regular key: every modifier currently held becomes part of a combination. */
    private fun markHeldModifiersUsed() {
        for (k in pointers.values) if (k.isMeta && isMetaActive(k)) comboUsed.add(k)
    }

    fun metaPressed(key: Key) {
        comboUsed.remove(key)
    }

    /**
     * Modifier key lifted. If it was held while other keys were pressed it acts like a physical
     * modifier and is released now (or together with a key that is still down); a plain tap keeps
     * its latch (one-shot / locked).
     */
    fun metaReleased(key: Key) {
        if (!comboUsed.remove(key) || !isMetaActive(key)) return
        if (pointers.values.any { !it.isMeta }) metaLatches[key] = Latch.ONE_SHOT
        else metaOff(key)
        invalidate()
    }

    /** Sends [spec] (`"ctrl+shift+t"`) as a key combination. Returns false if it isn't one (caller treats it as text). */
    fun sendCombo(spec: String, requireModifier: Boolean = true): Boolean {
        if (isEditor) return false
        val combo = Combos.parse(spec, requireModifier) ?: return false
        var mask = 0
        for (m in combo.mods) {
            mask = mask or m.mask
            keyModified(ACTION_DOWN, m.keyCode, metaState or mask)
        }
        clickModified(combo.keyCode, metaState or mask)
        for (m in combo.mods.asReversed()) {
            mask = mask and m.mask.inv()
            keyModified(ACTION_UP, m.keyCode, metaState or mask)
        }
        return true
    }

    /** `"macro:name"` runs a macro, `"ctrl+c"` sends a combination. Returns false for anything else. */
    fun runSpecial(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed == VOICE_ACTION) {
            voice.toggle()
            return true
        }
        if (trimmed == COMPOSE_ACTION) {
            compose.toggle()
            invalidate()
            return true
        }
        if (DeadKeys.isDeadKey(trimmed)) {
            val name = DeadKeys.nameOf(trimmed)
            if (compose.dead == name) {
                // Same dead key twice: type the accent itself.
                compose.cancel()
                DeadKeys.spacing(name).takeIf { it.isNotEmpty() }?.let { setText(it) }
            } else {
                compose.arm(name)
            }
            invalidate()
            return true
        }
        if (trimmed == COMPLETE_EMOJI_ACTION) {
            acceptEmoji()
            return true
        }
        if (trimmed.startsWith(COMPLETE_ACTION_PREFIX)) {
            trimmed.removePrefix(COMPLETE_ACTION_PREFIX).toIntOrNull()?.let { acceptSuggestion(it - 1) }
            return true
        }
        if (trimmed == CLIPBOARD_ACTION) {
            ime?.showClipboard()
            return true
        }
        if (trimmed == DELETE_WORD_ACTION) {
            deleteWordBefore()
            return true
        }
        if (trimmed == DELETE_WORD_FORWARD_ACTION) {
            deleteWordAfter()
            return true
        }
        if (trimmed.startsWith(TR_VOICE_PREFIX) || trimmed.startsWith(TR_VOICE_ALT_PREFIX)) {
            voice.toggle(VoiceInput.parseTrVoiceSpec(trimmed))
            return true
        }
        if (value.startsWith(LAYOUT_ACTION_PREFIX)) {
            val target = value.removePrefix(LAYOUT_ACTION_PREFIX).trim()
            if (target.isNotEmpty()) goToLayout(target)
            return true
        }
        if (value.startsWith(Macros.PREFIX)) {
            runMacro(value.removePrefix(Macros.PREFIX).trim())
            return true
        }
        return sendCombo(value)
    }

    fun runMacro(name: String) {
        if (isEditor || name.isEmpty()) return
        val steps = Macros.steps(ctx, name)
        if (steps.isEmpty()) {
            if (!Macros.exists(ctx, name)) err("Macro not found: $name")
            return
        }
        runSteps(steps, 0)
    }

    private fun runSteps(steps: List<Macros.Step>, from: Int) {
        var i = from
        while (i < steps.size) {
            val step = steps[i++]
            when (step.type) {
                Macros.Type.WAIT -> {
                    val ms = (step.value.trim().toIntOrNull() ?: 0).coerceIn(0, Macros.MAX_WAIT_MS)
                    handler.postDelayed({ runSteps(steps, i) }, ms.toLong())
                    return
                }
                Macros.Type.KEY -> if (!sendCombo(step.value, requireModifier = false)) err("Bad macro step: ${step.value}")
                Macros.Type.TEXT -> onText(step.value)
            }
        }
    }

    // ======================= layouts =======================

    fun reloadLayout() {
        Settings.loadVars(ctx)
        if (!pickLayout(currentLayoutName)) {
            // Missing or broken layout: fall back instead of showing an empty keyboard.
            val fallback = Settings.str(SETTING_DEF_LAYOUT).takeIf { it != currentLayoutName && it.isNotBlank() }
                ?: Bootstrap.textLayouts(ctx).firstOrNull { it != currentLayoutName }
            val broken = currentLayoutName
            if (fallback != null && pickLayout(fallback)) currentLayoutName = fallback
            else {
                Bootstrap.ensureInstalled(ctx)
                PFile.install(ctx, DEFAULT_LAYOUT_ASSET)
                pickLayout(DEFAULT_LAYOUT_ASSET)
                currentLayoutName = DEFAULT_LAYOUT_ASSET
            }
            if (broken.isNotBlank() && PFile(ctx, broken).exists() && !isEditor) {
                log("Layout '$broken' could not be loaded")
                ctx.startActivity(Intent(ctx, RawEditor::class.java).putExtra("name", broken).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        if (Bootstrap.isTextLayout(currentLayoutName)) lastTextLayout = currentLayoutName
        // Always re-measure: even the same layout may need a new height (height settings changed
        // while the keyboard was hidden). measure() itself skips the work when nothing changed.
        view.requestLayout()
        invalidate()
    }

    /** Makes [name] current. Returns false if it can't be loaded. */
    private fun pickLayout(name: String): Boolean {
        if (name.isBlank()) return false
        val numberRow = Settings.num(SETTING_NUMBER_ROW, NumberRow.OFF)
        if (numberRow != loadedNumberRow) {
            loaded.clear()
            loadedNumberRow = numberRow
        }
        val cached = loaded[name]
        val file = PFile(ctx, name)
        if (!file.exists()) PFile.install(ctx, name)
        if (cached != null && cached.lastModified == file.lastModified()) {
            currentLayout = cached
            return true
        }
        val layout = loadLayout(name) ?: return false
        loaded[name] = layout
        currentLayout = layout
        return true
    }

    fun loadLayout(name: String): KeybLayout? {
        val file = PFile(ctx, name)
        if (!file.exists()) return null
        val node = FlexParser.parse(file.read())
        // The layout editor shows (and saves) the file exactly as it is.
        if (!isEditor) NumberRow.apply(node, name, Settings.num(SETTING_NUMBER_ROW, NumberRow.OFF))
        val layout = KeybLayout(this, node)
        layout.lastModified = file.lastModified()
        return layout.takeIf { it.loaded }
    }

    /** Layout editor: loads exactly [name] (no fallbacks, no cache). */
    fun openForEditing(name: String): Boolean {
        val layout = loadLayout(name) ?: return false
        currentLayout = layout
        currentLayoutName = name
        view.requestLayout()
        invalidate()
        return true
    }

    fun switchLayout(name: String) {
        currentLayoutName = name
        autoNumSwitched = false
        reloadLayout()
    }

    private fun cycleLayout(step: Int) {
        val list = Bootstrap.textLayouts(ctx)
        if (list.isEmpty()) return
        val pos = list.indexOf(lastTextLayout)
        val next = if (pos < 0) 0 else Math.floorMod(pos + step, list.size)
        lastTextLayout = list[next]
        switchLayout(lastTextLayout)
    }

    fun prevLayout() = cycleLayout(-1)
    fun nextLayout() = cycleLayout(1)
    fun numLayout() = switchLayout(NUM_FILENAME)
    fun textLayout() = switchLayout(lastTextLayout.ifBlank { Settings.str(SETTING_DEF_LAYOUT) })
    fun emojiLayout() = switchLayout(EMOJI_FILENAME)

    private fun setKeybType(inputType: Int) {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        keybType = when (cls) {
            InputType.TYPE_CLASS_NUMBER -> KeybType.NUMBER
            InputType.TYPE_CLASS_PHONE -> KeybType.PHONE
            InputType.TYPE_CLASS_TEXT -> when (variation) {
                InputType.TYPE_TEXT_VARIATION_URI -> KeybType.URI
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> KeybType.EMAIL
                else -> KeybType.NORMAL
            }
            InputType.TYPE_CLASS_DATETIME -> when (variation) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> KeybType.DATE
                InputType.TYPE_DATETIME_VARIATION_TIME -> KeybType.TIME
                else -> KeybType.DATETIME
            }
            else -> KeybType.NORMAL
        }
    }

    // ======================= misc =======================

    /** Color from the inheritance chain; malformed values fall back to the default. */
    fun color(name: String, node: FlexNode): Int {
        val v = node.str(name)
        return if (v.isEmpty()) Theme.parseColor(Settings.defaults.str(name), DEFAULT_COLOR)
        else Theme.parseColor(v, DEFAULT_COLOR)
    }

    fun vibrate(key: Key, s: String) {
        val ms = key.num(s).toLong()
        if (ms < 10) return
        val now = System.currentTimeMillis()
        if (vibrationMs + ms > now) return
        vibrationMs = now
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(ms)
        } catch (_: Exception) {
        }
    }

    fun prStack(e: Throwable) {
        Log.e(TAG, "Error", e)
        if (Settings.bool(SETTING_DEBUG) || Settings.bool(SETTING_ERROR_TOASTS, true)) toast(e.stackTraceToString())
    }

    /** A configuration problem the user should see (respects the error-toast setting). */
    fun err(s: String) {
        Log.w(TAG, s)
        if (Settings.bool(SETTING_DEBUG) || Settings.bool(SETTING_ERROR_TOASTS, true)) toast(s)
    }

    fun log(s: String) {
        Log.d(TAG, s)
        toast(s)
    }

    private fun toast(s: String) {
        Toast.makeText(ctx, s.substring(0, min(150, s.length)), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "VKeyboard"
        private const val DEFAULT_COLOR = 0xffff00ff.toInt()
    }
}

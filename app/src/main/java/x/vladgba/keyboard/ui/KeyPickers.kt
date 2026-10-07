package x.vladgba.keyboard.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.*
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.keyboard.Combos
import x.vladgba.keyboard.keyboard.Macros

/** Searchable list of all Android key codes. */
internal object KeyCodePicker {
    class Entry(val code: Int, val name: String, val label: String) {
        override fun toString() = "$label   ·   $code   ($name)"
    }

    val all: List<Entry> by lazy {
        (1..KeyEvent.getMaxKeyCode()).mapNotNull { code ->
            val raw = KeyEvent.keyCodeToString(code)
            if (!raw.startsWith("KEYCODE_")) null else Entry(code, Combos.keyName(code), Combos.keyLabel(code))
        }
    }

    /** With [onPick] null, tapping an entry copies its layout value (`-61`) to the clipboard. */
    fun show(ctx: Context, onPick: ((Entry) -> Unit)? = null) {
        val search = EditText(ctx).apply {
            hint = ctx.getString(R.string.ui_search_key)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val list = ListView(ctx)
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_list_item_1, ArrayList(all))
        list.adapter = adapter
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0)
            addView(TextView(ctx).apply {
                text = ctx.getString(if (onPick == null) R.string.ui_keycodes_note_copy else R.string.ui_keycodes_note_pick)
                setTextColor(COLOR_SECONDARY_TEXT)
            })
            addView(search)
            addView(list, LinearLayout.LayoutParams(-1, ctx.dp(360)))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ui_keycodes_title)
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase().replace(' ', '_')
                adapter.clear()
                adapter.addAll(all.filter {
                    q.isEmpty() || it.name.contains(q) || it.code.toString() == q.removePrefix("-")
                })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos) ?: return@setOnItemClickListener
            if (onPick != null) {
                onPick(e)
                dialog.dismiss()
            } else {
                ctx.copyToClipboard("-${e.code}", ctx.getString(R.string.ui_copied, "-${e.code}"))
            }
        }
        dialog.show()
    }
}

/** Dialog to build `ctrl+shift+t`: modifier check boxes + key, or just press it on a physical keyboard. */
internal object ComboBuilder {
    fun show(ctx: Context, initial: String, onDone: (Combos.Combo) -> Unit) {
        val start = Combos.parse(initial, requireModifier = false)
        val boxes = Combos.MODIFIERS.map { m ->
            CheckBox(ctx).apply {
                text = m.label
                isChecked = start?.mods?.contains(m) == true
            }
        }
        val keyInput = EditText(ctx).apply {
            hint = ctx.getString(R.string.ui_combo_key_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(start?.let { Combos.keyName(it.keyCode) } ?: "")
        }
        val preview = TextView(ctx).apply {
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, ctx.dp(8), 0, ctx.dp(4))
        }
        val modsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            boxes.forEach { addView(it) }
        }
        val pickBtn = Button(ctx).apply { text = ctx.getString(R.string.ui_choose_key) }
        val keyRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(pickBtn)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(TextView(ctx).apply { text = ctx.getString(R.string.ui_combo_hold); setTextColor(COLOR_SECONDARY_TEXT) })
            addView(HorizontalScrollView(ctx).apply { addView(modsRow) })
            addView(TextView(ctx).apply { text = ctx.getString(R.string.ui_combo_then_press); setTextColor(COLOR_SECONDARY_TEXT) })
            addView(keyRow)
            addView(preview)
            addView(TextView(ctx).apply { text = ctx.getString(R.string.ui_combo_capture_tip); setTextColor(COLOR_SECONDARY_TEXT); textSize = 12f })
        }

        fun current(): Combos.Combo? {
            val code = Combos.keyCode(keyInput.text.toString()) ?: return null
            return Combos.Combo(Combos.MODIFIERS.filterIndexed { i, _ -> boxes[i].isChecked }, code)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.ui_combo_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> current()?.let(onDone) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun refresh() {
            val c = current()
            if (c == null) {
                preview.text = if (keyInput.text.isBlank()) ctx.getString(R.string.ui_combo_choose_key) else ctx.getString(R.string.ui_unknown_key, keyInput.text.toString())
                preview.setTextColor(COLOR_WARN)
            } else {
                preview.text = c.describe()
                preview.setTextColor(COLOR_OK)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = c != null
        }

        boxes.forEach { it.setOnCheckedChangeListener { _, _ -> refresh() } }
        keyInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        pickBtn.setOnClickListener { KeyCodePicker.show(ctx) { keyInput.setText(it.name) } }

        // A physical keyboard press fills everything in at once.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            // Only physical keyboards: on-screen keyboards (VKeyboard sends letters as key events) just type.
            if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 || event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD)
                return@setOnKeyListener false
            if (KeyEvent.isModifierKey(keyCode)) return@setOnKeyListener true
            val meta = event.metaState
            boxes[0].isChecked = meta and KeyEvent.META_CTRL_ON != 0
            boxes[1].isChecked = meta and KeyEvent.META_ALT_ON != 0
            boxes[2].isChecked = meta and KeyEvent.META_SHIFT_ON != 0
            boxes[3].isChecked = meta and KeyEvent.META_META_ON != 0
            boxes[4].isChecked = meta and KeyEvent.META_FUNCTION_ON != 0
            keyInput.setText(Combos.keyName(keyCode))
            true
        }
        dialog.show()
        refresh()
    }
}

/**
 * Editor for values of long-press / heavy press / swipe / popup chars. Explains as you type what
 * the value will do, and offers pickers so nobody has to remember key codes.
 */
internal object ActionPrompt {
    /** Mirrors how the keyboard interprets these values (see Key.emit). */
    fun describe(ctx: Context, value: String, numbersAreKeys: Boolean = false): Pair<String, Boolean> {
        val v = value.trim()
        if (value.isEmpty()) return ctx.getString(R.string.ui_action_nothing) to true
        if (v == VOICE_ACTION) return ctx.getString(R.string.vo_action) to true
        if (v.startsWith(LAYOUT_ACTION_PREFIX)) {
            val t = v.removePrefix(LAYOUT_ACTION_PREFIX).trim()
            return ctx.getString(R.string.ui_action_layout, layoutTargetName(ctx, t)) to t.isNotEmpty()
        }
        if (v.startsWith(Macros.PREFIX)) {
            val name = v.removePrefix(Macros.PREFIX).trim()
            return if (Macros.exists(ctx, name)) ctx.getString(R.string.ui_action_macro, name) to true
            else ctx.getString(R.string.ui_action_macro_missing, name) to false
        }
        val n = v.toIntOrNull()
        if (n != null && (n < 0 || numbersAreKeys)) {
            val code = if (n < 0) -n else n
            return if (n < 0) ctx.getString(R.string.ui_action_key, Combos.keyLabel(code)) to true
            else ctx.getString(R.string.ui_action_char, String(Character.toChars(code))) to true
        }
        Combos.parse(v)?.let { return ctx.getString(R.string.ui_action_key, it.describe()) to true }
        return ctx.getString(R.string.ui_action_text, value) to true
    }

    fun show(ctx: Context, title: CharSequence, value: String, numbersAreKeys: Boolean = false, onOk: (String) -> Unit) {
        val input = EditText(ctx).apply {
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val meaning = TextView(ctx).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)); textSize = 15f }
        fun refresh() {
            val (text, ok) = describe(ctx, input.text.toString(), numbersAreKeys)
            meaning.text = text
            meaning.setTextColor(if (ok) COLOR_OK else COLOR_WARN)
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refresh()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fun small(textRes: Int, onClick: () -> Unit) = Button(ctx).apply {
            text = ctx.getString(textRes)
            textSize = 12f
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(small(R.string.ui_btn_key) { KeyCodePicker.show(ctx) { input.setText("-${it.code}") } })
            addView(small(R.string.ui_btn_combo) { ComboBuilder.show(ctx, input.text.toString()) { input.setText(it.spec()) } })
            addView(small(R.string.ui_btn_macro) { pickMacro(ctx) { input.setText(Macros.PREFIX + it) } })
            addView(small(R.string.ui_btn_layout) { pickLayout(ctx) { input.setText(LAYOUT_ACTION_PREFIX + it) } })
            addView(small(R.string.ui_btn_voice) { input.setText(VOICE_ACTION) })
            addView(small(R.string.ui_btn_clear) { input.setText("") })
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
            addView(TextView(ctx).apply { text = ctx.getString(R.string.ui_action_help); setTextColor(COLOR_SECONDARY_TEXT); textSize = 13f })
            addView(input)
            addView(meaning)
            addView(HorizontalScrollView(ctx).apply { addView(buttons) })
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        refresh()
    }

    private val SPECIAL_TARGETS = listOf(
        LAYOUT_NEXT to R.string.ui_layout_next, LAYOUT_PREV to R.string.ui_layout_prev, LAYOUT_NUM to R.string.ui_layout_num,
        LAYOUT_TEXT to R.string.ui_layout_text, LAYOUT_EMOJI to R.string.ui_layout_emoji,
    )

    fun layoutTargetName(ctx: Context, t: String) =
        SPECIAL_TARGETS.firstOrNull { it.first == t }?.let { ctx.getString(it.second) } ?: BuiltIns.layoutTitle(ctx, t)

    fun pickLayout(ctx: Context, onPick: (String) -> Unit) {
        val files = PFile.list(ctx, LAYOUT_EXT).filter { it != SETTINGS_FILENAME }
        val values = SPECIAL_TARGETS.map { it.first } + files
        Ask.choice(ctx, ctx.getString(R.string.enter_layout_name), values.map { layoutTargetName(ctx, it) }) { onPick(values[it]) }
    }

    fun pickMacro(ctx: Context, onPick: (String) -> Unit) {
        val names = Macros.names(ctx)
        if (names.isEmpty()) {
            Ask.info(ctx, ctx.getString(R.string.ui_macros), ctx.getString(R.string.ui_no_macros_yet))
            return
        }
        Ask.choice(ctx, ctx.getString(R.string.ui_pick_macro), names) { onPick(names[it]) }
    }
}

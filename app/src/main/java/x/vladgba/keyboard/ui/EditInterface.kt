package x.vladgba.keyboard.ui

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.keyboard.KeybLayout
import x.vladgba.keyboard.keyboard.Key
import x.vladgba.keyboard.keyboard.Keyboard
import x.vladgba.keyboard.keyboard.Combos

/**
 * Touch handling of the layout editor:
 *  - tap a key: open the key editor dialog
 *  - drag a key: it follows the finger, a marker shows where it will land (any row, any position);
 *    drop it above or below the keyboard to put it in a new row. Rows left empty are removed.
 */
class EditInterface(private val c: Keyboard, private val onChange: () -> Unit) {
    private var pressX = -1
    private var pressY = -1
    private var editingKey: Key? = null
    private val ctx get() = c.ctx
    private val layout get() = c.currentLayout

    private fun key() = editingKey!!

    // ======================= drag and drop =======================

    /** Where a dragged key would land: before [before] in [row], or in a new row at index [newRowAt]. */
    private data class Drop(val row: KeybLayout.Row?, val before: Key?, val newRowAt: Int = -1)

    private var dragging = false
    private var drop: Drop? = null
    private val slop get() = android.view.ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f
    private val accentPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    fun onPress(key: Key, x: Int, y: Int) {
        editingKey = key
        pressX = x
        pressY = y
        dragging = false
        drop = null
    }

    fun onMove(x: Int, y: Int) {
        val k = editingKey ?: return
        if (!dragging && (kotlin.math.abs(x - pressX) > slop || kotlin.math.abs(y - pressY) > slop)) dragging = true
        if (!dragging) return
        k.floatingPos = Key.Point(k.x + x - pressX, k.y + y - pressY)
        val d = findDrop(k, x, y)
        if (d != drop) {
            drop = d
            c.view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun onCancel() {
        editingKey?.floatingPos = null
        editingKey = null
        dragging = false
        drop = null
    }

    fun onRelease(x: Int, y: Int) {
        val k = editingKey ?: return
        k.floatingPos = null
        val d = drop
        if (dragging) {
            if (d != null && moveTo(k, d)) changed()
        } else {
            showKeyDialog()
        }
        dragging = false
        drop = null
        c.invalidate()
    }

    /**
     * The row under the finger and the key the dragged one goes before (null = row end).
     * Above or below the keyboard means "a new row there".
     */
    private fun findDrop(k: Key, x: Int, y: Int): Drop {
        val rows = layout.rows
        if (y < 0) return Drop(null, null, 0)
        if (y >= layout.height) return Drop(null, null, rows.size)
        val row = rows.firstOrNull { y < it.y + it.height } ?: rows.last()
        val before = row.keys.firstOrNull { it !== k && it.width > 0 && x < it.x + it.width / 2 }
        return Drop(row, before)
    }

    /** Returns false when the drop would leave the key where it is. */
    private fun moveTo(k: Key, d: Drop): Boolean {
        val from = k.row
        val target = d.row
        if (target === from) {
            val i = from.keys.indexOf(k)
            val next = from.keys.drop(i + 1).firstOrNull { it.width > 0 }
            if (d.before === next || d.before === k) return false // same place
        }
        if (target == null && from.keys.size == 1) {
            // Alone in its row: "new row" next to it changes nothing.
            val i = layout.rows.indexOf(from)
            if (d.newRowAt == i || d.newRowAt == i + 1) return false
        }
        from.remove(k)
        val dest = target ?: layout.addRow(d.newRowAt)
        val index = d.before?.let { dest.keys.indexOf(it) }?.takeIf { it >= 0 } ?: dest.keys.size
        dest.add(k, index)
        k.row = dest
        k.parent = dest
        if (from.keys.isEmpty() && from !== dest) layout.remove(from)
        return true
    }

    /** Insertion marker and the lifted key, drawn over the layout while dragging. */
    fun drawOverlay(canvas: android.graphics.Canvas, w: Int, h: Int) {
        val k = editingKey ?: return
        val d = drop
        if (dragging && d != null) {
            val p = accentPaint
            p.color = c.color(COLOR_KEY_PRESSED_MOD_BACKGROUND, k)
            val bar = ctx.resources.displayMetrics.density * 4
            val row = d.row
            if (row == null) {
                // New row: a bar along the top or bottom edge with a label.
                val top = d.newRowAt == 0
                val y0 = if (top) 0f else h - bar
                canvas.drawRect(0f, y0, w.toFloat(), y0 + bar, p)
                p.textAlign = android.graphics.Paint.Align.CENTER
                p.textSize = bar * 3.5f
                canvas.drawText(ctx.getString(R.string.le_new_row), w / 2f, if (top) bar * 5f else h - bar * 2.5f, p)
            } else {
                val visible = row.keys.filter { it !== k && it.width > 0 }
                val xLine = d.before?.x?.toFloat() ?: visible.lastOrNull()?.let { (it.x + it.width).toFloat() } ?: 0f
                val x0 = (xLine - bar / 2).coerceIn(0f, w - bar)
                canvas.drawRoundRect(android.graphics.RectF(x0, row.y.toFloat() + bar, x0 + bar, (row.y + row.height).toFloat() - bar), bar, bar, p)
            }
        }
        k.drawFloating(canvas)
    }

    private fun changed() {
        layout.relayout()
        c.view.requestLayout()
        c.invalidate()
        onChange()
    }

    // ======================= key dialog =======================

    private fun showKeyDialog() {
        val dialog = Dialog(ctx)
        dialog.setContentView(R.layout.key_edit)
        dialog.setOnDismissListener { changed() }
        hideAll(dialog)

        dialog.findViewById<RadioButton>(
            when (key().str(KEY_MODE)) {
                KEY_MODE_JOY -> R.id.radio_joy
                KEY_MODE_META -> R.id.radio_meta
                KEY_MODE_RANDOM -> R.id.radio_random
                else -> R.id.radio_popup
            }
        ).isChecked = true

        dialog.findViewById<Button>(R.id.button_hard).setOnClickListener { actionEditor(KEY_HARD_PRESS, null, ctx.getString(R.string.ui_heavy_press_action)) }
        dialog.findViewById<Button>(R.id.button_hold).setOnClickListener { actionEditor(KEY_HOLD, null, ctx.getString(R.string.ui_long_press_action)) }
        dialog.findViewById<Button>(R.id.key_meta_label).setOnClickListener { textEditor(KEY_KEY, null, ctx.getString(R.string.enter_text)) }
        dialog.findViewById<Button>(R.id.meta_keycode).apply {
            text = ctx.getString(R.string.ui_modifier_pick)
            setOnClickListener { modifierPicker() }
        }
        tapActionSetup(dialog.findViewById(R.id.button_tap_action))
        dialog.findViewById<Button>(R.id.meta_mask).setOnClickListener { textEditor(KEY_MOD_META, null, ctx.getString(R.string.enter_meta_code)) }
        dialog.findViewById<Button>(R.id.button_layout).setOnClickListener { layoutPicker() }
        dialog.findViewById<Button>(R.id.button_sound).setOnClickListener { textEditor(KEY_SOUND_PRESS, null, ctx.getString(R.string.sound)) }

        dialog.findViewById<ImageButton>(R.id.button_delete).setOnClickListener { deleteKeyDialog(dialog) }
        dialog.findViewById<Button>(R.id.button_edit_row).setOnClickListener { rowDialog(dialog) }
        dialog.findViewById<Button>(R.id.button_style).setOnClickListener { styleDialog() }

        keyModeSetup(dialog.findViewById(R.id.rad_group), dialog.findViewById(R.id.rad_group2))
        showChecked(dialog, R.id.radio_joy, R.id.edit_joy, KEY_MODE_JOY)
        showChecked(dialog, R.id.radio_meta, R.id.edit_meta, KEY_MODE_META)
        showChecked(dialog, R.id.radio_popup, R.id.edit_popup, KEY_MODE_POPUP)
        showChecked(dialog, R.id.radio_random, R.id.edit_random, KEY_MODE_RANDOM)

        midKeyEditing(dialog.findViewById(R.id.button_midpopup))
        midKeyEditing(dialog.findViewById(R.id.button_midjoy))

        joyActionEdit(dialog, R.id.button22, KEY_TOP_ACTION, R.string.enter_keycode_swipe_top)
        joyActionEdit(dialog, R.id.button42, KEY_LEFT_ACTION, R.string.enter_keycode_swipe_left)
        joyActionEdit(dialog, R.id.button52, KEY_RIGHT_ACTION, R.string.enter_keycode_swipe_right)
        joyActionEdit(dialog, R.id.button72, KEY_BOTTOM_ACTION, R.string.enter_keycode_swipe_bottom)

        val popupButtons = intArrayOf(R.id.button1, R.id.button2, R.id.button3, R.id.button4, R.id.button5, R.id.button6, R.id.button7, R.id.button8)
        for ((i, id) in popupButtons.withIndex()) {
            val btn = dialog.findViewById<Button>(id)
            btn.text = key().str(i)
            btn.setOnClickListener {
                ActionPrompt.show(ctx, ctx.getString(R.string.ui_popup_char), key().str(i)) { value ->
                    key()[i] = value
                    btn.text = value
                    c.invalidate()
                }
            }
        }

        randomListSetup(dialog)
        dialog.show()
    }

    private fun deleteKeyDialog(parent: Dialog) {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.confirm_title)
            .setMessage(R.string.confirm_delete_key)
            .setPositiveButton(R.string.yes) { _, _ ->
                key().row.remove(key())
                parent.dismiss()
            }
            .setNeutralButton(R.string.delete_row) { _, _ ->
                layout.remove(key().row)
                parent.dismiss()
            }
            .setNegativeButton(R.string.no) { d, _ -> d.cancel() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.RED)
    }

    private fun rowDialog(parent: Dialog) {
        val row = key().row
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.row_preferences)
            .setView(R.layout.row_edit)
            .create()
        dialog.show()
        // Bug fix: the height field used to be written back (empty) as soon as the dialog opened.
        val height = dialog.findViewById<EditText>(R.id.row_height)
        height.text = editable(row.params[ROW_HEIGHT] as? String ?: "")
        dialog.setOnDismissListener {
            setOrRemove(row, ROW_HEIGHT, height.text.toString())
            changed()
        }
        dialog.findViewById<ImageButton>(R.id.button_delete).setOnClickListener {
            confirm(ctx, R.string.confirm_title, R.string.confirm_delete_row) {
                layout.remove(row)
                dialog.dismiss()
                parent.dismiss()
            }
        }
        colorButton(dialog, row)
        visibilityCheckbox(dialog, row)
    }

    private fun styleDialog() {
        val k = key()
        val dialog = AlertDialog.Builder(ctx).setView(R.layout.key_style_edit).create()
        dialog.show()
        val width = dialog.findViewById<EditText>(R.id.text_width).apply { text = editable(k.params[KEY_WIDTH] as? String ?: "") }
        val height = dialog.findViewById<EditText>(R.id.text_height).apply { text = editable(k.row.params[ROW_HEIGHT] as? String ?: "") }
        val padding = dialog.findViewById<EditText>(R.id.text_padding).apply { text = editable(k.params[KEY_PADDING] as? String ?: "") }
        // Bug fix: radius was read from the row but written to the key.
        val radius = dialog.findViewById<EditText>(R.id.text_radius).apply { text = editable(k.params[KEY_BORDER_RADIUS] as? String ?: "") }
        dialog.setOnDismissListener {
            setOrRemove(k, KEY_WIDTH, width.text.toString())
            setOrRemove(k.row, ROW_HEIGHT, height.text.toString())
            setOrRemove(k, KEY_PADDING, padding.text.toString())
            setOrRemove(k, KEY_BORDER_RADIUS, radius.text.toString())
            changed()
        }
        borderCheckbox(dialog, R.id.checkbox_border_top, "t")
        borderCheckbox(dialog, R.id.checkbox_border_left, "l")
        borderCheckbox(dialog, R.id.checkbox_border_right, "r")
        borderCheckbox(dialog, R.id.checkbox_border_bottom, "b")
        visibilityCheckbox(dialog, k)
        colorButton(dialog, k)
    }

    /** Empty input removes the param so the value is inherited again (instead of storing ""). */
    private fun setOrRemove(node: FlexNode, name: String, value: String) {
        if (value.isBlank()) node.params.remove(name) else node[name] = value.trim()
    }

    private fun randomListSetup(dialog: Dialog) {
        val container = dialog.findViewById<LinearLayout>(R.id.rand_container) ?: return
        val list = key().params[KEY_RANDOM] as? FlexNode ?: FlexNode().also { key()[KEY_RANDOM] = it }

        fun render() {
            container.removeAllViews()
            for (i in 0 until list.childCount()) {
                val item = LayoutInflater.from(ctx).inflate(R.layout.rand_item, container, false)
                val text = item.findViewById<EditText>(R.id.layout_name)
                text.text = editable(list.str(i))
                text.setOnFocusChangeListener { _, focused -> if (!focused && i < list.childCount()) list[i] = text.text.toString() }
                item.findViewById<ImageButton>(R.id.layout_delete).setOnClickListener {
                    list.childs.removeAt(i)
                    render()
                }
                container.addView(item)
            }
        }
        dialog.findViewById<Button>(R.id.rand_add)?.setOnClickListener {
            prompt(ctx.getString(R.string.enter_text), "") {
                list.childs.add(it)
                render()
            }
        }
        render()
    }

    private fun colorButton(d: AlertDialog, node: FlexNode) {
        d.findViewById<Button>(R.id.button_edit_colors).setOnClickListener {
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(R.string.color_settings)
                .setView(R.layout.color_settings)
                .setPositiveButton(android.R.string.ok) { x, _ -> x.dismiss() }
                .create()
            dialog.show()
            fillColorList(ctx, dialog, node) { c.invalidate() }
        }
    }

    private fun keyModeSetup(groupOne: RadioGroup, groupTwo: RadioGroup) {
        val listener = RadioGroup.OnCheckedChangeListener { group, checkedId ->
            if (group.findViewById<RadioButton>(checkedId)?.isChecked == true) {
                if (group == groupOne) groupTwo.clearCheck() else groupOne.clearCheck()
            }
        }
        groupOne.setOnCheckedChangeListener(listener)
        groupTwo.setOnCheckedChangeListener(listener)
    }

    private fun visibilityCheckbox(d: AlertDialog, node: FlexNode) {
        val cb = d.findViewById<CheckBox>(R.id.checkbox_visible)
        cb.isChecked = node.bool(KEY_VISIBLE, true)
        cb.setOnClickListener { node[KEY_VISIBLE] = if (cb.isChecked) "1" else "0" }
    }

    private fun borderCheckbox(d: AlertDialog, id: Int, tag: String) {
        val cb = d.findViewById<CheckBox>(id)
        cb.isChecked = !key().str(KEY_HIDE_BORDERS).contains(tag)
        cb.setOnClickListener {
            val curr = key().str(KEY_HIDE_BORDERS)
            if (!cb.isChecked && !curr.contains(tag)) key()[KEY_HIDE_BORDERS] = curr + tag
            else if (cb.isChecked && curr.contains(tag)) key()[KEY_HIDE_BORDERS] = curr.replace(tag, "")
        }
    }

    private fun joyActionEdit(dialog: Dialog, btnId: Int, prop: String, title: Int) {
        val btn = dialog.findViewById<Button>(btnId)
        btn.text = key().str(prop)
        btn.setOnClickListener { actionEditor(prop, btn, ctx.getString(title), numbersAreKeys = true) }
    }

    /** Long press / heavy press / swipe values: text, key code, combination or macro, with live explanation. */
    private fun actionEditor(prop: String, btn: Button?, title: String, numbersAreKeys: Boolean = false) {
        ActionPrompt.show(ctx, title, key().params[prop] as? String ?: "", numbersAreKeys) { value ->
            setOrRemove(key(), prop, value)
            btn?.text = value
            c.invalidate()
        }
    }

    /** What a plain tap does: the default (label / code), a key combination or a macro. */
    private fun tapActionSetup(btn: Button?) {
        btn ?: return
        fun label() {
            val k = key()
            val macro = k.params[KEY_MACRO] as? String
            val combo = (k.params[KEY_COMBO] as? String)?.let { Combos.parse(it, requireModifier = false) }
            btn.text = when {
                (k.params[KEY_VOICE] as? String)?.trim() == "1" -> ctx.getString(R.string.ui_tap_voice)
                !macro.isNullOrBlank() -> ctx.getString(R.string.ui_tap_macro, macro)
                combo != null -> ctx.getString(R.string.ui_tap_combo, combo.describe())
                else -> ctx.getString(R.string.ui_tap_default)
            }
        }
        label()
        btn.setOnClickListener {
            val items = listOf(
                ctx.getString(R.string.ui_tap_opt_default),
                ctx.getString(R.string.ui_tap_opt_combo),
                ctx.getString(R.string.ui_tap_opt_macro),
                ctx.getString(R.string.ui_tap_opt_voice),
            )
            val current = when {
                key().params.containsKey(KEY_VOICE) -> 3
                key().params.containsKey(KEY_MACRO) -> 2
                key().params.containsKey(KEY_COMBO) -> 1
                else -> 0
            }
            Ask.choice(ctx, ctx.getString(R.string.ui_tap_action), items, current) { which ->
                if (which != 3) key().params.remove(KEY_VOICE)
                when (which) {
                    0 -> {
                        key().params.remove(KEY_COMBO)
                        key().params.remove(KEY_MACRO)
                        label()
                    }
                    3 -> {
                        key().params.remove(KEY_COMBO)
                        key().params.remove(KEY_MACRO)
                        key()[KEY_VOICE] = "1"
                        if (key().str(KEY_KEY).isEmpty()) key()[KEY_KEY] = "🎤"
                        label()
                        c.invalidate()
                    }
                    1 -> ComboBuilder.show(ctx, key().params[KEY_COMBO] as? String ?: "") { combo ->
                        key().params.remove(KEY_MACRO)
                        key()[KEY_COMBO] = combo.spec()
                        if (key().str(KEY_KEY).isEmpty()) key()[KEY_KEY] = combo.describe()
                        label()
                        c.invalidate()
                    }
                    2 -> ActionPrompt.pickMacro(ctx) { name ->
                        key().params.remove(KEY_COMBO)
                        key().params.remove(KEY_VOICE)
                        key()[KEY_MACRO] = name
                        if (key().str(KEY_KEY).isEmpty()) key()[KEY_KEY] = name
                        label()
                        c.invalidate()
                    }
                }
            }
        }
    }

    /** Which layout a tap switches to: special targets first, then the user's layouts. */
    private fun layoutPicker() {
        val special = listOf(
            LAYOUT_NEXT to R.string.ui_layout_next, LAYOUT_PREV to R.string.ui_layout_prev,
            LAYOUT_NUM to R.string.ui_layout_num, LAYOUT_TEXT to R.string.ui_layout_text, LAYOUT_EMOJI to R.string.ui_layout_emoji,
        )
        val files = PFile.list(ctx, LAYOUT_EXT).filter { it != SETTINGS_FILENAME }
        val values = listOf("") + special.map { it.first } + files
        val labels = listOf(ctx.getString(R.string.ui_layout_none)) + special.map { ctx.getString(it.second) } + files
        val current = values.indexOf(key().params[KEY_LAYOUT] as? String ?: "")
        Ask.choice(ctx, ctx.getString(R.string.enter_layout_name), labels, current) { i ->
            setOrRemove(key(), KEY_LAYOUT, values[i])
            c.invalidate()
        }
    }

    /** Modifier keys need a key code and a meta mask; let the user just pick "Ctrl", "Shift"... */
    private fun modifierPicker() {
        val mods = Combos.MODIFIERS + Combos.ALT_GR
        Ask.choice(ctx, ctx.getString(R.string.ui_modifier_pick), mods.map { it.label }) { i ->
            val m = mods[i]
            key()[KEY_CODE] = m.keyCode.toString()
            key()[KEY_MOD_META] = m.mask.toString()
            if (key().str(KEY_KEY).isEmpty()) key()[KEY_KEY] = m.label
            c.invalidate()
        }
    }

    private fun textEditor(prop: String, btn: Button?, title: String) {
        prompt(title, key().params[prop] as? String ?: "") { value ->
            setOrRemove(key(), prop, value)
            btn?.text = value
            c.invalidate()
        }
    }

    private fun prompt(title: String, value: String, onOk: (String) -> Unit) {
        val input = EditText(ctx).apply {
            text = editable(value)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            .show()
    }

    private fun midKeyEditing(btn: Button) {
        btn.text = key().str(KEY_KEY)
        btn.setOnClickListener {
            val v = View.inflate(ctx, R.layout.mid_key_edit, null)
            val label = v.findViewById<EditText>(R.id.mid_label).apply { text = editable(key().str(KEY_KEY)) }
            val code = v.findViewById<EditText>(R.id.mid_code).apply { text = editable(key().params[KEY_CODE] as? String ?: "") }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.title_mid_action)
                .setView(v)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    key()[KEY_KEY] = label.text.toString()
                    setOrRemove(key(), KEY_CODE, code.text.toString())
                    btn.text = label.text.toString()
                    c.invalidate()
                }
                .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
                .show()
        }
    }

    private fun showChecked(dialog: Dialog, rb: Int, show: Int, mode: String) {
        val r = dialog.findViewById<RadioButton>(rb)
        if (r.isChecked) dialog.findViewById<LinearLayout>(show).visibility = View.VISIBLE
        r.setOnClickListener {
            hideAll(dialog)
            if (r.isChecked) {
                key()[KEY_MODE] = mode
                dialog.findViewById<LinearLayout>(show).visibility = View.VISIBLE
            }
        }
    }

    private fun hideAll(dialog: Dialog) {
        for (id in intArrayOf(R.id.edit_joy, R.id.edit_meta, R.id.edit_popup, R.id.edit_random))
            dialog.findViewById<LinearLayout>(id).visibility = View.GONE
    }
}

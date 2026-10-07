package x.vladgba.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.*
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.keyboard.Combos

/**
 * "Physical buttons": change what volume keys, keyboard keys, remotes or gamepads do while typing.
 * Step 1: press the button. Step 2: choose what it should do. Below: the list of remaps and,
 * folded away, the technical details (codes, scan codes, modifiers, device, event log).
 *
 * Remaps are stored in settings as `"24": ("code": "61", "meta": "4096")` (same format layouts
 * use) and applied by the keyboard when remapping is on.
 */
class PhysicalKeysActivity : LocalizedActivity() {
    private lateinit var page: Page
    private lateinit var keyName: TextView
    private lateinit var keyStatus: TextView
    private lateinit var changeBtn: Button
    private lateinit var remapList: LinearLayout
    private lateinit var masterSwitch: Switch
    private lateinit var advanced: LinearLayout
    private lateinit var details: TextView
    private lateinit var heldView: TextView
    private lateinit var logView: TextView

    private val held = LinkedHashSet<Int>()
    private val log = ArrayDeque<String>()
    private var lastCode = 0
    private var captureBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.pk_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        Settings.loadVars(this)
        page = Page(this)
        build()
        setContentView(page.view)
        refreshRemaps()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun build() {
        page.note(getString(R.string.pk_intro))
        val master = page.switchRow(getString(R.string.pk_enabled), getString(R.string.pk_enabled_sub), Settings.bool(SETTING_REDEFINE_HW_ACTION)) {
            Settings[SETTING_REDEFINE_HW_ACTION] = if (it) "1" else "0"
            Settings.save(this)
        }
        masterSwitch = master.end.getChildAt(0) as Switch
        master.isFocusable = false

        page.header(getString(R.string.pk_step1))
        page.card {
            keyName = TextView(context).apply {
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                text = getString(R.string.pk_waiting)
            }
            keyStatus = TextView(context).apply {
                textSize = 14f
                setTextColor(COLOR_SECONDARY_TEXT)
                text = getString(R.string.pk_waiting_sub)
            }
            addView(keyName)
            addView(keyStatus)
        }

        page.header(getString(R.string.pk_step2))
        changeBtn = page.button(getString(R.string.pk_change_none)) { remapDialog(lastCode) }
        changeBtn.isEnabled = false
        changeBtn.isFocusable = false

        page.header(getString(R.string.pk_yours))
        remapList = page.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        page.note(getString(R.string.ui_tester_remap_note))

        // Technical details, folded by default.
        val toggle = page.row(getString(R.string.pk_advanced), getString(R.string.pk_advanced_sub)) { }
        val arrow = TextView(this).apply { text = "▾"; textSize = 20f; setTextColor(COLOR_SECONDARY_TEXT) }
        toggle.end.addView(arrow)
        toggle.isFocusable = false
        advanced = page.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE })
        toggle.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            arrow.text = if (show) "▴" else "▾"
        }
        toggle.selectableBackground()

        details = TextView(this).apply { textSize = 13f; setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        heldView = TextView(this).apply { textSize = 13f; setTextColor(COLOR_ACCENT) }
        advanced.addView(details)
        advanced.addView(heldView)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tools.addView(Button(this).apply {
            text = getString(R.string.ui_tester_copy)
            isFocusable = false
            setOnClickListener { if (lastCode != 0) copyToClipboard("-$lastCode", getString(R.string.ui_copied, "-$lastCode")) }
        })
        tools.addView(Button(this).apply {
            text = getString(R.string.ui_keycodes_title)
            isFocusable = false
            setOnClickListener { KeyCodePicker.show(this@PhysicalKeysActivity) }
        })
        advanced.addView(tools)
        val back = CheckBox(this).apply {
            text = getString(R.string.ui_tester_capture_back)
            isFocusable = false
            setOnCheckedChangeListener { _, c -> captureBack = c }
        }
        advanced.addView(back)
        advanced.addView(TextView(this).apply {
            text = getString(R.string.ui_tester_capture_back_sub)
            textSize = 12f
            setTextColor(COLOR_SECONDARY_TEXT)
        })
        advanced.addView(TextView(this).apply {
            text = getString(R.string.ui_tester_log)
            setTextColor(COLOR_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        })
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; setTextIsSelectable(true) }
        advanced.addView(logView)
        page.root.isFocusable = false
    }

    // ======================= capturing =======================

    @Suppress("DEPRECATION") // ACTION_MULTIPLE: ignored, it has no physical press/release
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK && !captureBack) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_MULTIPLE) return true
        show(event)
        return true // keep volume, arrows etc. from doing their usual thing here
    }

    private fun show(e: KeyEvent) {
        val code = e.keyCode
        val down = e.action == KeyEvent.ACTION_DOWN
        if (down) held.add(code) else held.remove(code)
        details.text = describe(e)
        heldView.text = if (held.isEmpty()) "" else getString(R.string.ui_tester_held, held.joinToString(", ") { Combos.keyLabel(it) })
        if (!(down && e.repeatCount > 0)) {
            val mods = Combos.metaNames(e.metaState).joinToString("+").ifEmpty { "-" }
            log.addFirst(String.format("%-4s %-16s %4d scan %-4d %s", if (down) "DOWN" else "UP", Combos.keyName(code).take(16), code, e.scanCode, mods))
            while (log.size > 40) log.removeLast()
            logView.text = log.joinToString("\n")
        }
        if (!down || KeyEvent.isModifierKey(code) && held.size > 1) return
        lastCode = code
        updateCurrent()
    }

    private fun updateCurrent() {
        if (lastCode == 0) return
        val label = Combos.keyLabel(lastCode)
        keyName.text = label
        val remap = currentRemap(lastCode)
        keyStatus.text = getString(R.string.pk_code_line, lastCode) + "\n" +
                (remap?.let { getString(R.string.pk_now_does, describeRemap(it)) } ?: getString(R.string.pk_now_normal))
        changeBtn.text = getString(R.string.pk_change, label)
        changeBtn.isEnabled = true
    }

    private fun describe(e: KeyEvent): String {
        val dev = e.device
        val mods = Combos.metaNames(e.metaState).joinToString(", ").ifEmpty { getString(R.string.ui_none) }
        return getString(
            R.string.ui_tester_details,
            e.keyCode, Combos.keyName(e.keyCode), "-${e.keyCode}", e.scanCode,
            if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP", e.repeatCount,
            mods, "0x" + Integer.toHexString(e.metaState),
            dev?.name ?: "?", e.deviceId, sourceName(e.source),
        )
    }

    private fun sourceName(src: Int): String {
        val names = ArrayList<String>()
        fun has(s: Int) = src and s == s
        if (has(InputDevice.SOURCE_GAMEPAD)) names += "gamepad"
        if (has(InputDevice.SOURCE_JOYSTICK)) names += "joystick"
        if (has(InputDevice.SOURCE_DPAD)) names += "dpad"
        if (has(InputDevice.SOURCE_KEYBOARD)) names += "keyboard"
        if (has(InputDevice.SOURCE_MOUSE)) names += "mouse"
        if (has(InputDevice.SOURCE_TOUCHSCREEN)) names += "touchscreen"
        return names.joinToString(", ").ifEmpty { "0x" + Integer.toHexString(src) }
    }

    // ======================= remaps =======================

    private fun currentRemap(code: Int): FlexNode? = Settings.params[code.toString()] as? FlexNode

    private fun describeRemap(n: FlexNode): String {
        val text = n.params[KEY_TEXT] as? String
        val code = (n.params[KEY_CODE] as? String)?.let { FlexNode.parseInt(it.trim()) }
        val layout = n.params[KEY_SWITCH_LAYOUT] as? String
        val parts = ArrayList<String>()
        when {
            n.params.containsKey("inject") -> parts += getString(R.string.ui_remap_inject)
            !text.isNullOrEmpty() -> parts += getString(R.string.ui_action_text, text)
            code != null -> {
                val mask = (n.params[KEY_MOD_META] as? String)?.let { FlexNode.parseInt(it.trim()) } ?: 0
                val mods = Combos.MODIFIERS.filter { mask and it.mask == it.mask && it.mask != 0 }
                parts += getString(R.string.ui_action_key, Combos.Combo(mods, code).describe())
            }
        }
        if (!layout.isNullOrEmpty()) parts += getString(R.string.pk_switches_to, BuiltIns.layoutTitle(this, layout))
        if (parts.isEmpty()) parts += getString(R.string.pk_does_nothing)
        if (n.bool(KEY_DO_IF_HIDDEN)) parts += getString(R.string.ui_remap_hidden_too)
        return parts.joinToString(", ")
    }

    private fun refreshRemaps() {
        masterSwitch.isChecked = Settings.bool(SETTING_REDEFINE_HW_ACTION)
        remapList.removeAllViews()
        val codes = Settings.params.filter { it.value is FlexNode && it.key.toIntOrNull() != null }.keys.map { it.toInt() }.sorted()
        if (codes.isEmpty()) remapList.addView(TextView(this).apply {
            text = getString(R.string.pk_none_yet)
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        for (code in codes) {
            val n = currentRemap(code) ?: continue
            val r = Row(this)
            r.title.text = getString(R.string.pk_row, Combos.keyLabel(code), describeRemap(n))
            r.setSubtitle(getString(R.string.pk_row_sub, code))
            r.selectableBackground()
            r.isFocusable = false
            r.setOnClickListener { remapDialog(code) }
            remapList.addView(r)
        }
        updateCurrent()
    }

    private fun saveRemap(code: Int, node: FlexNode?) {
        if (node == null) Settings.params.remove(code.toString()) else Settings[code.toString()] = node
        if (node != null) Settings[SETTING_REDEFINE_HW_ACTION] = "1"
        Settings.save(this)
        refreshRemaps()
        Toast.makeText(this, R.string.file_saved, Toast.LENGTH_SHORT).show()
    }

    private fun remapDialog(code: Int) {
        if (code == 0) return
        val label = Combos.keyLabel(code)
        val existing = currentRemap(code)
        val hidden = CheckBox(this).apply {
            text = getString(R.string.ui_remap_hidden)
            isChecked = existing?.bool(KEY_DO_IF_HIDDEN) == true
        }
        lateinit var dialog: AlertDialog
        fun node(build: FlexNode.() -> Unit) = FlexNode().apply {
            build()
            if (hidden.isChecked) this[KEY_DO_IF_HIDDEN] = "1"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.pk_dialog_now, existing?.let { describeRemap(it) } ?: getString(R.string.pk_normal_short))
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, 0, 0, dp(8))
        })
        fun option(title: Int, sub: Int, onClick: () -> Unit) {
            val r = Row(this)
            r.title.text = getString(title)
            r.setSubtitle(getString(sub))
            r.selectableBackground()
            r.setOnClickListener { dialog.dismiss(); onClick() }
            r.end.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(COLOR_SECONDARY_TEXT) })
            box.addView(r)
        }
        option(R.string.pk_opt_key, R.string.pk_opt_key_sub) {
            ComboBuilder.show(this, "") { c ->
                saveRemap(code, node { this[KEY_CODE] = c.keyCode.toString(); if (c.mask != 0) this[KEY_MOD_META] = c.mask.toString() })
            }
        }
        option(R.string.pk_opt_text, R.string.pk_opt_text_sub) {
            Ask.text(this, getString(R.string.pk_opt_text), (existing?.params?.get(KEY_TEXT) as? String) ?: "") { t ->
                if (t.isNotEmpty()) saveRemap(code, node { this[KEY_TEXT] = t })
            }
        }
        option(R.string.pk_opt_layout, R.string.pk_opt_layout_sub) {
            val layouts = PFile.list(this, LAYOUT_EXT).filter { it != SETTINGS_FILENAME }
            Ask.choice(this, getString(R.string.pk_opt_layout), layouts.map { BuiltIns.layoutTitle(this, it) }) { i ->
                saveRemap(code, node { this[KEY_SWITCH_LAYOUT] = layouts[i] })
            }
        }
        option(R.string.pk_opt_block, R.string.pk_opt_block_sub) { saveRemap(code, node { }) }
        if (existing != null) option(R.string.pk_opt_restore, R.string.pk_opt_restore_sub) { saveRemap(code, null) }
        box.addView(hidden)
        box.addView(TextView(this).apply {
            text = getString(R.string.pk_hidden_note)
            textSize = 12f
            setTextColor(COLOR_SECONDARY_TEXT)
        })
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pk_dialog_title, label))
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }
}

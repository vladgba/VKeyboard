package x.vladgba.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/**
 * Visual theme editor: a live sample keyboard on top (tap any part of it to recolor it), the
 * colors grouped below with plain names. The picker opens at the bottom and updates the preview
 * while dragging. Undo, reset to default, generate from one color, use as day/night theme.
 *
 * Extras: `name` (theme file without extension), optional `generate` = true to start with the
 * palette generator.
 */
class ThemeEditorActivity : LocalizedActivity() {
    private lateinit var name: String
    private lateinit var node: FlexNode
    private lateinit var preview: ThemePreview
    private lateinit var warning: TextView
    private lateinit var page: Page
    private val undo = ArrayDeque<Map<String, Any>>()
    private var dirty = false
    private var savedSnapshot: Map<String, Any> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name") ?: return finish()
        title = getString(R.string.theme_editing_title, name)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        Settings.loadVars(this)
        node = Theme.upgrade(FlexParser.parse(PFile(this, name, THEME_EXT).read()))
        savedSnapshot = HashMap(node.params)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
            setBackgroundColor(0xffffffff.toInt())
            elevation = dp(4).toFloat()
        }
        preview = ThemePreview(this).apply {
            colors = { ThemeColors.color(node, it) }
            onPick = { key -> pickColor(key) }
        }
        top.addView(preview)
        top.addView(TextView(this).apply {
            text = getString(R.string.te_tap_hint)
            textSize = 12f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, dp(4), 0, 0)
        })
        warning = TextView(this).apply { textSize = 12f; setTextColor(COLOR_WARN); visibility = View.GONE }
        top.addView(warning)
        root.addView(top)

        page = Page(this)
        root.addView(page.view, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
        if (intent.getBooleanExtra("generate", false)) generateDialog()
    }

    // ======================= list =======================

    private fun swatch(color: Int) = View(this).apply {
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), 0x33000000)
        }
    }

    private fun render() {
        page.clear()
        for (group in ThemeColors.GROUPS) {
            page.header(getString(group.title))
            for (info in group.items) {
                val color = ThemeColors.color(node, info.key)
                val own = node.params.containsKey(info.key)
                val row = page.row(getString(info.title), getString(info.desc)) {
                    flash(info.key)
                    pickColor(info.key)
                }
                val end = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        text = if (own) ThemeColors.hex(color) else getString(R.string.te_default)
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setTextColor(COLOR_SECONDARY_TEXT)
                        setPadding(0, 0, dp(8), 0)
                    })
                    addView(swatch(color), LinearLayout.LayoutParams(dp(36), dp(36)))
                }
                row.end.addView(end)
                row.setOnLongClickListener { colorMenu(info.key); true }
            }
        }
        page.header(getString(R.string.te_quick))
        page.row(getString(R.string.te_generate), getString(R.string.te_generate_sub)) { generateDialog() }
        page.row(getString(R.string.te_load_from), getString(R.string.te_load_from_sub)) { loadFromDialog() }
        page.row(getString(R.string.te_use_as), getString(R.string.te_use_as_sub, Settings.str(THEME_DAY), Settings.str(THEME_NIGHT))) { useAsDialog() }
        page.row(getString(R.string.edit_raw), getString(R.string.te_raw_sub)) {
            if (dirty) save()
            startActivity(Intent(this, RawEditor::class.java).putExtra("name", name).putExtra("ext", THEME_EXT))
            finish()
        }
        refreshPreview()
    }

    private fun refreshPreview() {
        preview.invalidate()
        // Warn about labels that are hard to read.
        val problems = ArrayList<String>()
        fun check(fg: String, bg: String, what: Int) {
            val b = ThemeColors.over(ThemeColors.color(node, bg), ThemeColors.color(node, COLOR_KEYBOARD_BACKGROUND))
            val f = ThemeColors.over(ThemeColors.color(node, fg), b)
            if (ThemeColors.contrast(f, b) < 3.0) problems += getString(what)
        }
        check(COLOR_TEXT_PRIMARY, COLOR_KEY_BACKGROUND, R.string.te_warn_labels)
        check(COLOR_TEXT_PREVIEW, COLOR_KEY_POPUP_BACKGROUND, R.string.te_warn_popup)
        warning.text = problems.joinToString("\n")
        warning.visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        invalidateOptionsMenu()
    }

    private fun flash(key: String) {
        preview.highlight = key
        preview.removeCallbacks(clearHighlight)
        preview.postDelayed(clearHighlight, 1600)
    }

    private val clearHighlight = Runnable { preview.highlight = null }

    // ======================= changes =======================

    private fun snapshot() {
        undo.addLast(HashMap(node.params))
        while (undo.size > 50) undo.removeFirst()
    }

    private fun changed() {
        dirty = node.params != savedSnapshot
        render()
    }

    private fun setColor(key: String, color: Int?) {
        if (color == null) node.params.remove(key) else node[key] = Theme.toHex(color)
    }

    private fun pickColor(key: String) {
        val info = ThemeColors.info(key) ?: return
        flash(key)
        val had = node.params[key]
        val start = ThemeColors.color(node, key)
        ColorPicker(this, start, true, object : ColorPicker.ColorPickerListener {
            override fun onOk(dialog: ColorPicker, color: Int) {
                // Restore first so the undo snapshot holds the old value.
                if (had == null) node.params.remove(key) else node[key] = had
                snapshot()
                setColor(key, color)
                changed()
            }

            override fun onCancel(dialog: ColorPicker) {
                if (had == null) node.params.remove(key) else node[key] = had
                refreshPreview()
            }
        }, onChange = { c ->
            setColor(key, c)
            preview.invalidate()
        }, title = getString(info.title), atBottom = true).show()
    }

    private fun colorMenu(key: String) {
        val info = ThemeColors.info(key) ?: return
        val items = listOf(getString(R.string.te_enter_hex), getString(R.string.te_copy_from), getString(R.string.te_reset_one))
        Ask.choice(this, getString(info.title), items) { which ->
            when (which) {
                0 -> Ask.text(this, getString(R.string.te_enter_hex), ThemeColors.hex(ThemeColors.color(node, key)),
                    getString(R.string.te_hex_help), InputType.TYPE_CLASS_TEXT) { raw ->
                    val t = raw.trim().removePrefix("#")
                    val v = t.toLongOrNull(16)
                    if (v == null || (t.length != 6 && t.length != 8)) {
                        Toast.makeText(this, R.string.te_bad_hex, Toast.LENGTH_SHORT).show()
                    } else {
                        snapshot()
                        setColor(key, if (t.length == 6) (v or 0xff000000).toInt() else v.toInt())
                        changed()
                    }
                }
                1 -> {
                    val others = ThemeColors.ALL.filter { it.key != key }
                    Ask.choice(this, getString(R.string.te_copy_from), others.map { getString(it.title) }) { i ->
                        snapshot()
                        setColor(key, ThemeColors.color(node, others[i].key))
                        changed()
                    }
                }
                2 -> {
                    snapshot()
                    setColor(key, null)
                    changed()
                }
            }
        }
    }

    private fun generateDialog() {
        val accent = ThemeColors.color(node, COLOR_KEY_PRESSED_MOD_BACKGROUND)
        ColorPicker(this, accent, false, object : ColorPicker.ColorPickerListener {
            override fun onOk(dialog: ColorPicker, color: Int) {
                Ask.choice(this@ThemeEditorActivity, getString(R.string.te_light_or_dark),
                    listOf(getString(R.string.te_light), getString(R.string.te_dark))) { which ->
                    snapshot()
                    for ((k, v) in ThemeColors.generate(color, which == 1)) setColor(k, v)
                    changed()
                }
            }
            override fun onCancel(dialog: ColorPicker) {}
        }, title = getString(R.string.te_pick_accent)).show()
    }

    private fun loadFromDialog() {
        val sources = listOf(
            getString(R.string.te_builtin_light) to (PFile.asset(this, "baseLight", THEME_EXT) ?: ""),
            getString(R.string.te_builtin_dark) to (PFile.asset(this, "baseDark", THEME_EXT) ?: ""),
        ) + PFile.list(this, THEME_EXT).filter { it != name }.map { it to PFile(this, it, THEME_EXT).read() }
        Ask.choice(this, getString(R.string.te_load_from), sources.map { it.first }) { i ->
            val other = Theme.upgrade(FlexParser.parse(sources[i].second))
            snapshot()
            for (info in ThemeColors.ALL) {
                val v = other.params[info.key]
                if (v == null) node.params.remove(info.key) else node[info.key] = v
            }
            changed()
        }
    }

    private fun useAsDialog() {
        val items = listOf(getString(R.string.theme_day), getString(R.string.theme_night), getString(R.string.te_both))
        Ask.choice(this, getString(R.string.te_use_as), items) { which ->
            save()
            if (which == 0 || which == 2) Settings[THEME_DAY] = name
            if (which == 1 || which == 2) Settings[THEME_NIGHT] = name
            Settings.save(this)
            Theme.invalidate()
            Toast.makeText(this, R.string.te_applied, Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun save() {
        if (!PFile(this, name, THEME_EXT).write(node.toString())) {
            Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
            return
        }
        Theme.invalidate()
        savedSnapshot = HashMap(node.params)
        dirty = false
        invalidateOptionsMenu()
        Toast.makeText(this, R.string.file_saved, Toast.LENGTH_SHORT).show()
    }

    // ======================= menu / leaving =======================

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_UNDO, 0, R.string.te_undo).setIcon(android.R.drawable.ic_menu_revert)
            .setEnabled(undo.isNotEmpty()).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_SAVE, 1, R.string.save).setIcon(android.R.drawable.ic_menu_save)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_UNDO -> undo.removeLastOrNull()?.let {
                node.params = HashMap(it).toMutableMap()
                changed()
            }
            MENU_SAVE -> save()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onNavigateUp(): Boolean {
        leave()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = leave()

    private fun leave() {
        if (!dirty) return finish()
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_title)
            .setMessage(R.string.te_unsaved)
            .setPositiveButton(R.string.save) { _, _ -> save(); finish() }
            .setNegativeButton(R.string.te_discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val MENU_UNDO = 1
        private const val MENU_SAVE = 2
    }
}

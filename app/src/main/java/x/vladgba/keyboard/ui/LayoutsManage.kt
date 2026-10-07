package x.vladgba.keyboard.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexParser

/**
 * The user's layouts as cards with a drawn preview. Typing layouts (cycled with the globe key)
 * and number/symbol layouts are listed separately; the default one is marked.
 */
class LayoutsManage : LocalizedActivity() {
    private lateinit var page: Page

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ui_home_layouts)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        page = Page(this)
        setContentView(page.view)
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        page.clear()
        Settings.loadVars(this)
        page.note(getString(R.string.lm_intro))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = getString(R.string.lm_new)
            setOnClickListener { newLayoutDialog() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = getString(R.string.lm_builtin)
            setOnClickListener { startActivity(Intent(this@LayoutsManage, SetupLayouts::class.java)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.add(buttons)

        val all = PFile.list(this, LAYOUT_EXT).filter { it != SETTINGS_FILENAME && !isEmojiPage(it) }
        val typing = all.filter { Bootstrap.isTextLayout(it) }
        val other = all.filter { !Bootstrap.isTextLayout(it) }
        val def = Settings.str(SETTING_DEF_LAYOUT)

        page.header(getString(R.string.lm_typing, typing.size))
        page.note(getString(R.string.lm_typing_sub))
        // Default first, then alphabetical.
        for (name in typing.sortedBy { if (it == def) "" else it.lowercase() }) card(name, isDefault = name == def, canBeDefault = true)

        if (other.isNotEmpty()) {
            page.header(getString(R.string.lm_other))
            page.note(getString(R.string.lm_other_sub))
            for (name in other) card(name, isDefault = false, canBeDefault = false)
        }
    }

    private fun chip(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xffffffff.toInt())
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
    }

    private fun card(name: String, isDefault: Boolean, canBeDefault: Boolean) {
        val node = FlexParser.parse(PFile(this, name).read())
        val title = BuiltIns.layoutTitle(this, name)
        page.card {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(TextView(context).apply {
                text = title
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            if (title != name) titles.addView(TextView(context).apply {
                text = getString(R.string.lm_file, name)
                textSize = 12f
                setTextColor(COLOR_SECONDARY_TEXT)
            })
            header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            if (isDefault) header.addView(chip(getString(R.string.lm_default), COLOR_ACCENT))
            addView(header)

            addView(LayoutPreview(context).apply {
                layout = node
                setOnClickListener { edit(name) }
                contentDescription = getString(R.string.lm_edit_visual)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(2) })

            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (canBeDefault && !isDefault) actions.addView(Button(context).apply {
                text = getString(R.string.lm_make_default)
                textSize = 12f
                setOnClickListener {
                    Settings[SETTING_DEF_LAYOUT] = name
                    Settings.save(this@LayoutsManage)
                    Toast.makeText(this@LayoutsManage, getString(R.string.default_layout_set, title), Toast.LENGTH_SHORT).show()
                    render()
                }
            })
            actions.addView(Button(context).apply {
                text = getString(R.string.edit)
                textSize = 12f
                setOnClickListener { edit(name) }
            })
            actions.addView(android.widget.Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            fun icon(res: Int, desc: Int, onClick: () -> Unit) = actions.addView(ImageButton(context).apply {
                setImageResource(res)
                contentDescription = getString(desc)
                background = null
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onClick() }
            })
            icon(android.R.drawable.ic_menu_sort_by_size, R.string.edit_raw) {
                startActivity(Intent(this@LayoutsManage, RawEditor::class.java).putExtra("name", name))
            }
            icon(android.R.drawable.ic_menu_share, R.string.share) {
                startActivity(Intent(this@LayoutsManage, FileExport::class.java).putExtra("name", name))
            }
            icon(android.R.drawable.ic_menu_delete, R.string.delete) {
                if (isDefault) {
                    Toast.makeText(this@LayoutsManage, R.string.lm_cant_delete_default, Toast.LENGTH_LONG).show()
                    return@icon
                }
                confirm(this@LayoutsManage, R.string.delete_file_title, R.string.delete_file_confirmation, R.string.delete) {
                    PFile(this@LayoutsManage, name).delete()
                    render()
                }
            }
            addView(actions)
        }
    }

    private fun edit(name: String) = startActivity(Intent(this, LayoutEditor::class.java).putExtra("name", name))

    /** Name → starting point → visual editor. */
    private fun newLayoutDialog() {
        Ask.text(this, getString(R.string.lm_new), "", getString(R.string.lm_name_hint)) { raw ->
            val name = raw.trim()
            when {
                name.isEmpty() || name.contains('/') || name == SETTINGS_FILENAME ->
                    Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
                PFile(this, name).exists() -> Toast.makeText(this, getString(R.string.tm_exists, name), Toast.LENGTH_SHORT).show()
                else -> startFrom(name)
            }
        }
    }

    private fun startFrom(name: String) {
        val builtIn = BuiltIns.assetNames(this, LAYOUT_EXT, BLANK_LAYOUT)
        val mine = PFile.list(this, LAYOUT_EXT).filter { it != SETTINGS_FILENAME }
        val labels = listOf(getString(R.string.lm_start_empty)) +
                builtIn.map { getString(R.string.lm_start_builtin, BuiltIns.layoutTitle(this, it)) } +
                mine.map { getString(R.string.tm_copy_of, it) }
        Ask.choice(this, getString(R.string.tm_start_from), labels) { i ->
            val text = when {
                i == 0 -> PFile.asset(this, BLANK_LAYOUT) ?: "(())"
                i <= builtIn.size -> PFile.asset(this, builtIn[i - 1]) ?: ""
                else -> PFile(this, mine[i - 1 - builtIn.size]).read()
            }
            if (!PFile(this, name).write(text)) {
                Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
                return@choice
            }
            edit(name)
        }
    }
}

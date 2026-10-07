package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexParser

/** Themes with a small live preview each; tap a preview to edit, "+ New theme" starts a wizard. */
class ThemeManage : LocalizedActivity() {
    private lateinit var page: Page

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.themes)
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
        themesList()
    }

    private fun themesList() {
        page.clear()
        Settings.loadVars(this)
        page.note(getString(R.string.tm_intro))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(android.widget.Button(this).apply {
            text = getString(R.string.tm_new)
            setOnClickListener { newThemeDialog() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(android.widget.Button(this).apply {
            text = getString(R.string.tm_builtin)
            setOnClickListener { startActivity(Intent(this@ThemeManage, SetupLayouts::class.java).putExtra("layouts", false)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.add(buttons)

        for (name in PFile.list(this, THEME_EXT)) {
            val node = Theme.upgrade(FlexParser.parse(PFile(this, name, THEME_EXT).read()))
            val inUse = listOfNotNull(
                getString(R.string.theme_day).takeIf { Settings.str(THEME_DAY) == name },
                getString(R.string.theme_night).takeIf { Settings.str(THEME_NIGHT) == name }
            )
            page.card {
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                header.addView(TextView(context).apply {
                    text = if (inUse.isEmpty()) name else "$name  ·  ${inUse.joinToString(" + ")}"
                    textSize = 16f
                    if (inUse.isNotEmpty()) setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                fun icon(res: Int, desc: Int, onClick: () -> Unit) = header.addView(ImageButton(context).apply {
                    setImageResource(res)
                    contentDescription = getString(desc)
                    background = null
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { onClick() }
                })
                icon(android.R.drawable.ic_menu_edit, R.string.edit) { edit(name) }
                icon(android.R.drawable.ic_menu_day, R.string.te_use_as) { useAs(name) }
                icon(android.R.drawable.ic_menu_share, R.string.share) {
                    startActivity(Intent(this@ThemeManage, FileExport::class.java).putExtra("name", name).putExtra("ext", THEME_EXT))
                }
                icon(android.R.drawable.ic_menu_delete, R.string.delete) {
                    confirm(this@ThemeManage, R.string.delete_file_title, R.string.delete_file_confirmation, R.string.delete) {
                        PFile(this@ThemeManage, name, THEME_EXT).delete()
                        Theme.invalidate()
                        themesList()
                    }
                }
                addView(header)
                addView(ThemePreview(context, compact = true).apply {
                    colors = { ThemeColors.color(node, it) }
                    onPick = { edit(name) }
                })
            }
        }
    }

    private fun edit(name: String) = startActivity(Intent(this, ThemeEditorActivity::class.java).putExtra("name", name))

    private fun useAs(name: String) {
        val items = listOf(getString(R.string.theme_day), getString(R.string.theme_night), getString(R.string.te_both))
        Ask.choice(this, getString(R.string.te_use_as), items) { which ->
            if (which == 0 || which == 2) Settings[THEME_DAY] = name
            if (which == 1 || which == 2) Settings[THEME_NIGHT] = name
            Settings.save(this)
            Theme.invalidate()
            themesList()
        }
    }

    /** Name → starting point → editor. */
    private fun newThemeDialog() {
        Ask.text(this, getString(R.string.tm_new), "", getString(R.string.tm_name_hint)) { raw ->
            val name = raw.trim()
            when {
                name.isEmpty() || name.contains('/') -> Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
                PFile(this, name, THEME_EXT).exists() -> Toast.makeText(this, getString(R.string.tm_exists, name), Toast.LENGTH_SHORT).show()
                else -> startingPoint(name)
            }
        }
    }

    private fun startingPoint(name: String) {
        val existing = PFile.list(this, THEME_EXT)
        val options = listOf(getString(R.string.te_generate), getString(R.string.te_builtin_light), getString(R.string.te_builtin_dark)) +
                existing.map { getString(R.string.tm_copy_of, it) }
        Ask.choice(this, getString(R.string.tm_start_from), options) { i ->
            val text = when (i) {
                0 -> PFile.asset(this, "baseLight", THEME_EXT) ?: ""
                1 -> PFile.asset(this, "baseLight", THEME_EXT) ?: ""
                2 -> PFile.asset(this, "baseDark", THEME_EXT) ?: ""
                else -> PFile(this, existing[i - 3], THEME_EXT).read()
            }
            if (!PFile(this, name, THEME_EXT).write(text)) {
                Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
                return@choice
            }
            startActivity(Intent(this, ThemeEditorActivity::class.java).putExtra("name", name).putExtra("generate", i == 0))
        }
    }
}

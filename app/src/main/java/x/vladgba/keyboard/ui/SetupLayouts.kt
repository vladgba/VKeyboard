package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexParser

/**
 * Built-in layouts (`*.txt`) or themes (`*.ini`) shipped with the app, each with a preview and a
 * short description. Added ones are marked and can be restored to the original.
 * Extras: `layouts` (default true), `firstRun` shows the welcome text and a Done button.
 */
class SetupLayouts : LocalizedActivity() {
    private lateinit var page: Page
    private var isLayouts = true
    private var firstRun = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLayouts = intent.getBooleanExtra("layouts", true)
        firstRun = intent.getBooleanExtra("firstRun", false)
        title = getString(if (isLayouts) R.string.prebuilt_layouts else R.string.prebuilt_themes)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        Bootstrap.ensureInstalled(this)
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
        if (firstRun) page.add(TextView(this).apply {
            text = getString(R.string.bi_welcome_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(12), dp(4), dp(4))
        })
        page.note(getString(if (isLayouts) R.string.bi_layouts_intro else R.string.bi_themes_intro))

        val ext = if (isLayouts) LAYOUT_EXT else THEME_EXT
        val blank = if (isLayouts) BLANK_LAYOUT else BLANK_THEME
        for (name in BuiltIns.assetNames(this, ext, blank)) item(name, ext)

        page.button(getString(if (firstRun) R.string.bi_done else R.string.bi_more_themes_or_layouts)) {
            if (firstRun) finish()
            else startActivity(Intent(this, if (isLayouts) LayoutsManage::class.java else ThemeManage::class.java))
        }
    }

    private fun item(name: String, ext: String) {
        val info = if (isLayouts) BuiltIns.layout(name) else BuiltIns.theme(name)
        val text = PFile.asset(this, name, ext) ?: return
        val added = PFile(this, name, ext).exists()
        page.card {
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            titles.addView(TextView(context).apply {
                this.text = info?.let { getString(it.title) } ?: name
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            info?.let {
                titles.addView(TextView(context).apply {
                    this.text = getString(it.desc)
                    textSize = 13f
                    setTextColor(COLOR_SECONDARY_TEXT)
                })
            }
            header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            header.addView(Button(context).apply {
                this.text = getString(if (added) R.string.bi_added else R.string.bi_add)
                isEnabled = !added
                setOnClickListener { install(name, ext, overwrite = false) }
            })
            addView(header)

            if (isLayouts) addView(LayoutPreview(context, rowHeightDp = 22).apply { layout = FlexParser.parse(text) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            else {
                val node = Theme.upgrade(FlexParser.parse(text))
                addView(ThemePreview(context, compact = true).apply { colors = { ThemeColors.color(node, it) } },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }

            if (added) addView(TextView(context).apply {
                this.text = getString(R.string.bi_restore)
                textSize = 13f
                setTextColor(COLOR_ACCENT)
                setPadding(dp(4), dp(8), dp(4), dp(4))
                setOnClickListener {
                    confirm(this@SetupLayouts, R.string.bi_restore, R.string.bi_restore_confirm, R.string.yes) {
                        install(name, ext, overwrite = true)
                    }
                }
            })
        }
    }

    private fun install(name: String, ext: String, overwrite: Boolean) {
        if (!PFile.install(this, name, ext, overwrite)) {
            Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
            return
        }
        if (isLayouts && name == EMOJI_FILENAME) for (page in EMOJI_PAGES) PFile.install(this, page, ext, overwrite)
        if (ext == THEME_EXT) Theme.invalidate()
        if (isLayouts && Settings.str(SETTING_DEF_LAYOUT).isBlank() && Bootstrap.isTextLayout(name)) {
            Settings[SETTING_DEF_LAYOUT] = name
            Settings.save(this)
        }
        Toast.makeText(this, getString(if (overwrite) R.string.bi_restored else R.string.bi_added_toast,
            (if (isLayouts) BuiltIns.layout(name) else BuiltIns.theme(name))?.let { getString(it.title) } ?: name), Toast.LENGTH_SHORT).show()
        render()
    }
}

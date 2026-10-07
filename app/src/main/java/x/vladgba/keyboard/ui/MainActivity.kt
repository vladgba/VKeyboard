package x.vladgba.keyboard.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.Bootstrap
import x.vladgba.keyboard.core.DICT_EXT
import x.vladgba.keyboard.core.DICT_FILENAME

/** Home screen: setup steps with live status, then everything that can be customized. */
class MainActivity : LocalizedActivity() {
    private lateinit var page: Page
    private lateinit var stepEnable: Row
    private lateinit var stepSelect: Row
    private lateinit var stepEnableMark: TextView
    private lateinit var stepSelectMark: TextView

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val firstRun = Bootstrap.isFirstRun(this)
        Bootstrap.ensureInstalled(this)
        if (firstRun) startActivity(Intent(this, SetupLayouts::class.java).putExtra("firstRun", true))
        page = Page(this)
        build()
        setContentView(page.view)
    }

    private fun build() {
        page.add(TextView(this).apply {
            text = getString(R.string.welcome_to_keyb)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(16), dp(4), dp(4))
        })
        page.note(getString(R.string.ui_home_tagline))

        page.header(getString(R.string.ui_home_setup))
        page.card {
            stepEnable = setupRow(R.string.ui_step1, R.string.ui_step1_sub) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            stepEnableMark = stepEnable.end.getChildAt(0) as TextView
            stepSelect = setupRow(R.string.ui_step2, R.string.ui_step2_sub) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            stepSelectMark = stepSelect.end.getChildAt(0) as TextView
            addView(TextView(context).apply {
                text = getString(R.string.ui_step3)
                textSize = 16f
                setPadding(dp(4), dp(8), dp(4), 0)
            })
            addView(EditText(context).apply {
                hint = getString(R.string.you_can_test)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
            }, LinearLayout.LayoutParams(-1, -2))
        }

        page.header(getString(R.string.ui_home_customize))
        page.row(getString(R.string.ui_language), getString(R.string.ui_language_sub)) { LanguagePicker.show(this) }
            .also { it.end.addView(TextView(this).apply { text = LanguagePicker.currentName(this@MainActivity); textSize = 15f; setTextColor(COLOR_ACCENT) }) }
        nav(R.string.settings, R.string.ui_home_settings_sub) { open(SettingsActivity::class.java) }
        nav(R.string.ui_home_layouts, R.string.ui_home_layouts_sub) { open(LayoutsManage::class.java) }
        nav(R.string.themes, R.string.ui_home_themes_sub) { open(ThemeManage::class.java) }
        nav(R.string.ui_macros, R.string.ui_home_macros_sub) { open(MacrosActivity::class.java) }
        nav(R.string.dictionary, R.string.ui_set_dict_sub) {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }

        page.header(getString(R.string.ui_home_tools))
        nav(R.string.ui_key_tester, R.string.ui_key_tester_sub) { open(PhysicalKeysActivity::class.java) }
        nav(R.string.ts_title, R.string.ts_home_sub) { open(TouchStatsActivity::class.java) }
        nav(R.string.ui_keycodes_title, R.string.ui_home_keycodes_sub) { KeyCodePicker.show(this) }
        nav(R.string.ui_guide, R.string.ui_guide_sub) { Ask.info(this, getString(R.string.ui_guide), getString(R.string.ui_guide_text)) }
    }

    private fun LinearLayout.setupRow(title: Int, sub: Int, onClick: () -> Unit): Row {
        val r = Row(context)
        r.title.text = getString(title)
        r.setSubtitle(getString(sub))
        r.selectableBackground()
        r.setOnClickListener { onClick() }
        r.end.addView(TextView(context).apply { textSize = 22f })
        addView(r)
        return r
    }

    private fun nav(title: Int, sub: Int, onClick: () -> Unit) = page.row(getString(title), getString(sub), onClick)
        .also { it.end.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(COLOR_SECONDARY_TEXT) }) }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    private fun mark(view: TextView, done: Boolean) {
        view.text = if (done) "✓" else "!"
        view.setTextColor(if (done) COLOR_OK else COLOR_WARN)
    }

    private fun refreshSetup() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        mark(stepEnableMark, enabled)
        mark(stepSelectMark, selected)
        stepSelect.isEnabled = enabled
        stepSelect.alpha = if (enabled) 1f else 0.5f
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input method picker is a system dialog: re-check when it closes.
        if (hasFocus) refreshSetup()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.welcome_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_about -> {
            Dialog(this).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setContentView(R.layout.about_app)
                show()
            }
            true
        }
        R.id.action_prebuilt_add_theme -> {
            startActivity(Intent(this, SetupLayouts::class.java).putExtra("layouts", false))
            true
        }
        R.id.action_prebuilt_add_layouts -> {
            startActivity(Intent(this, SetupLayouts::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openUrl(url: String) = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }

    // Referenced from about_app.xml via android:onClick
    fun openMail(v: View) = openUrl("mailto:vladgba@gmail.com")
    fun openWiki(v: View) = openUrl("https://github.com/vladgba/VKeyboard/wiki")
    fun openGithub(v: View) = openUrl("https://github.com/vladgba/VKeyboard")
    fun openPrivacyPolicy(v: View) = openUrl("http://zcxv.icu/vkeyb/privacy.html")
    fun openTermsOfUse(v: View) = openUrl("http://zcxv.icu/vkeyb/terms.html")
}

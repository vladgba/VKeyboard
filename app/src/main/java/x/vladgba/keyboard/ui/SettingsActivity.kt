package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import java.util.Locale

/** Visual editor for `settings.txt`: every option with a short explanation. Long-press restores the default. */
class SettingsActivity : LocalizedActivity() {
    private lateinit var page: Page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        page = Page(this)
        setContentView(page.view)
    }

    override fun onResume() {
        super.onResume()
        Settings.loadVars(this)
        render()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    // ======================= persistence =======================

    private fun isSet(key: String) = Settings.params.containsKey(key)

    private fun set(key: String, value: String) {
        Settings[key] = value
        save()
    }

    private fun reset(key: String) {
        if (!isSet(key)) return
        Settings.params.remove(key)
        save()
        Toast.makeText(this, R.string.ui_restored_default, Toast.LENGTH_SHORT).show()
        render()
    }

    private fun save() {
        Settings.save(this)
        Theme.invalidate()
    }

    private fun fmt(f: Float, decimals: Int = 2) = String.format(Locale.US, "%.${decimals}f", f).trimEnd('0').trimEnd('.')

    // ======================= rows =======================

    private fun bool(key: String, title: Int, sub: Int, def: Boolean) {
        page.switchRow(getString(title), getString(sub), Settings.bool(key, def)) { set(key, if (it) "1" else "0") }
            .setOnLongClickListener { reset(key); true }
    }

    /** Integer slider. [format] turns the value into display text. */
    private fun int(key: String, title: Int, sub: Int, min: Int, max: Int, step: Int, def: Int, format: (Int) -> String) {
        val (row, _) = page.sliderRow(getString(title), getString(sub), min.toFloat(), max.toFloat(), step.toFloat(),
            Settings.num(key, def).toFloat(), { format(it.toInt()) }) { set(key, it.toInt().toString()) }
        row.setOnLongClickListener { reset(key); true }
    }

    /** Label sizes are stored as "key height / N"; shown as a percentage of the default. */
    private fun textSize(key: String, title: Int, sub: Int, base: Float) {
        val pct = base / Settings.float(key, base).coerceAtLeast(0.1f) * 100f
        val (row, _) = page.sliderRow(getString(title), getString(sub), 50f, 200f, 5f, pct, { "${it.toInt()}%" }) {
            set(key, fmt(base * 100f / it))
        }
        row.setOnLongClickListener { reset(key); true }
    }

    private fun choice(key: String, title: Int, sub: Int, options: List<String>, after: () -> Unit = {}) {
        val cur = Settings.str(key)
        val row = page.row(getString(title), getString(sub)) {
            Ask.choice(this, getString(title), options, options.indexOf(cur)) {
                set(key, options[it])
                after()
                render()
            }
        }
        row.end.addView(android.widget.TextView(this).apply {
            text = cur.ifEmpty { "—" }
            setTextColor(COLOR_ACCENT)
            textSize = 15f
        })
        row.setOnLongClickListener { reset(key); true }
    }

    private fun voiceRows() {
        val tags = listOf(VOICE_LANG_AUTO) + x.vladgba.keyboard.keyboard.VoiceInput.LAYOUT_LANGS.values.distinct().sorted()
        fun name(tag: String) = if (tag == VOICE_LANG_AUTO) getString(R.string.vo_lang_auto)
            else Locale.forLanguageTag(tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
        val cur = Settings.str(SETTING_VOICE_LANG).ifBlank { VOICE_LANG_AUTO }
        val row = page.row(getString(R.string.vo_set_lang), getString(R.string.vo_set_lang_sub)) {
            Ask.choice(this, getString(R.string.vo_set_lang), tags.map { name(it) }, tags.indexOf(cur)) {
                set(SETTING_VOICE_LANG, tags[it])
                render()
            }
        }
        row.end.addView(android.widget.TextView(this).apply {
            text = name(cur)
            setTextColor(COLOR_ACCENT)
            textSize = 15f
        })
        row.setOnLongClickListener { reset(SETTING_VOICE_LANG); true }
        bool(SETTING_VOICE_OFFLINE, R.string.vo_set_offline, R.string.vo_set_offline_sub, false)
        val granted = android.os.Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val mic = page.row(getString(R.string.vo_set_mic), getString(if (granted) R.string.vo_mic_ok else R.string.vo_mic_missing)) {
            if (!granted) startActivity(Intent(this, VoicePermissionActivity::class.java))
        }
        mic.subtitle.setTextColor(if (granted) COLOR_OK else COLOR_WARN)
    }

    private fun render() {
        page.clear()
        page.note(getString(R.string.ui_settings_intro))

        page.row(getString(R.string.ui_language), getString(R.string.ui_language_sub)) { LanguagePicker.show(this) }
            .end.addView(android.widget.TextView(this).apply {
                text = LanguagePicker.currentName(this@SettingsActivity)
                setTextColor(COLOR_ACCENT)
                textSize = 15f
            })

        page.header(getString(R.string.ui_sec_typing))
        bool(SETTING_META_ONE_SHOT, R.string.ui_set_one_shot, R.string.ui_set_one_shot_sub, true)
        bool(SETTING_TEXT_EXPANSION, R.string.ui_set_expansion, R.string.ui_set_expansion_sub, true)
        page.row(getString(R.string.dictionary), getString(R.string.ui_set_dict_sub)) {
            startActivity(Intent(this, DictionaryActivity::class.java))
        }
        bool(SETTING_AUTO_CAPS, R.string.ui_set_auto_caps, R.string.ui_set_auto_caps_sub, true)
        bool(SETTING_DOUBLE_SPACE, R.string.ui_set_double_space, R.string.ui_set_double_space_sub, true)
        bool(SETTING_AUTO_NUM_LAYOUT, R.string.ui_set_auto_num, R.string.ui_set_auto_num_sub, true)

        page.header(getString(R.string.vo_sec))
        page.note(getString(R.string.vo_intro))
        voiceRows()

        page.header(getString(R.string.ui_sec_look))
        choice(SETTING_DEF_LAYOUT, R.string.ui_set_def_layout, R.string.ui_set_def_layout_sub, Bootstrap.textLayouts(this))
        val themes = PFile.list(this, THEME_EXT)
        choice(THEME_DAY, R.string.ui_set_day_theme, R.string.ui_set_day_theme_sub, themes)
        choice(THEME_NIGHT, R.string.ui_set_night_theme, R.string.ui_set_night_theme_sub, themes)
        val (pRow, _) = page.sliderRow(getString(R.string.ui_set_portrait), getString(R.string.ui_set_portrait_sub),
            60f, 150f, 5f, Settings.float(SETTING_PORTRAIT_HEIGHT, 1f) * 100f, { "${it.toInt()}%" }) {
            set(SETTING_PORTRAIT_HEIGHT, fmt(it / 100f))
        }
        pRow.setOnLongClickListener { reset(SETTING_PORTRAIT_HEIGHT); true }
        val (lRow, _) = page.sliderRow(getString(R.string.ui_set_landscape), getString(R.string.ui_set_landscape_sub),
            50f, 150f, 5f, Settings.float(SETTING_LANDSCAPE_HEIGHT, 1f) * 100f, { "${it.toInt()}%" }) {
            set(SETTING_LANDSCAPE_HEIGHT, fmt(it / 100f))
        }
        lRow.setOnLongClickListener { reset(SETTING_LANDSCAPE_HEIGHT); true }
        textSize(KEY_TEXT_SIZE_PRIMARY, R.string.ui_set_label_size, R.string.ui_set_label_size_sub, 2.5f)
        textSize(KEY_TEXT_SIZE_SECONDARY, R.string.ui_set_hint_size, R.string.ui_set_hint_size_sub, 5f)
        int(KEY_PADDING, R.string.ui_set_padding, R.string.ui_set_padding_sub, 0, 40, 1, 10) { "$it px" }
        int(KEY_BORDER_RADIUS, R.string.ui_set_radius, R.string.ui_set_radius_sub, 0, 60, 1, 10) { "$it px" }

        page.header(getString(R.string.ui_sec_touch))
        int(SENSE_HOLD_PRESS, R.string.ui_set_long_press, R.string.ui_set_long_press_sub, 100, 1000, 25, 300) { "$it ms" }
        int(SENSE_HOLD_PRESS_REPEAT, R.string.ui_set_repeat, R.string.ui_set_repeat_sub, 20, 300, 5, 50) { "$it ms" }
        int(SENSE_ADDITIONAL_CHARS, R.string.ui_set_swipe, R.string.ui_set_swipe_sub, 20, 250, 5, 70) { "$it px" }
        int(SENSE_HORIZONTAL_TICK, R.string.ui_set_joy_h, R.string.ui_set_joy_h_sub, 20, 250, 5, 70) { "$it px" }
        int(SENSE_VERTICAL_TICK, R.string.ui_set_joy_v, R.string.ui_set_joy_v_sub, 20, 250, 5, 60) { "$it px" }
        int(SENSE_HARD_PRESS, R.string.ui_set_hard, R.string.ui_set_hard_sub, 0, 1000, 25, 400) {
            if (it == 0) getString(R.string.ui_off) else "$it"
        }

        page.header(getString(R.string.ui_sec_feedback))
        val vib: (Int) -> String = { if (it < 10) getString(R.string.ui_off) else "$it ms" }
        int(KEY_VIBRATE_PRESS, R.string.ui_set_vib_press, R.string.ui_set_vib_press_sub, 0, 60, 5, 0, vib)
        int(KEY_VIBRATE_TICK, R.string.ui_set_vib_tick, R.string.ui_set_vib_tick_sub, 0, 60, 5, 0, vib)
        int(KEY_VIBRATE_ADDITIONAL, R.string.ui_set_vib_popup, R.string.ui_set_vib_popup_sub, 0, 60, 5, 0, vib)

        page.header(getString(R.string.ui_sec_hardware))
        bool(SETTING_REDEFINE_HW_ACTION, R.string.ui_set_hw_remap, R.string.ui_set_hw_remap_sub, false)
        page.row(getString(R.string.ui_key_tester), getString(R.string.ui_key_tester_sub)) {
            startActivity(Intent(this, PhysicalKeysActivity::class.java))
        }

        page.header(getString(R.string.ui_sec_advanced))
        page.row(getString(R.string.ts_title), getString(R.string.ts_home_sub)) {
            startActivity(Intent(this, TouchStatsActivity::class.java))
        }
        bool(SETTING_ERROR_TOASTS, R.string.ui_set_errors, R.string.ui_set_errors_sub, true)
        bool(SETTING_DEBUG, R.string.ui_set_debug, R.string.ui_set_debug_sub, false)
        val dev = page.row(getString(R.string.ui_set_input_device), Settings.str(SETTING_INPUT_DEVICE)) {
            Ask.text(this, getString(R.string.ui_set_input_device), Settings.str(SETTING_INPUT_DEVICE),
                getString(R.string.ui_set_input_device_sub), InputType.TYPE_CLASS_TEXT) {
                if (it.isBlank()) reset(SETTING_INPUT_DEVICE) else set(SETTING_INPUT_DEVICE, it.trim())
                render()
            }
        }
        dev.setOnLongClickListener { reset(SETTING_INPUT_DEVICE); true }
        page.row(getString(R.string.ui_set_raw), getString(R.string.ui_set_raw_sub)) {
            startActivity(Intent(this, RawEditor::class.java).putExtra("name", SETTINGS_FILENAME))
        }
        page.row(getString(R.string.ui_set_reset_all), getString(R.string.ui_set_reset_all_sub)) {
            confirm(this, R.string.ui_set_reset_all, R.string.ui_set_reset_all_confirm, R.string.yes) {
                // Keep the chosen layout, themes and hardware key remaps (managed in the key tester).
                val keep = listOf(SETTING_DEF_LAYOUT, THEME_DAY, THEME_NIGHT)
                Settings.params.keys.retainAll { it in keep || it.toIntOrNull() != null }
                save()
                render()
            }
        }
    }
}

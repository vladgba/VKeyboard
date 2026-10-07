package x.vladgba.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/**
 * Text expansion dictionary as a searchable list: type an abbreviation, then space or
 * punctuation, and it is replaced with the full text. Stored in `dict.dic` as `"abbr": "text"`.
 */
class DictionaryActivity : LocalizedActivity() {
    private lateinit var page: Page
    private lateinit var listBox: LinearLayout
    private lateinit var countView: TextView
    private val entries = LinkedHashMap<String, String>()
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.dictionary)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        page = Page(this)
        setContentView(page.view)
    }

    override fun onResume() {
        super.onResume()
        load()
        build()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.ui_edit_as_text)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != 1) return super.onOptionsItemSelected(item)
        startActivity(Intent(this, RawEditor::class.java).putExtra("name", DICT_FILENAME).putExtra("ext", DICT_EXT))
        return true
    }

    // ======================= storage =======================

    private fun load() {
        entries.clear()
        val node = FlexParser.parse(PFile(this, DICT_FILENAME, DICT_EXT).read())
        for ((k, v) in node.params) if (v is String) entries[k] = v
    }

    private fun save() {
        val node = FlexNode()
        for ((k, v) in entries) node[k] = v
        val text = HEADER + node.toString() + "\n"
        if (!PFile(this, DICT_FILENAME, DICT_EXT).write(text)) Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
    }

    // ======================= UI =======================

    private fun build() {
        page.clear()
        Settings.loadVars(this)
        page.note(getString(R.string.dict_intro))
        page.switchRow(getString(R.string.ui_set_expansion), getString(R.string.ui_set_expansion_sub), Settings.bool(SETTING_TEXT_EXPANSION, true)) {
            Settings[SETTING_TEXT_EXPANSION] = if (it) "1" else "0"
            Settings.save(this)
        }
        page.button(getString(R.string.dict_add)) { editDialog(null) }
        page.add(EditText(this).apply {
            hint = getString(R.string.dict_search)
            setSingleLine()
            setText(filter)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    filter = s.toString()
                    renderList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        countView = page.header("")
        listBox = page.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        renderList()

        page.header(getString(R.string.dict_examples))
        page.note(getString(R.string.dict_examples_sub))
        for ((abbr, text) in EXAMPLES) {
            if (entries.containsKey(abbr)) continue
            page.row("$abbr  →  $text", null) {
                entries[abbr] = text
                save()
                build()
            }
        }
    }

    private fun renderList() {
        listBox.removeAllViews()
        val q = filter.trim().lowercase()
        val shown = entries.filter { q.isEmpty() || it.key.lowercase().contains(q) || it.value.lowercase().contains(q) }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        countView.text = getString(R.string.dict_count, entries.size)
        if (entries.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.dict_empty)
                setTextColor(COLOR_SECONDARY_TEXT)
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })
        } else if (shown.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(R.string.dict_no_match)
                setTextColor(COLOR_SECONDARY_TEXT)
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })
        }
        for ((abbr, text) in shown) {
            val r = Row(this)
            r.title.text = abbr
            r.title.typeface = android.graphics.Typeface.MONOSPACE
            r.setSubtitle(text.replace('\n', '⏎'))
            r.subtitle.maxLines = 2
            r.subtitle.ellipsize = TextUtils.TruncateAt.END
            r.selectableBackground()
            r.setOnClickListener { editDialog(abbr) }
            listBox.addView(r)
        }
    }

    private fun editDialog(existing: String?) {
        val abbrInput = EditText(this).apply {
            hint = getString(R.string.dict_abbr_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existing ?: "")
        }
        val textInput = EditText(this).apply {
            hint = getString(R.string.dict_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(existing?.let { entries[it] } ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(TextView(context).apply { text = getString(R.string.dict_abbr_label); setTextColor(COLOR_SECONDARY_TEXT) })
            addView(abbrInput)
            addView(TextView(context).apply { text = getString(R.string.dict_text_label); setTextColor(COLOR_SECONDARY_TEXT) })
            addView(textInput)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.dict_add else R.string.dict_edit)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null) b.setNeutralButton(R.string.delete) { _, _ ->
            entries.remove(existing)
            save()
            renderList()
            build()
        }
        val dialog = b.create()
        dialog.show()
        // Validate without closing the dialog on errors.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val abbr = abbrInput.text.toString().trim()
            val text = textInput.text.toString()
            when {
                abbr.isEmpty() || abbr.any { it.isWhitespace() } -> abbrInput.error = getString(R.string.dict_err_abbr)
                text.isEmpty() -> textInput.error = getString(R.string.dict_err_text)
                abbr != existing && entries.containsKey(abbr) -> abbrInput.error = getString(R.string.dict_err_exists)
                else -> {
                    if (existing != null && existing != abbr) entries.remove(existing)
                    entries[abbr] = text
                    save()
                    dialog.dismiss()
                    build()
                }
            }
        }
    }

    companion object {
        private const val HEADER = "# Text expansion dictionary: \"abbreviation\": \"replacement\".\n" +
                "# Type the abbreviation followed by space, Enter or punctuation to expand it.\n"

        private val EXAMPLES = listOf(
            "brb" to "be right back",
            "omw" to "on my way",
            "ty" to "thank you",
            "shrug" to "¯\\_(ツ)_/¯",
            "tm" to "™",
        )
    }
}

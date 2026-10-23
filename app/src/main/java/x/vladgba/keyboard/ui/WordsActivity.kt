package x.vladgba.keyboard.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.UserDictionary
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*

/**
 * Words the keyboard learned from typing: search, delete, import and export (plain word list,
 * Gboard zip, AOSP `.combined` lists, Android's personal dictionary).
 */
class WordsActivity : LocalizedActivity() {
    private lateinit var page: Page
    private lateinit var listBox: LinearLayout
    private lateinit var countView: TextView
    private var filter = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.words_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        page = Page(this)
        setContentView(page.view)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        page.clear()
        Settings.loadVars(this)
        page.note(getString(R.string.words_intro))
        page.switchRow(getString(R.string.ui_set_learn), getString(R.string.ui_set_learn_sub), Settings.bool(SETTING_LEARN_WORDS, true)) {
            Settings[SETTING_LEARN_WORDS] = if (it) "1" else "0"
            Settings.save(this)
        }

        page.header(getString(R.string.words_transfer))
        page.row(getString(R.string.words_import), getString(R.string.words_import_sub)) { pick(REQ_IMPORT) }
        page.row(getString(R.string.words_export_plain), null) { create(REQ_EXPORT_PLAIN, "learned-words.txt", "text/plain") }
        page.row(getString(R.string.words_export_gboard), null) { create(REQ_EXPORT_GBOARD, "PersonalDictionary.zip", "application/zip") }
        page.row(getString(R.string.words_from_system), getString(R.string.words_from_system_sub)) { importSystem() }
        page.row(getString(R.string.words_to_system), getString(R.string.words_to_system_sub)) { exportSystem() }
        page.row(getString(R.string.words_clear), null) {
            confirm(this, R.string.confirm_title, R.string.words_clear_confirm) {
                UserWords.clear(this)
                build()
            }
        }

        page.header(getString(R.string.emoji_kw_title))
        page.note(getString(R.string.emoji_kw_intro, EmojiKeywords.size(this)))
        page.row(getString(R.string.emoji_kw_import), getString(R.string.emoji_kw_import_sub)) { pick(REQ_IMPORT_EMOJI) }
        if (EmojiKeywords.size(this) > 0) page.row(getString(R.string.emoji_kw_clear), null) {
            confirm(this, R.string.confirm_title, R.string.emoji_kw_clear_confirm) {
                EmojiKeywords.clear(this)
                build()
            }
        }

        page.header(getString(R.string.words_title))
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
    }

    private fun renderList() {
        listBox.removeAllViews()
        val all = UserWords.all(this)
        countView.text = getString(R.string.words_count, all.size)
        val q = filter.trim().lowercase()
        val shown = if (q.isEmpty()) all else all.filter { it.form.lowercase().contains(q) }
        if (all.isEmpty() || shown.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = getString(if (all.isEmpty()) R.string.words_empty else R.string.dict_no_match)
                setTextColor(COLOR_SECONDARY_TEXT)
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })
            return
        }
        for (w in shown.take(MAX_SHOWN)) {
            val r = Row(this)
            r.title.text = w.form
            r.subtitle.text = getString(R.string.words_used, w.count)
            r.subtitle.visibility = android.view.View.VISIBLE
            r.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(w.form)
                    .setMessage(R.string.words_delete_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        UserWords.remove(this, w.form)
                        renderList()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            listBox.addView(r)
        }
        if (shown.size > MAX_SHOWN) listBox.addView(TextView(this).apply {
            text = getString(R.string.words_more, shown.size - MAX_SHOWN)
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
    }

    // ======================= files =======================

    private fun pick(request: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, request)
    }

    private fun create(request: Int, name: String, mime: String) {
        UserWords.flush(this)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }, request)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_IMPORT -> background {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val words = UserWords.parse(bytes)
                val added = UserWords.merge(this, words)
                getString(if (words.isEmpty()) R.string.words_import_none else R.string.words_imported, added, words.size)
            }
            REQ_IMPORT_EMOJI -> background {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val n = EmojiKeywords.import(this, bytes)
                getString(if (n == 0) R.string.emoji_kw_none else R.string.emoji_kw_imported, n, EmojiKeywords.size(this))
            }
            REQ_EXPORT_PLAIN -> background {
                contentResolver.openOutputStream(uri)?.use { it.write(UserWords.exportPlain(this).toByteArray()) }
                getString(R.string.file_saved)
            }
            REQ_EXPORT_GBOARD -> background {
                contentResolver.openOutputStream(uri)?.use { UserWords.exportGboardZip(this, it) }
                getString(R.string.file_saved)
            }
        }
    }

    // ======================= Android personal dictionary =======================

    private fun importSystem() = background {
        val list = ArrayList<Pair<String, Int>>()
        contentResolver.query(UserDictionary.Words.CONTENT_URI,
            arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY), null, null, null)?.use { c ->
            while (c.moveToNext()) list.add(c.getString(0) to c.getInt(1).coerceAtLeast(1))
        }
        val added = UserWords.merge(this, list)
        getString(if (list.isEmpty()) R.string.words_system_empty else R.string.words_imported, added, list.size)
    }

    private fun exportSystem() {
        val all = UserWords.all(this)
        if (all.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.words_to_system)
            .setMessage(getString(R.string.words_to_system_confirm, all.size))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                background {
                    val existing = HashSet<String>()
                    contentResolver.query(UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD), null, null, null)?.use { c ->
                        while (c.moveToNext()) existing.add(c.getString(0).lowercase())
                    }
                    var n = 0
                    for (w in all) {
                        if (w.form.lowercase() in existing) continue
                        @Suppress("DEPRECATION")
                        UserDictionary.Words.addWord(this, w.form, w.count.coerceIn(1, 255), null, null)
                        n++
                    }
                    getString(R.string.words_exported_system, n)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Runs [work] off the UI thread, then shows its message (or the error) and refreshes the list. */
    private fun background(work: () -> String) {
        Toast.makeText(this, R.string.words_working, Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                work()
            } catch (e: SecurityException) {
                getString(R.string.words_system_denied)
            } catch (e: Exception) {
                getString(R.string.words_failed, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (!isFinishing) build()
            }
        }.start()
    }

    private companion object {
        const val REQ_IMPORT = 1
        const val REQ_EXPORT_PLAIN = 2
        const val REQ_EXPORT_GBOARD = 3
        const val REQ_IMPORT_EMOJI = 4
        const val MAX_SHOWN = 300
    }
}

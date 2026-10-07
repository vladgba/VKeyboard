package x.vladgba.keyboard.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/**
 * Text editor for layouts, themes, settings, macros and the dictionary.
 * Extras: `name`, optional `ext` (default txt) and `base` (asset to start from).
 *
 * Besides plain editing it gives: syntax colours, line numbers, live problem checking,
 * a live preview (layouts and themes) that jumps to the tapped key, a "where am I" bar,
 * find & replace, undo/redo, a symbol/action bar for touch screens and snippets.
 */
class RawEditor : LocalizedActivity() {
    private lateinit var editor: CodeEditText
    private lateinit var editName: EditText
    private lateinit var crumb: TextView
    private lateinit var badge: TextView
    private lateinit var position: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var replaceInput: EditText
    private lateinit var findCount: TextView
    private lateinit var previewBox: FrameLayout
    private var undoBtn: TextView? = null
    private var redoBtn: TextView? = null

    private var layoutPreview: LayoutPreview? = null
    private var themePreview: ThemePreview? = null

    private var name = ""
    private var ext = LAYOUT_EXT
    private var savedText = ""
    private var titleBase = ""

    private class Problem(val offset: Int, val line: Int, val msg: String)
    private var problems: List<Problem> = emptyList()
    private var matches: List<IntRange> = emptyList()
    private var matchIndex = -1

    private val prefs by lazy { getSharedPreferences("raw_editor", MODE_PRIVATE) }
    private val isLayout get() = ext == LAYOUT_EXT && name != SETTINGS_FILENAME
    private val isTheme get() = ext == THEME_EXT
    private val hasPreview get() = isLayout || isTheme

    // Which key the cursor is in (row/key index), for the preview highlight.
    private var curRow = -1
    private var curKey = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name") ?: return finish()
        val base = intent.getStringExtra("base") ?: ""
        ext = intent.getStringExtra("ext") ?: LAYOUT_EXT
        titleBase = getString(
            when {
                ext == THEME_EXT -> R.string.editor_title_theme
                ext == DICT_EXT -> R.string.editor_title_dict
                ext == MACROS_EXT -> R.string.ui_macros
                name == SETTINGS_FILENAME -> R.string.settings
                else -> R.string.editor_title_layout
            }
        )
        title = titleBase
        actionBar?.setDisplayHomeAsUpEnabled(true)
        buildUi()
        editName.setText(name)

        val file = PFile(this, name, ext)
        savedText = savedInstanceState?.getString(STATE_SAVED) ?: when {
            base.isNotEmpty() -> PFile.asset(this, base, ext) ?: ""
            file.exists() -> file.read()
            else -> PFile.asset(this, name, ext) ?: ""
        }
        editor.load(savedInstanceState?.getString(STATE_TEXT) ?: savedText)
        savedInstanceState?.getInt(STATE_CURSOR)?.let { c -> editor.post { editor.goToOffset(c) } }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(STATE_SAVED, savedText)
        out.putString(STATE_TEXT, editor.text.toString())
        out.putInt(STATE_CURSOR, editor.selectionStart)
    }

    // ------------------------------------------------------------------ UI

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Name
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            setBackgroundColor(COLOR_CARD)
            addView(TextView(context).apply { setText(R.string.name); setTextColor(COLOR_SECONDARY_TEXT); textSize = 14f })
            editName = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                isSingleLine = true
                textSize = 15f
                background = null
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            addView(editName, LinearLayout.LayoutParams(0, -2, 1f))
        })

        // Live preview
        previewBox = FrameLayout(this).apply { setPadding(dp(8), dp(4), dp(8), dp(4)); setBackgroundColor(COLOR_CARD) }
        if (isLayout) {
            layoutPreview = LayoutPreview(this, 18).apply {
                overlay = { c, keys -> drawCurrentKey(c, keys) }
                setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) previewTap(e.x, e.y); v.performClick(); true }
            }
            previewBox.addView(layoutPreview)
        } else if (isTheme) {
            themePreview = ThemePreview(this, compact = true).apply { onPick = { pickThemeColor(it) } }
            previewBox.addView(themePreview)
        }
        previewBox.visibility = if (hasPreview && prefs.getBoolean(PREF_PREVIEW, true)) View.VISIBLE else View.GONE
        root.addView(previewBox)

        // Find & replace
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xfffef7e0.toInt())
            setPadding(dp(8), dp(2), dp(4), dp(2))
            visibility = View.GONE
        }
        findInput = smallInput(R.string.re_find_hint)
        replaceInput = smallInput(R.string.re_replace_hint)
        findCount = TextView(this).apply { textSize = 13f; setTextColor(COLOR_SECONDARY_TEXT); minWidth = dp(44); gravity = Gravity.CENTER }
        findBar.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(findInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(findCount)
            addView(barButton("▲", R.string.re_prev) { stepMatch(-1) })
            addView(barButton("▼", R.string.re_next) { stepMatch(1) })
            addView(barButton("✕", R.string.re_close) { showFind(false) })
        })
        findBar.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(replaceInput, LinearLayout.LayoutParams(0, -2, 1f))
            addView(textButton(R.string.re_replace) { replaceOne() })
            addView(textButton(R.string.re_replace_all) { replaceAll() })
        })
        findInput.addTextChangedListener(simpleWatcher { refreshMatches(jump = true) })
        root.addView(findBar)

        // Editor
        editor = CodeEditText(this).apply {
            showSlots = isLayout
            setTextSizeSp(prefs.getFloat(PREF_SIZE, 14f))
            setWrap(prefs.getBoolean(PREF_WRAP, false))
            onAnalyzed = { analyzed() }
            onCursor = { cursorMoved() }
            onHistory = { refreshHistoryButtons() }
            onZoom = { prefs.edit().putFloat(PREF_SIZE, it).apply() }
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        // Status: where am I · problems · line:col
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_CARD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            crumb = TextView(context).apply {
                textSize = 13f; setTextColor(0xff3c4043.toInt()); isSingleLine = true; ellipsize = TextUtils.TruncateAt.START
            }
            addView(crumb, LinearLayout.LayoutParams(0, -2, 1f))
            badge = TextView(context).apply {
                textSize = 13f; setPadding(dp(10), dp(2), dp(10), dp(2)); isSingleLine = true
                setOnClickListener { showProblems() }
            }
            addView(badge)
            position = TextView(context).apply { textSize = 13f; setTextColor(COLOR_SECONDARY_TEXT); typeface = android.graphics.Typeface.MONOSPACE }
            addView(position)
        })

        // Symbol / action bar
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2), 0, dp(2), 0) }
        undoBtn = barButton("↶", R.string.re_undo) { editor.undo() }.also { bar.addView(it) }
        redoBtn = barButton("↷", R.string.re_redo) { editor.redo() }.also { bar.addView(it) }
        bar.addView(barButton("⇥", R.string.re_indent) { editor.indentLines() })
        bar.addView(barButton("⇤", R.string.re_outdent) { editor.outdentLines() })
        bar.addView(barButton("( )", R.string.re_ins_brackets) { editor.wrap("(", ")") })
        bar.addView(barButton("\" \"", R.string.re_ins_quotes) { editor.wrap("\"", "\"") })
        bar.addView(barButton(":", null) { editor.insert(": ") })
        bar.addView(barButton(",", null) { editor.insert(",") })
        bar.addView(barButton("#", R.string.re_comment) { editor.toggleComment() })
        bar.addView(barButton("⧉", R.string.re_duplicate) { editor.duplicateLine() })
        bar.addView(barButton("⬚", R.string.re_select_block) { editor.selectNode() })
        if (isTheme) bar.addView(barButton("🎨", R.string.re_ins_color) { colorAtCursor() })
        bar.addView(barButton("＋", R.string.re_insert) { showInsertMenu() })
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xffe8eaed.toInt())
            addView(bar)
        })

        setContentView(root)
        refreshHistoryButtons()
    }

    private fun smallInput(hint: Int) = EditText(this).apply {
        setHint(hint)
        isSingleLine = true
        textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun barButton(label: String, desc: Int?, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        minWidth = dp(44)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTextColor(0xff202124.toInt())
        selectableBackground()
        if (desc != null) {
            contentDescription = getString(desc)
            setOnLongClickListener { Toast.makeText(context, desc, Toast.LENGTH_SHORT).show(); true }
        }
        setOnClickListener { onClick() }
    }

    private fun textButton(res: Int, onClick: () -> Unit) = TextView(this).apply {
        setText(res)
        textSize = 14f
        setTextColor(COLOR_ACCENT)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        selectableBackground()
        setOnClickListener { onClick() }
    }

    private fun simpleWatcher(after: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = after()
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    private fun refreshHistoryButtons() {
        undoBtn?.alpha = if (editor.canUndo) 1f else 0.3f
        redoBtn?.alpha = if (editor.canRedo) 1f else 0.3f
    }

    // ------------------------------------------------------------------ analysis

    private fun analyzed() {
        val text = editor.text.toString()
        val lex = editor.lexed
        val list = ArrayList<Problem>()
        for (p in lex.problems) {
            val msg = getString(when (p.type) {
                CodeLexer.ProblemType.UNCLOSED -> R.string.re_err_unclosed
                CodeLexer.ProblemType.UNEXPECTED_CLOSE -> R.string.re_err_extra_close
                CodeLexer.ProblemType.UNTERMINATED -> R.string.re_err_string
            })
            list += Problem(p.offset, editor.lineOf(p.offset), msg)
        }
        val parser = FlexParser(text)
        val node = parser.parse()
        val seen = list.map { it.line }.toHashSet()
        for (w in parser.warnings) {
            val off = CodeLexer.offsetOf(text, w.line, w.col)
            val line = editor.lineOf(off)
            if (line in seen) continue
            seen += line
            list += Problem(off, line, w.msg)
        }
        list.sortBy { it.offset }
        problems = list
        editor.errorLines = list.map { it.line }.toSet()
        if (list.isEmpty()) {
            badge.text = "✓"
            badge.setTextColor(COLOR_OK)
        } else {
            badge.text = "⚠ " + list.size
            badge.setTextColor(COLOR_WARN)
        }
        badge.contentDescription = if (list.isEmpty()) getString(R.string.re_no_problems) else getString(R.string.re_n_problems, list.size)

        // Preview follows the text as long as the brackets are sound.
        if (lex.problems.isEmpty()) {
            layoutPreview?.layout = node
            themePreview?.let { tp ->
                val th = Theme.upgrade(node)
                tp.colors = { ThemeColors.color(th, it) }
            }
        }
        title = if (text == savedText) titleBase else "$titleBase •"
        if (findBar.visibility == View.VISIBLE) refreshMatches(jump = false)
        cursorMoved()
        refreshHistoryButtons()
    }

    private fun cursorMoved() {
        if (!::crumb.isInitialized) return
        val c = editor.selectionStart.coerceAtLeast(0)
        val line = editor.lineOf(c)
        position.text = getString(R.string.re_pos, line + 1, c - editor.lineStart(line) + 1)
        crumb.text = describe(c)
        problems.firstOrNull { it.line == line }?.let { crumb.text = "⚠ " + it.msg }
        crumb.setTextColor(if (problems.any { it.line == line }) COLOR_WARN else 0xff3c4043.toInt())
    }

    /** Human description of where the cursor is: "Row 2 › Key 3: q › swipe ↓". */
    private fun describe(offset: Int): String {
        val lex = editor.lexed
        val text = editor.text ?: return ""
        val node = lex.nodeAt(offset)
        val path = ArrayList<CodeLexer.Node>()
        var n = node
        while (n != null) { path.add(0, n); n = n.parent }
        val parts = ArrayList<String>()
        val tok = lex.tokAt(offset) ?: lex.tokAt(offset - 1)
        var row = -1
        var key = -1
        if (isLayout) {
            for (p in path) when (p.depth) {
                1 -> { row = p.nodeIndex; parts += getString(R.string.re_row, p.nodeIndex + 1) }
                2 -> {
                    key = p.nodeIndex
                    val label = p.label
                    parts += if (label.isNullOrEmpty()) getString(R.string.re_key, p.nodeIndex + 1)
                    else getString(R.string.re_key_named, p.nodeIndex + 1, Labels.resolve(this, label))
                }
                else -> if (p.depth > 2) parts += p.name ?: "#${p.nodeIndex + 1}"
            }
            if (tok != null && tok.slot in 0..7 && node?.depth == 2)
                parts += getString(R.string.re_swipe, CodeEditText.SLOT_ARROWS[tok.slot])
        } else {
            for (p in path) if (p.depth > 0) parts += p.name ?: "#${p.nodeIndex + 1}"
        }
        if (tok != null && tok.kind == CodeLexer.Kind.KEY) {
            val k = CodeLexer.unquote(text, tok)
            val info = ThemeColors.info(k)
            parts += if (info != null) getString(info.title) else k
        } else if (isTheme) {
            // Name the colour on the cursor's line.
            val line = editor.lineOf(offset)
            val from = editor.lineStart(line)
            var i = lex.firstTokAfter(from)
            while (i < lex.toks.size && editor.lineOf(lex.toks[i].start) == line) {
                val t = lex.toks[i++]
                if (t.kind == CodeLexer.Kind.KEY) ThemeColors.info(CodeLexer.unquote(text, t))?.let { parts += getString(it.title) }
            }
        }
        if (row != curRow || key != curKey) {
            curRow = row; curKey = key
            layoutPreview?.invalidate()
        }
        return parts.joinToString("  ›  ")
    }

    private fun showProblems() {
        if (problems.isEmpty()) {
            Toast.makeText(this, R.string.re_no_problems, Toast.LENGTH_SHORT).show()
            return
        }
        val items = problems.map { getString(R.string.re_line, it.line + 1, it.msg) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.re_n_problems, problems.size))
            .setItems(items) { _, i -> editor.goToOffset(problems[i].offset) }
            .setNegativeButton(R.string.re_close, null)
            .show()
    }

    // ------------------------------------------------------------------ preview interaction

    private val keyOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = COLOR_ACCENT }

    private fun drawCurrentKey(c: Canvas, keys: List<LayoutPreview.KeyRect>) {
        val k = keys.firstOrNull { it.row == curRow && it.index == curKey } ?: return
        keyOutline.strokeWidth = dp(2).toFloat()
        val r = RectF(k.rect).apply { inset(-dp(1).toFloat(), -dp(1).toFloat()) }
        c.drawRoundRect(r, dp(4).toFloat(), dp(4).toFloat(), keyOutline)
    }

    private fun previewTap(x: Float, y: Float) {
        val lp = layoutPreview ?: return
        val k = lp.keys.firstOrNull { it.rect.contains(x - lp.left, y - lp.top) || it.rect.contains(x, y) } ?: return
        val node = editor.lexed.nodes.firstOrNull { it.depth == 2 && it.nodeIndex == k.index && it.parent?.nodeIndex == k.row && it.parent?.depth == 1 }
            ?: return
        // Land on the key's label value when there is one, else just inside the bracket.
        val text = editor.text ?: return
        val lex = editor.lexed
        var target = node.open + 1
        var i = lex.firstTokAfter(node.open)
        while (i + 2 < lex.toks.size && lex.toks[i].start < (if (node.close < 0) text.length else node.close)) {
            val t = lex.toks[i]
            if (t.kind == CodeLexer.Kind.KEY && CodeLexer.unquote(text, t) == KEY_KEY) {
                val v = lex.toks.getOrNull(i + 2)
                if (v != null && v.kind == CodeLexer.Kind.STRING) target = v.end - 1
                break
            }
            i++
        }
        editor.goToOffset(target)
    }

    /** Value token of `"param": value` at the top level of the file, or null. */
    private fun findParamValue(param: String): CodeLexer.Tok? {
        val text = editor.text ?: return null
        val toks = editor.lexed.toks
        for (i in toks.indices) {
            val t = toks[i]
            if (t.kind == CodeLexer.Kind.KEY && CodeLexer.unquote(text, t) == param) {
                for (j in i + 1 until minOf(toks.size, i + 4)) {
                    val v = toks[j]
                    if (v.kind == CodeLexer.Kind.STRING || v.kind == CodeLexer.Kind.NUMBER || v.kind == CodeLexer.Kind.WORD) return v
                }
            }
        }
        return null
    }

    private fun pickThemeColor(param: String) {
        val text = editor.text ?: return
        val v = findParamValue(param)
        val start = v?.let { CodeLexer.colorOf(text, it) } ?: themeColorNow(param)
        v?.let { editor.goToOffset(it.start + 1, it.end - 1) }
        val title = ThemeColors.info(param)?.let { getString(it.title) } ?: param
        openColorPicker(start, title) { color -> setThemeColor(param, color) }
    }

    private fun themeColorNow(param: String): Int =
        ThemeColors.color(Theme.upgrade(FlexParser.parse(editor.text.toString())), param)

    private fun setThemeColor(param: String, color: Int) {
        val text = editor.text ?: return
        val hex = Theme.toHex(color)
        val v = findParamValue(param)
        if (v != null) {
            editor.replaceRange(v.start, v.end, "\"$hex\"", v.start + 1)
            editor.setSelection(v.start + 1, v.start + 1 + hex.length)
        } else {
            val prefix = if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""
            val line = "$prefix\"$param\":\"$hex\"\n"
            editor.replaceRange(text.length, text.length, line)
        }
    }

    private fun colorAtCursor() {
        val text = editor.text ?: return
        val c = editor.selectionStart
        val tok = editor.editorTokenAround(c)
        val color = tok?.let { CodeLexer.colorOf(text, it) }
        if (tok != null && color != null) {
            openColorPicker(color, null) { picked ->
                editor.replaceRange(tok.start, tok.end, "\"" + Theme.toHex(picked) + "\"", tok.start + 1)
            }
        } else showColorKeyChooser()
    }

    private fun showColorKeyChooser() {
        val all = ThemeColors.ALL
        AlertDialog.Builder(this)
            .setTitle(R.string.re_ins_color)
            .setItems(all.map { getString(it.title) }.toTypedArray()) { _, i -> pickThemeColor(all[i].key) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openColorPicker(start: Int, title: CharSequence?, onOk: (Int) -> Unit) {
        ColorPicker(this, start, true, object : ColorPicker.ColorPickerListener {
            override fun onOk(dialog: ColorPicker, color: Int) = onOk(color)
            override fun onCancel(dialog: ColorPicker) {}
        }, null, title).show()
    }

    private fun CodeEditText.editorTokenAround(c: Int): CodeLexer.Tok? =
        lexed.tokAt(c)?.takeIf { it.kind == CodeLexer.Kind.STRING } ?: lexed.tokAt(c - 1)?.takeIf { it.kind == CodeLexer.Kind.STRING }

    // ------------------------------------------------------------------ insert menu

    private fun showInsertMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (isLayout) {
            items += getString(R.string.re_ins_key) to { snippet("(\n    \"key\": \"$CURSOR\"\n),") }
            items += getString(R.string.re_ins_key_slots) to {
                snippet("(\n    \"key\": \"$CURSOR\",\n" + (0 until 8).joinToString("") { "    \"\",\n" } + "),")
            }
            items += getString(R.string.re_ins_row) to { snippet("(\n    (\n        \"key\": \"$CURSOR\"\n    )\n),") }
        }
        if (isTheme) items += getString(R.string.re_ins_color) to { showColorKeyChooser() }
        if (ext == LAYOUT_EXT || ext == MACROS_EXT) {
            items += getString(R.string.re_ins_param) to { pickParam() }
            items += getString(R.string.re_ins_action) to {
                ActionPrompt.show(this, getString(R.string.re_ins_action), "") { v -> insertValue(v) }
            }
        }
        if (items.isEmpty()) items += getString(R.string.re_ins_quotes) to { editor.wrap("\"", "\"") }
        AlertDialog.Builder(this)
            .setTitle(R.string.re_insert)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickParam() {
        val names = PARAMS
        AlertDialog.Builder(this)
            .setTitle(R.string.re_ins_param)
            .setItems(names) { _, i -> snippet("\"${names[i]}\": \"$CURSOR\",") }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Inserts a quoted value, or fills the empty string the cursor is in. */
    private fun insertValue(v: String) {
        val text = editor.text ?: return
        val quoted = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val tok = editor.editorTokenAround(editor.selectionStart)
        if (tok != null && tok.end - tok.start == 2 && text.substring(tok.start, tok.end) == "\"\"")
            editor.replaceRange(tok.start, tok.end, quoted)
        else editor.insert(quoted)
    }

    /** Inserts multi-line [s] at the cursor, re-indented to the current line; `$CURSOR` marks the caret. */
    private fun snippet(s: String) {
        val c = editor.selectionStart.coerceAtLeast(0)
        val text = editor.text ?: return
        val lineStart = editor.lineStart(editor.lineOf(c))
        val blankLine = (lineStart until c).all { text[it] == ' ' }
        val ind = editor.indentOf(editor.lineOf(c))
        val body = s.split('\n').mapIndexed { i, ln -> if (i == 0) ln else ind + ln }.joinToString("\n")
        val full = if (blankLine) body else "\n$ind$body"
        val caret = full.indexOf(CURSOR)
        val clean = full.replace(CURSOR, "")
        editor.replaceRange(c, c, clean, c + if (caret >= 0) caret else clean.length)
    }

    // ------------------------------------------------------------------ find & replace

    private fun showFind(show: Boolean) {
        findBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val sel = editor.text!!.substring(minOf(editor.selectionStart, editor.selectionEnd), maxOf(editor.selectionStart, editor.selectionEnd))
            if (sel.isNotEmpty() && !sel.contains('\n')) findInput.setText(sel)
            findInput.requestFocus()
            findInput.selectAll()
            refreshMatches(jump = false)
        } else {
            matches = emptyList()
            editor.markFound(matches, -1)
            editor.requestFocus()
        }
    }

    private fun refreshMatches(jump: Boolean) {
        val q = findInput.text.toString()
        val text = editor.text.toString()
        val out = ArrayList<IntRange>()
        if (q.isNotEmpty()) {
            var i = text.indexOf(q, 0, ignoreCase = true)
            while (i >= 0 && out.size < MAX_MATCHES) {
                out += i until i + q.length
                i = text.indexOf(q, i + q.length, ignoreCase = true)
            }
        }
        matches = out
        val c = minOf(editor.selectionStart, editor.selectionEnd)
        matchIndex = if (out.isEmpty()) -1 else out.indexOfFirst { it.first >= c }.let { if (it < 0) 0 else it }
        updateFindUi()
        if (jump && matchIndex >= 0) revealMatch()
    }

    private fun updateFindUi() {
        findCount.text = when {
            findInput.text.isEmpty() -> ""
            matches.isEmpty() -> "0"
            else -> "${matchIndex + 1}/${matches.size}"
        }
        findCount.setTextColor(if (findInput.text.isNotEmpty() && matches.isEmpty()) COLOR_WARN else COLOR_SECONDARY_TEXT)
        editor.markFound(matches, matchIndex)
    }

    private fun revealMatch() {
        val r = matches.getOrNull(matchIndex) ?: return
        editor.goToOffset(r.first, r.last + 1)
        findInput.requestFocus()
    }

    private fun stepMatch(d: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + d + matches.size) % matches.size
        updateFindUi()
        revealMatch()
    }

    private fun replaceOne() {
        val r = matches.getOrNull(matchIndex) ?: return
        val rep = replaceInput.text.toString()
        editor.replaceRange(r.first, r.last + 1, rep)
        editor.post {
            refreshMatches(jump = false)
            // Continue after the replaced text.
            val after = r.first + rep.length
            matchIndex = matches.indexOfFirst { it.first >= after }.let { if (it < 0) (if (matches.isEmpty()) -1 else 0) else it }
            updateFindUi()
            if (matchIndex >= 0) revealMatch()
        }
    }

    private fun replaceAll() {
        val q = findInput.text.toString()
        if (q.isEmpty() || matches.isEmpty()) return
        val n = matches.size
        val text = editor.text.toString()
        val out = text.replace(q, replaceInput.text.toString(), ignoreCase = true)
        editor.replaceRange(0, text.length, out, minOf(editor.selectionStart, out.length))
        Toast.makeText(this, getString(R.string.re_replaced, n), Toast.LENGTH_SHORT).show()
        editor.post { refreshMatches(jump = false) }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_SAVE, 0, R.string.save).setIcon(android.R.drawable.ic_menu_save).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, M_FIND, 1, R.string.re_find).setIcon(android.R.drawable.ic_menu_search).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        if (hasPreview) menu.add(0, M_PREVIEW, 2, R.string.re_preview).setCheckable(true).isChecked = previewBox.visibility == View.VISIBLE
        menu.add(0, M_WRAP, 3, R.string.re_wrap).setCheckable(true).isChecked = prefs.getBoolean(PREF_WRAP, false)
        menu.add(0, M_GOTO, 4, R.string.re_goto)
        menu.add(0, M_PROBLEMS, 5, R.string.re_problems_title)
        menu.add(0, M_BIGGER, 6, R.string.re_text_bigger)
        menu.add(0, M_SMALLER, 7, R.string.re_text_smaller)
        menu.add(0, M_REFORMAT, 8, R.string.raw_reformat)
        if (PFile.asset(this, name, ext) != null) menu.add(0, M_RESTORE, 9, R.string.re_restore)
        menu.add(0, M_HELP, 10, R.string.re_help)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> onBackPressed()
            M_SAVE -> save()
            M_FIND -> showFind(findBar.visibility != View.VISIBLE)
            M_PREVIEW -> {
                val on = !item.isChecked
                item.isChecked = on
                previewBox.visibility = if (on) View.VISIBLE else View.GONE
                prefs.edit().putBoolean(PREF_PREVIEW, on).apply()
            }
            M_WRAP -> {
                val on = !item.isChecked
                item.isChecked = on
                editor.setWrap(on)
                prefs.edit().putBoolean(PREF_WRAP, on).apply()
            }
            M_GOTO -> goToLine()
            M_PROBLEMS -> showProblems()
            M_BIGGER, M_SMALLER -> {
                val cur = prefs.getFloat(PREF_SIZE, 14f)
                val sp = (cur + if (item.itemId == M_BIGGER) 2f else -2f).coerceIn(CodeEditText.MIN_SP, CodeEditText.MAX_SP)
                editor.setTextSizeSp(sp)
                prefs.edit().putFloat(PREF_SIZE, sp).apply()
            }
            M_REFORMAT -> {
                val doIt = {
                    val t = editor.text.toString()
                    editor.replaceRange(0, t.length, FlexParser.parse(t).toString(), 0)
                }
                if (editor.lexed.toks.any { it.kind == CodeLexer.Kind.COMMENT }) confirm(this, R.string.confirm_title, R.string.re_reformat_confirm) { doIt() }
                else doIt()
            }
            M_RESTORE -> confirm(this, R.string.confirm_title, R.string.re_restore_confirm) {
                val orig = PFile.asset(this, name, ext) ?: return@confirm
                editor.replaceRange(0, editor.text!!.length, orig, 0)
            }
            M_HELP -> Ask.info(this, getString(R.string.re_help), getString(R.string.re_help_text))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun goToLine() {
        val n = editor.lineCount()
        Ask.text(this, getString(R.string.re_goto), "", getString(R.string.re_goto_hint, n), InputType.TYPE_CLASS_NUMBER) { v ->
            val line = (v.trim().toIntOrNull() ?: return@text).coerceIn(1, n) - 1
            editor.goToOffset(editor.lineStart(line))
        }
    }

    // Hardware keyboard shortcuts.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_S) { save(); return true }
        if (event.action == KeyEvent.ACTION_DOWN && editor.hasFocus()) {
            if (event.isCtrlPressed) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) editor.redo() else editor.undo(); return true }
                    KeyEvent.KEYCODE_Y -> { editor.redo(); return true }
                    KeyEvent.KEYCODE_F -> { showFind(true); return true }
                    KeyEvent.KEYCODE_G -> { goToLine(); return true }
                    KeyEvent.KEYCODE_D -> { editor.duplicateLine(); return true }
                    KeyEvent.KEYCODE_SLASH -> { editor.toggleComment(); return true }
                }
            } else if (event.keyCode == KeyEvent.KEYCODE_TAB && !event.isAltPressed) {
                if (event.isShiftPressed) editor.outdentLines() else editor.indentLines()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ------------------------------------------------------------------ save / leave

    private fun save() {
        val newName = editName.text.toString().trim()
        if (newName.isEmpty() || newName.contains('/')) {
            Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val text = editor.text.toString()
        if (!PFile(this, newName, ext).write(text)) {
            Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
            return
        }
        name = newName
        savedText = text
        title = titleBase
        if (ext == THEME_EXT) Theme.invalidate()
        if (problems.isEmpty()) Toast.makeText(this, R.string.file_saved, Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, getString(R.string.parse_warnings, problems.size, getString(R.string.re_line, problems[0].line + 1, problems[0].msg)), Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (findBar.visibility == View.VISIBLE) return showFind(false)
        if (editor.text.toString() == savedText) return finish()
        confirm(this, R.string.confirm_title, R.string.confirm_unsaved) { finish() }
    }

    companion object {
        private const val CURSOR = "\u0001"
        private const val MAX_MATCHES = 2000
        private const val PREF_PREVIEW = "preview"
        private const val PREF_WRAP = "wrap"
        private const val PREF_SIZE = "textSize"
        private const val STATE_SAVED = "saved"
        private const val STATE_TEXT = "text"
        private const val STATE_CURSOR = "cursor"
        private const val M_SAVE = 1
        private const val M_FIND = 2
        private const val M_PREVIEW = 3
        private const val M_WRAP = 4
        private const val M_GOTO = 5
        private const val M_PROBLEMS = 6
        private const val M_BIGGER = 7
        private const val M_SMALLER = 8
        private const val M_REFORMAT = 9
        private const val M_RESTORE = 10
        private const val M_HELP = 11

        private val PARAMS = arrayOf(
            KEY_KEY, KEY_CODE, KEY_TEXT, KEY_HOLD, KEY_HOLD_REPEAT, KEY_WIDTH, ROW_HEIGHT, KEY_VISIBLE,
            KEY_MODE, KEY_MOD_META, KEY_COMBO, KEY_MACRO, KEY_LAYOUT, KEY_ACTION,
            KEY_TOP_ACTION, KEY_BOTTOM_ACTION, KEY_LEFT_ACTION, KEY_RIGHT_ACTION,
            KEY_ACTION_ON_SHIFT, KEY_ACTION_ON_CTRL, KEY_ACTION_ON_ALT, KEY_TEXT_CURSOR_OFFSET,
            KEY_TEXT_SIZE_PRIMARY, KEY_TEXT_SIZE_SECONDARY, KEY_PADDING, KEY_BORDER_RADIUS, KEY_SHADOW, KEY_HIDE_BORDERS,
        )
    }
}

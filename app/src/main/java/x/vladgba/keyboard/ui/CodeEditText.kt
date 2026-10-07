package x.vladgba.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Code editor view for Flexaml files: line-number gutter, syntax colours, rainbow brackets,
 * bracket matching, colour swatches, popup-slot arrows, error marks, undo/redo, auto-indent,
 * fling scrolling and pinch-to-zoom. Plain framework EditText underneath, no libraries.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
internal class CodeEditText(ctx: Context) : EditText(ctx) {
    /** Called (debounced) after the text changed; [lexed] is already fresh. */
    var onAnalyzed: (() -> Unit)? = null
    var onCursor: (() -> Unit)? = null
    var onHistory: (() -> Unit)? = null
    var onZoom: ((Float) -> Unit)? = null

    /** Draw ↖ ↑ ↗ … next to positional values (layouts only). */
    var showSlots = false

    var lexed: CodeLexer.Result = CodeLexer.Result(emptyList(), emptyList(), emptyMap(), emptyList())
        private set

    /** Logical (0-based) lines with problems. */
    var errorLines: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }

    private var lineStarts = IntArray(1)

    // --- colours (light editor, matches the rest of the settings UI) ---
    private val cComment = 0xff8a8f98.toInt()
    private val cString = 0xff0b7a3e.toInt()
    private val cKey = 0xff1a5fb4.toInt()
    private val cNumber = 0xffb5530a.toInt()
    private val cWord = 0xff8e24aa.toInt()
    private val cPunct = 0xff5f6368.toInt()
    private val rainbow = intArrayOf(0xff1a73e8.toInt(), 0xffd81b60.toInt(), 0xff00897b.toInt(), 0xffef6c00.toInt(), 0xff6d4c41.toInt())

    private class Fg(c: Int) : ForegroundColorSpan(c)
    private class Bg(c: Int) : BackgroundColorSpan(c)
    private class Match(c: Int) : BackgroundColorSpan(c)
    private class Found(c: Int) : BackgroundColorSpan(c)

    private val gutterBg = Paint().apply { color = 0xfff1f3f4.toInt() }
    private val gutterLine = Paint().apply { color = 0xffdadce0.toInt() }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT }
    private val curLine = Paint().apply { color = 0x141a73e8 }
    private val errLine = Paint().apply { color = 0x1ad93025 }
    private val errDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_WARN }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xff9aa0a6.toInt() }
    private var gutterW = 0
    private var ready = false
    private var wrapOn = false

    // ------------------------------------------------------------------ text loading

    fun load(text: String) {
        applying = true
        setText(text)
        applying = false
        undo.clear(); redo.clear()
        onHistory?.invoke()
        analyzeNow()
        setSelection(0)
    }

    fun setWrap(wrap: Boolean) {
        wrapOn = wrap
        setHorizontallyScrolling(!wrap)
        if (wrap) scrollTo(0, scrollY)
        requestLayout()
    }

    fun setTextSizeSp(sp: Float) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        updateGutter()
    }

    // ------------------------------------------------------------------ analysis + colours

    private val analyzeRunnable = Runnable { analyzeNow() }
    private val highlightRunnable = Runnable { highlightVisible() }

    private fun analyzeNow() {
        val t = text ?: return
        lexed = CodeLexer.lex(t)
        var count = 1
        for (i in 0 until t.length) if (t[i] == '\n') count++
        val starts = IntArray(count)
        var k = 1
        for (i in 0 until t.length) if (t[i] == '\n') starts[k++] = i + 1
        lineStarts = starts
        updateGutter()
        highlightVisible()
        updateBracketMatch()
        onAnalyzed?.invoke()
    }

    fun lineOf(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lineCount() = lineStarts.size
    fun lineStart(line: Int) = lineStarts[line.coerceIn(0, lineStarts.size - 1)]

    private fun updateGutter() {
        numPaint.textSize = textSize * 0.82f
        val digits = max(2, lineStarts.size.toString().length)
        val w = (numPaint.measureText("0".repeat(digits)) + context.dp(14)).toInt()
        if (w != gutterW) {
            gutterW = w
            setPadding(w + context.dp(6), context.dp(6), context.dp(24), context.dp(48))
        }
    }

    private fun highlightVisible() {
        val t = text ?: return
        val l = layout ?: return
        for (s in t.getSpans(0, t.length, Fg::class.java)) t.removeSpan(s)
        for (s in t.getSpans(0, t.length, Bg::class.java)) t.removeSpan(s)
        val top = max(0, scrollY - height)
        val bottom = scrollY + height * 2
        val from = l.getLineStart(l.getLineForVertical(top))
        val to = l.getLineEnd(l.getLineForVertical(bottom))
        val toks = lexed.toks
        var i = lexed.firstTokAfter(from)
        val len = t.length
        while (i < toks.size) {
            val tk = toks[i++]
            if (tk.start >= to) break
            if (tk.end > len) break
            val color = when (tk.kind) {
                CodeLexer.Kind.COMMENT -> cComment
                CodeLexer.Kind.STRING -> cString
                CodeLexer.Kind.KEY -> cKey
                CodeLexer.Kind.NUMBER -> cNumber
                CodeLexer.Kind.WORD -> cWord
                CodeLexer.Kind.OPEN, CodeLexer.Kind.CLOSE -> rainbow[tk.depth % rainbow.size]
                else -> cPunct
            }
            t.setSpan(Fg(color), tk.start, tk.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val swatch = CodeLexer.colorOf(t, tk)
            if (swatch != null && tk.end - tk.start > 2) {
                val solid = ThemeColors.over(swatch, Color.WHITE)
                t.setSpan(Bg(solid), tk.start + 1, tk.end - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                t.setSpan(Fg(if (ThemeColors.luminance(solid) < 140) Color.WHITE else Color.BLACK), tk.start + 1, tk.end - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // ------------------------------------------------------------------ brackets + search marks

    private fun updateBracketMatch() {
        val t = text ?: return
        for (s in t.getSpans(0, t.length, Match::class.java)) t.removeSpan(s)
        val c = selectionStart
        if (c < 0 || c != selectionEnd) return
        val pairs = lexed.pairs
        val at = when {
            c > 0 && pairs.containsKey(c - 1) -> c - 1
            pairs.containsKey(c) -> c
            else -> return
        }
        val other = pairs[at] ?: return
        if (max(at, other) >= t.length) return
        t.setSpan(Match(0x551a73e8), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.setSpan(Match(0x551a73e8), other, other + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Marks search matches; [current] gets a stronger colour. */
    fun markFound(ranges: List<IntRange>, current: Int) {
        val t = text ?: return
        for (s in t.getSpans(0, t.length, Found::class.java)) t.removeSpan(s)
        for ((i, r) in ranges.withIndex()) {
            if (r.last + 1 > t.length) break
            t.setSpan(Found(if (i == current) 0xaaffb300.toInt() else 0x55ffd54f), r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!ready) return // called from the super constructor
        updateBracketMatch()
        onCursor?.invoke()
        invalidate()
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (ready && abs(vert - oldVert) > 0) {
            removeCallbacks(highlightRunnable)
            postDelayed(highlightRunnable, 60)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (ready) post(highlightRunnable)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val l = layout
        if (l == null || !ready) return super.onDraw(canvas)
        val pt = extendedPaddingTop.toFloat()
        val first = l.getLineForVertical(max(0, scrollY - extendedPaddingTop))
        val last = l.getLineForVertical(scrollY + height)
        val left = scrollX.toFloat()
        val right = left + width

        // Current line + lines with problems, behind the text.
        val sel = selectionStart
        if (sel >= 0 && sel == selectionEnd && isFocused) {
            val vl = l.getLineForOffset(sel)
            canvas.drawRect(left, l.getLineTop(vl) + pt, right, l.getLineBottom(vl) + pt, curLine)
        }
        if (errorLines.isNotEmpty()) for (vl in first..last) {
            if (lineOf(l.getLineStart(vl)) in errorLines)
                canvas.drawRect(left, l.getLineTop(vl) + pt, right, l.getLineBottom(vl) + pt, errLine)
        }

        super.onDraw(canvas)

        // Popup-slot arrows at the end of lines holding exactly one positional value.
        if (showSlots) drawSlots(canvas, first, last, pt)

        // Gutter stays put while scrolling sideways.
        canvas.drawRect(left, scrollY.toFloat(), left + gutterW, (scrollY + height).toFloat(), gutterBg)
        canvas.drawRect(left + gutterW - 1, scrollY.toFloat(), left + gutterW.toFloat(), (scrollY + height).toFloat(), gutterLine)
        val curLogical = if (sel >= 0) lineOf(sel) else -1
        for (vl in first..last) {
            val start = l.getLineStart(vl)
            val logical = lineOf(start)
            if (lineStarts[logical] != start && vl != 0) continue // wrapped continuation
            val base = l.getLineBaseline(vl) + pt
            val err = logical in errorLines
            numPaint.color = when {
                err -> COLOR_WARN
                logical == curLogical -> 0xff202124.toInt()
                else -> 0xff9aa0a6.toInt()
            }
            numPaint.isFakeBoldText = err || logical == curLogical
            canvas.drawText((logical + 1).toString(), left + gutterW - context.dp(8), base, numPaint)
            if (err) canvas.drawCircle(left + context.dp(5), base - numPaint.textSize / 3, context.dp(3).toFloat(), errDot)
        }
    }

    private fun drawSlots(canvas: Canvas, first: Int, last: Int, pt: Float) {
        val l = layout ?: return
        val t = text ?: return
        slotPaint.textSize = textSize * 0.85f
        val toks = lexed.toks
        val from = l.getLineStart(first)
        val to = l.getLineEnd(last)
        var i = lexed.firstTokAfter(from)
        var lastLine = -1
        var lastTok: CodeLexer.Tok? = null
        var many = false
        fun flush() {
            val tk = lastTok ?: return
            if (!many && tk.slot in 0..7) {
                val vl = l.getLineForOffset(tk.start)
                val x = l.getLineRight(vl) + compoundPaddingLeft + context.dp(10)
                canvas.drawText(SLOT_ARROWS[tk.slot], x, l.getLineBaseline(vl) + pt, slotPaint)
            }
        }
        while (i < toks.size) {
            val tk = toks[i++]
            if (tk.start >= to || tk.end > t.length) break
            if (tk.kind == CodeLexer.Kind.COMMENT || tk.kind == CodeLexer.Kind.SEP) continue
            val line = l.getLineForOffset(tk.start)
            if (line != lastLine) {
                flush()
                lastLine = line; lastTok = tk; many = false
            } else many = true
        }
        flush()
    }

    // ------------------------------------------------------------------ scrolling + zoom

    private val scroller = OverScroller(ctx)
    private val flinger = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val l = layout ?: return false
            val maxY = max(0, l.height + totalPaddingTop + totalPaddingBottom - height)
            var widest = 0f
            if (!canWrap()) for (i in 0 until l.lineCount) widest = max(widest, l.getLineRight(i))
            val maxX = max(0, (widest + totalPaddingLeft + totalPaddingRight - width).toInt())
            scroller.fling(scrollX, scrollY, -vx.toInt(), -vy.toInt(), 0, maxX, 0, maxY)
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun canWrap() = wrapOn

    private var zoomSp = 14f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            zoomSp = textSize / resources.displayMetrics.scaledDensity
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomSp = (zoomSp * d.scaleFactor).coerceIn(MIN_SP, MAX_SP)
            setTextSizeSp(zoomSp)
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) { onZoom?.invoke(zoomSp) }
    })

    override fun computeScroll() {
        super.computeScroll()
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scroller.forceFinished(true)
        scaler.onTouchEvent(event)
        if (scaler.isInProgress || event.pointerCount > 1) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                // Hand the gesture over to pinch: cancel any selection/drag in progress.
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.onTouchEvent(cancel)
                cancel.recycle()
            }
            return true
        }
        flinger.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    // ------------------------------------------------------------------ editing helpers

    /** Replaces [start, end) with [s]; one undo step. */
    fun replaceRange(start: Int, end: Int, s: CharSequence, cursor: Int = start + s.length) {
        val t = text ?: return
        t.replace(start, end, s)
        setSelection(cursor.coerceIn(0, t.length))
    }

    fun insert(s: String, cursorBack: Int = 0) {
        val a = min(selectionStart, selectionEnd).coerceAtLeast(0)
        val b = max(selectionStart, selectionEnd).coerceAtLeast(0)
        replaceRange(a, b, s, a + s.length - cursorBack)
    }

    /** Wraps the selection in [open]/[close], or inserts the pair with the cursor inside. */
    fun wrap(open: String, close: String) {
        val a = min(selectionStart, selectionEnd).coerceAtLeast(0)
        val b = max(selectionStart, selectionEnd).coerceAtLeast(0)
        val inner = text!!.subSequence(a, b)
        replaceRange(a, b, "$open$inner$close", if (a == b) a + open.length else a + open.length + inner.length + close.length)
    }

    fun indentOf(line: Int): String {
        val t = text ?: return ""
        val s = lineStart(line)
        var e = s
        while (e < t.length && (t[e] == ' ' || t[e] == '\t')) e++
        return t.substring(s, e)
    }

    private fun selectedLines(): IntRange {
        val a = min(selectionStart, selectionEnd).coerceAtLeast(0)
        var b = max(selectionStart, selectionEnd).coerceAtLeast(0)
        if (b > a && text!![b - 1] == '\n') b--
        return lineOf(a)..lineOf(b)
    }

    /** Applies [edit] to each selected line's text as one undo step, keeping the lines selected. */
    private fun editLines(edit: (String) -> String) {
        val t = text ?: return
        val lines = selectedLines()
        val start = lineStart(lines.first)
        val end = if (lines.last + 1 < lineStarts.size) lineStarts[lines.last + 1] - 1 else t.length
        val out = t.substring(start, end).split('\n').joinToString("\n") { edit(it) }
        val hadSel = selectionStart != selectionEnd
        val fromEnd = end - selectionEnd
        replaceRange(start, end, out)
        if (hadSel || lines.first != lines.last) setSelection(start, start + out.length)
        else setSelection((start + out.length - fromEnd).coerceIn(start, start + out.length))
    }

    fun indentLines() {
        if (selectionStart == selectionEnd && lineOf(selectionStart) == lineOf(selectionEnd) && !selectionIsAtIndent()) return insert(INDENT)
        editLines { INDENT + it }
    }

    fun outdentLines() = editLines { line ->
        var n = 0
        while (n < INDENT.length && n < line.length && line[n] == ' ') n++
        if (n == 0 && line.startsWith("\t")) line.substring(1) else line.substring(n)
    }

    private fun selectionIsAtIndent(): Boolean {
        val c = selectionStart
        val s = lineStart(lineOf(c))
        return (s until c).all { text!![it] == ' ' || text!![it] == '\t' }
    }

    fun toggleComment() {
        val t = text ?: return
        val lines = selectedLines()
        val all = lines.all { ln ->
            val s = lineStart(ln)
            var i = s
            while (i < t.length && t[i] == ' ') i++
            i >= t.length || t[i] == '\n' || t[i] == '#'
        }
        editLines { line ->
            val ind = line.takeWhile { it == ' ' || it == '\t' }
            val rest = line.substring(ind.length)
            when {
                rest.isEmpty() -> line
                all -> ind + rest.removePrefix("#").removePrefix(" ")
                else -> "$ind# $rest"
            }
        }
    }

    fun duplicateLine() {
        val t = text ?: return
        val lines = selectedLines()
        val start = lineStart(lines.first)
        val end = if (lines.last + 1 < lineStarts.size) lineStarts[lines.last + 1] - 1 else t.length
        val block = t.substring(start, end)
        replaceRange(end, end, "\n" + block, end + 1 + block.length)
    }

    /** Selects the whole innermost `( … )` around the selection; repeat to grow outwards. */
    fun selectNode() {
        val a = min(selectionStart, selectionEnd)
        val b = max(selectionStart, selectionEnd)
        var best: CodeLexer.Node? = null
        for (n in lexed.nodes) {
            if (n.close < 0) continue
            val s = n.open
            val e = n.close + 1
            if (s <= a && e >= b && !(s == a && e == b) && (best == null || n.depth > best.depth)) best = n
        }
        val n = best ?: return
        setSelection(n.open, n.close + 1)
    }

    fun goToOffset(offset: Int, selectTo: Int = offset) {
        val t = text ?: return
        requestFocus()
        setSelection(offset.coerceIn(0, t.length), selectTo.coerceIn(0, t.length))
        val l = layout ?: return
        val vl = l.getLineForOffset(offset.coerceIn(0, t.length))
        val y = l.getLineTop(vl) - height / 3
        val maxY = max(0, l.height + totalPaddingTop + totalPaddingBottom - height)
        scroller.forceFinished(true)
        scrollTo(scrollX, y.coerceIn(0, maxY))
        bringPointIntoView(offset.coerceIn(0, t.length))
    }

    // ------------------------------------------------------------------ undo / redo + auto-indent

    private class Edit(val start: Int, val before: String, var after: String, var time: Long)

    private val undo = ArrayList<Edit>()
    private val redo = ArrayList<Edit>()
    private var applying = false
    private var pendingBefore = ""
    private var autoAfter: (() -> Unit)? = null
    private var mergeNext = false

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun undo() = step(undo, redo, true)
    fun redo() = step(redo, undo, false)

    private fun step(from: ArrayList<Edit>, to: ArrayList<Edit>, back: Boolean) {
        val e = from.removeLastOrNull() ?: return
        val t = text ?: return
        applying = true
        try {
            if (back) {
                t.replace(e.start, (e.start + e.after.length).coerceAtMost(t.length), e.before)
                setSelection((e.start + e.before.length).coerceAtMost(t.length))
            } else {
                t.replace(e.start, (e.start + e.before.length).coerceAtMost(t.length), e.after)
                setSelection((e.start + e.after.length).coerceAtMost(t.length))
            }
        } finally { applying = false }
        to += e
        onHistory?.invoke()
    }

    private fun record(start: Int, before: String, after: String) {
        if (before == after) return
        val now = SystemClock.uptimeMillis()
        val last = undo.lastOrNull()
        val forced = mergeNext
        mergeNext = false
        if (last != null && (forced || now - last.time < 1200)) {
            val contiguousTyping = before.isEmpty() && last.before.isEmpty() && start == last.start + last.after.length &&
                (forced || (after.length == 1 && after != "\n" && !(after == " " && !last.after.endsWith(" "))))
            val composition = start == last.start && before == last.after
            if (contiguousTyping || composition) {
                last.after = if (composition) after else last.after + after
                last.time = now
                redo.clear()
                return
            }
            val backspace = after.isEmpty() && last.after.isEmpty() && start + before.length == last.start && before.length == 1
            if (backspace) {
                undo[undo.size - 1] = Edit(start, before + last.before, "", now)
                redo.clear()
                return
            }
        }
        undo += Edit(start, before, after, now)
        if (undo.size > MAX_HISTORY) undo.removeAt(0)
        redo.clear()
        onHistory?.invoke()
    }

    private fun watcher() = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (!applying) pendingBefore = s.subSequence(start, start + count).toString()
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (applying) return
            val inserted = s.subSequence(start, start + count).toString()
            record(start, pendingBefore, inserted)
            if (before == 0 && count == 1) {
                when (inserted[0]) {
                    '\n' -> autoAfter = { autoIndent(start) }
                    ')', ']', '}' -> autoAfter = { autoDedent(start) }
                }
            }
        }

        override fun afterTextChanged(s: Editable) {
            val a = autoAfter
            autoAfter = null
            if (a != null && !applying) {
                applying = true
                try { a() } finally { applying = false }
            }
            removeCallbacks(analyzeRunnable)
            postDelayed(analyzeRunnable, 140)
        }
    }

    /** Newline typed at [nl]: copy the indent, add one level after an opening bracket. */
    private fun autoIndent(nl: Int) {
        val t = text ?: return
        var s = nl
        while (s > 0 && t[s - 1] != '\n') s--
        var e = s
        while (e < nl && (t[e] == ' ' || t[e] == '\t')) e++
        val base = t.substring(s, e)
        var p = nl - 1
        while (p >= s && (t[p] == ' ' || t[p] == ',')) p--
        val opens = p >= s && t[p] in "([{"
        var q = nl + 1
        while (q < t.length && t[q] == ' ') q++
        val closesNext = q < t.length && t[q] in ")]}"
        val add = if (opens) base + INDENT else base
        val ins = if (opens && closesNext) add + "\n" + base else add
        // Drop spaces right after the cursor so they don't stack up with the new indent.
        val removed = t.substring(nl + 1, q)
        t.replace(nl + 1, q, ins)
        setSelection(nl + 1 + add.length)
        if (removed.isEmpty()) mergeNext = true
        record(nl + 1, removed, ins)
    }

    /** Closing bracket typed on a blank line: pull it back one level. */
    private fun autoDedent(at: Int) {
        val t = text ?: return
        var s = at
        while (s > 0 && t[s - 1] != '\n') s--
        if ((s until at).any { t[it] != ' ' } || at - s < INDENT.length) return
        // One level back, like most code editors (brackets inside strings make a smarter guess unreliable).
        val target = " ".repeat(at - s - INDENT.length)
        val removed = t.substring(s, at)
        t.replace(s, at, target)
        record(s, removed, target)
    }

    init {
        typeface = Typeface.MONOSPACE
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setHorizontallyScrolling(true)
        background = null
        setBackgroundColor(Color.WHITE)
        setTextColor(0xff202124.toInt())
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        setLineSpacing(0f, 1.1f)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        addTextChangedListener(watcher())
        ready = true
    }

    companion object {
        const val INDENT = "    "
        const val MIN_SP = 8f
        const val MAX_SP = 30f
        private const val MAX_HISTORY = 400
        val SLOT_ARROWS = arrayOf("↖", "↑", "↗", "←", "→", "↙", "↓", "↘")
    }
}

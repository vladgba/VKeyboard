package x.vladgba.keyboard.ui

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser
import x.vladgba.keyboard.keyboard.TouchStats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Touch heatmap: which keys are touched most, where on the layout, and where inside each key
 * (aim). Includes a test field that shows the latest touches live while typing.
 */
class TouchStatsActivity : LocalizedActivity() {
    private enum class Mode { KEYS, HEAT, AIM }

    private lateinit var page: Page
    private lateinit var preview: LayoutPreview
    private lateinit var summary: TextView
    private lateinit var insights: TextView
    private lateinit var layoutRow: Row
    private var layoutName = ""
    private var mode = Mode.KEYS

    /** Latest touches (memory only): row, index, dx, dy, time. */
    private class Live(val row: Int, val index: Int, val dx: Float, val dy: Float, val time: Long)
    private val live = ArrayDeque<Live>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ts_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        Settings.loadVars(this)
        layoutName = TouchStats.layouts(this).firstOrNull() ?: Settings.str(SETTING_DEF_LAYOUT)
        page = Page(this)
        build()
        setContentView(page.view)
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        TouchStats.listener = { layout, row, index, dx, dy ->
            if (layout != layoutName) selectLayout(layout)
            live.addLast(Live(row, index, dx, dy, System.currentTimeMillis()))
            while (live.size > 12) live.removeFirst()
            refresh()
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        TouchStats.listener = null
        TouchStats.flush(this)
    }

    private fun build() {
        page.note(getString(R.string.ts_intro))
        page.switchRow(getString(R.string.ts_record), getString(R.string.ts_record_sub), Settings.bool(SETTING_TOUCH_STATS)) {
            Settings[SETTING_TOUCH_STATS] = if (it) "1" else "0"
            Settings.save(this)
        }
        layoutRow = page.row(getString(R.string.ts_layout), "") { chooseLayout() }
        layoutRow.end.addView(TextView(this).apply { text = "▾"; textSize = 20f; setTextColor(COLOR_SECONDARY_TEXT) })

        val modes = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((m, label) in listOf(Mode.KEYS to R.string.ts_mode_keys, Mode.HEAT to R.string.ts_mode_heat, Mode.AIM to R.string.ts_mode_aim)) {
            modes.addView(RadioButton(this).apply {
                text = getString(label)
                id = m.ordinal + 1
                isChecked = m == mode
            })
        }
        modes.setOnCheckedChangeListener { _, id ->
            mode = Mode.values()[id - 1]
            refresh()
        }
        page.add(modes)

        preview = LayoutPreview(this, rowHeightDp = 44)
        preview.overlay = { c, keys -> drawOverlay(c, keys) }
        page.add(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        summary = page.note("")

        page.header(getString(R.string.ts_try))
        page.add(EditText(this).apply {
            hint = getString(R.string.ts_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        })
        page.note(getString(R.string.ts_try_sub))

        page.header(getString(R.string.ts_insights))
        insights = page.note("")

        page.row(getString(R.string.ts_reset_layout), getString(R.string.ts_reset_layout_sub)) {
            confirm(this, R.string.ts_reset_layout, R.string.ts_reset_confirm, R.string.yes) {
                TouchStats.reset(this, layoutName)
                live.clear()
                refresh()
            }
        }
        page.row(getString(R.string.ts_reset_all), null) {
            confirm(this, R.string.ts_reset_all, R.string.ts_reset_confirm, R.string.yes) {
                TouchStats.reset(this, null)
                live.clear()
                refresh()
            }
        }
    }

    private fun chooseLayout() {
        val withData = TouchStats.layouts(this)
        val all = (withData + PFile.list(this, LAYOUT_EXT).filter { it != SETTINGS_FILENAME }).distinct()
        Ask.choice(this, getString(R.string.ts_layout), all.map {
            val n = TouchStats.get(this, it)?.total ?: 0
            "${BuiltIns.layoutTitle(this, it)}  ·  " + getString(R.string.ts_touches, n)
        }, all.indexOf(layoutName)) { selectLayout(all[it]) }
    }

    private fun selectLayout(name: String) {
        layoutName = name
        live.clear()
        refresh()
    }

    private var shownLayout: String? = null

    private fun refresh() {
        if (shownLayout != layoutName) {
            preview.layout = FlexParser.parse(PFile(this, layoutName).read())
            shownLayout = layoutName
        }
        layoutRow.setSubtitle(BuiltIns.layoutTitle(this, layoutName))
        val stats = TouchStats.get(this, layoutName)
        summary.text = when {
            stats == null || stats.total == 0 -> getString(
                if (Settings.bool(SETTING_TOUCH_STATS)) R.string.ts_no_data else R.string.ts_no_data_off)
            else -> getString(R.string.ts_summary, stats.total) + "\n" + getString(
                when (mode) {
                    Mode.KEYS -> R.string.ts_legend_keys
                    Mode.HEAT -> R.string.ts_legend_heat
                    Mode.AIM -> R.string.ts_legend_aim
                })
        }
        insights.text = insightsText(stats)
        preview.invalidate()
    }

    // ======================= drawing =======================

    /** Blue (few) → green → yellow → red (most). */
    private fun heat(t: Float, alpha: Int): Int {
        val x = t.coerceIn(0f, 1f)
        val r: Float; val g: Float; val b: Float
        when {
            x < 0.33f -> { val k = x / 0.33f; r = 0f; g = 255 * k; b = 255 * (1 - k) }
            x < 0.66f -> { val k = (x - 0.33f) / 0.33f; r = 255 * k; g = 255f; b = 0f }
            else -> { val k = (x - 0.66f) / 0.34f; r = 255f; g = 255 * (1 - k); b = 0f }
        }
        return Color.argb(alpha, r.toInt(), g.toInt(), b.toInt())
    }

    private fun drawOverlay(c: Canvas, keys: List<LayoutPreview.KeyRect>) {
        val stats = TouchStats.get(this, layoutName)
        if (stats != null && stats.total > 0) when (mode) {
            Mode.KEYS -> drawKeyCounts(c, keys, stats)
            Mode.HEAT -> drawGrid(c, stats)
            Mode.AIM -> drawAim(c, keys, stats)
        }
        drawLive(c, keys)
    }

    private fun drawKeyCounts(c: Canvas, keys: List<LayoutPreview.KeyRect>, s: TouchStats.LayoutStats) {
        val maxCount = s.keys.values.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: return
        text.textSize = dp(11).toFloat()
        for (k in keys) {
            val st = s.keys[TouchStats.keyId(k.row, k.index)] ?: continue
            val t = st.count / maxCount.toFloat()
            fill.color = heat(t, (70 + 120 * t).toInt())
            c.drawRoundRect(k.rect, dp(4).toFloat(), dp(4).toFloat(), fill)
            text.color = 0xff000000.toInt()
            c.drawText(st.count.toString(), k.rect.centerX(), k.rect.bottom - dp(3), text)
        }
    }

    private fun drawGrid(c: Canvas, s: TouchStats.LayoutStats) {
        val maxCell = s.grid.maxOrNull()?.coerceAtLeast(1) ?: return
        val cw = c.width / TouchStats.GRID_W.toFloat()
        val ch = preview.height / TouchStats.GRID_H.toFloat()
        for (i in s.grid.indices) {
            val v = s.grid[i]
            if (v == 0) continue
            val t = sqrt(v / maxCell.toFloat())
            fill.color = heat(t, (60 + 150 * t).toInt())
            val cx = (i % TouchStats.GRID_W + 0.5f) * cw
            val cy = (i / TouchStats.GRID_W + 0.5f) * ch
            c.drawCircle(cx, cy, max(cw, ch) * (0.6f + 0.5f * t), fill)
        }
    }

    private fun drawAim(c: Canvas, keys: List<LayoutPreview.KeyRect>, s: TouchStats.LayoutStats) {
        line.strokeWidth = dp(2).toFloat()
        for (k in keys) {
            val st = s.keys[TouchStats.keyId(k.row, k.index)] ?: continue
            if (st.count < 3) continue
            val cx = k.rect.centerX()
            val cy = k.rect.centerY()
            val ax = cx + st.avgDx * k.rect.width() / 2
            val ay = cy + st.avgDy * k.rect.height() / 2
            val off = sqrt(st.avgDx * st.avgDx + st.avgDy * st.avgDy)
            val col = if (off > 0.5f) COLOR_WARN else if (off > 0.25f) 0xffe8710a.toInt() else COLOR_OK
            line.color = col
            c.drawLine(cx, cy, ax, ay, line)
            fill.color = col
            c.drawCircle(ax, ay, dp(4).toFloat(), fill)
            fill.color = 0x66000000
            c.drawCircle(cx, cy, dp(2).toFloat(), fill)
        }
    }

    private fun drawLive(c: Canvas, keys: List<LayoutPreview.KeyRect>) {
        val now = System.currentTimeMillis()
        text.textSize = dp(10).toFloat()
        for ((i, t) in live.withIndex()) {
            val k = keys.firstOrNull { it.row == t.row && it.index == t.index } ?: continue
            val age = ((now - t.time) / 8000f).coerceIn(0f, 1f)
            val x = k.rect.centerX() + t.dx * k.rect.width() / 2
            val y = k.rect.centerY() + t.dy * k.rect.height() / 2
            val newest = i == live.size - 1
            fill.color = Color.argb((230 * (1 - age * 0.7f)).toInt(), if (newest) 255 else 120, 0, if (newest) 0 else 200)
            c.drawCircle(x, y, dp(if (newest) 7 else 5).toFloat(), fill)
            line.color = 0xffffffff.toInt()
            line.strokeWidth = dp(1).toFloat()
            c.drawCircle(x, y, dp(if (newest) 7 else 5).toFloat(), line)
        }
    }

    // ======================= insights =======================

    private fun insightsText(s: TouchStats.LayoutStats?): String {
        if (s == null || s.total == 0) return getString(R.string.ts_insights_none)
        val node = preview.layout
        fun label(id: String): String {
            val (r, i) = id.split(':').map { it.toInt() }
            val row = node.childs.filterIsInstance<FlexNode>().getOrNull(r) ?: return id
            val key = row.childs.filterIsInstance<FlexNode>().getOrNull(i) ?: return id
            return (key.params[KEY_KEY] as? String)?.let { Labels.resolve(this, it) }?.takeIf { it.isNotBlank() }
                ?: if ((key.params[KEY_CODE] as? String)?.trim() == "-62") getString(R.string.kb_space_name) else getString(R.string.ts_key_n, i + 1)
        }
        val sb = StringBuilder()
        val top = s.keys.entries.sortedByDescending { it.value.count }.take(8)
        sb.append(getString(R.string.ts_top)).append(' ')
            .append(top.joinToString(", ") { "${label(it.key)} (${it.value.count * 100 / s.total}%)" }).append("\n\n")

        val off = s.keys.entries.filter { it.value.count >= 8 }
            .map { it to sqrt(it.value.avgDx * it.value.avgDx + it.value.avgDy * it.value.avgDy) }
            .filter { it.second > 0.35f }.sortedByDescending { it.second }.take(5)
        if (off.isEmpty()) sb.append(getString(R.string.ts_aim_good))
        else {
            sb.append(getString(R.string.ts_aim_off)).append('\n')
            for ((e, _) in off) {
                val st = e.value
                val dirs = ArrayList<String>()
                if (abs(st.avgDy) > 0.25f) dirs += getString(if (st.avgDy > 0) R.string.ts_dir_low else R.string.ts_dir_high)
                if (abs(st.avgDx) > 0.25f) dirs += getString(if (st.avgDx > 0) R.string.ts_dir_right else R.string.ts_dir_left)
                sb.append("• ").append(label(e.key)).append(": ").append(dirs.joinToString(", ")).append('\n')
            }
            sb.append(getString(R.string.ts_aim_tip))
        }
        val bottomRow = s.grid.sliceArray((TouchStats.GRID_H - 2) * TouchStats.GRID_W until s.grid.size).sum()
        if (bottomRow * 100 / max(1, s.total) > 40) sb.append("\n\n").append(getString(R.string.ts_bottom_heavy))
        return sb.toString()
    }

}

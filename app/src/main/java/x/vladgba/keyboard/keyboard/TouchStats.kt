package x.vladgba.keyboard.keyboard

import android.content.Context
import x.vladgba.keyboard.core.KEY_CODE
import x.vladgba.keyboard.core.KEY_KEY
import x.vladgba.keyboard.core.KEY_TEXT
import x.vladgba.keyboard.core.PFile
import x.vladgba.keyboard.core.STATS_EXT
import x.vladgba.keyboard.core.STATS_FILENAME
import x.vladgba.keyboard.flex.FlexNode
import kotlin.math.abs

/**
 * Where on the keyboard the user touches, for the touch heatmap screen and hitbox autotune.
 *
 * Privacy: only aggregates are stored (per key: number of touches, average offset from the key
 * center and a coarse grid of counts inside the key). The order of touches is never saved, so
 * typed text can't be reconstructed. Password and incognito fields are not recorded at all
 * (checked by the caller). The live feed for the heatmap screen exists only in memory and works
 * even when recording is off.
 *
 * Keys are identified by their content (label, code, text, popup chars), not by their position,
 * so the stats follow a key when it is moved or resized in the layout editor.
 * Everything is relative to the key, so nothing goes stale when the layout changes.
 */
object TouchStats {
    /** Grid inside each key: cells cover offsets -[SPAN]..[SPAN] (1 = key edge). */
    const val CELLS_X = 6
    const val CELLS_Y = 5
    const val SPAN = 1.25f
    private const val SAVE_EVERY = 25

    class KeyStat(var count: Int = 0, var sumDx: Float = 0f, var sumDy: Float = 0f) {
        val cells = IntArray(CELLS_X * CELLS_Y)
        val avgDx get() = if (count == 0) 0f else sumDx / count
        val avgDy get() = if (count == 0) 0f else sumDy / count

        fun addCell(dx: Float, dy: Float, n: Int = 1) {
            val cx = ((dx + SPAN) / (2 * SPAN) * CELLS_X).toInt().coerceIn(0, CELLS_X - 1)
            val cy = ((dy + SPAN) / (2 * SPAN) * CELLS_Y).toInt().coerceIn(0, CELLS_Y - 1)
            cells[cy * CELLS_X + cx] += n
        }
    }

    class LayoutStats {
        var total = 0
        /** Stable key id (see [keyIds]) -> stats. */
        val keys = HashMap<String, KeyStat>()
        /** Data from the old position-based format ("row:index"), waiting for [migrate]. */
        val legacy = HashMap<String, KeyStat>()
    }

    /** Live touch for the heatmap screen: layout, key id, offset from key center (-1..1). */
    var listener: ((String, String, Float, Float) -> Unit)? = null

    /** Changes whenever stored stats change in a way hitbox autotune should pick up. */
    var version = 0
        private set

    private val data = LinkedHashMap<String, LayoutStats>()
    private var loaded = false
    private var unsaved = 0

    // ======================= key identity =======================

    private fun signature(k: FlexNode): String {
        val sb = StringBuilder()
        for (p in arrayOf(KEY_KEY, KEY_CODE, KEY_TEXT)) sb.append(k.params[p]?.toString()?.trim() ?: "").append('\u0001')
        for (c in k.childs) sb.append(c.toString()).append('\u0002')
        return sb.toString()
    }

    private fun hash(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (ch in s) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return "%08x".format(h)
    }

    /**
     * Stable ids for the keys of a layout, same shape as [rows]. Identical keys get a running
     * number in reading order ("abcd1234", "abcd1234.1", ...).
     */
    fun keyIds(rows: List<List<FlexNode>>): List<List<String>> {
        val seen = HashMap<String, Int>()
        return rows.map { row ->
            row.map { k ->
                val h = hash(signature(k))
                val n = seen[h] ?: 0
                seen[h] = n + 1
                if (n == 0) h else "$h.$n"
            }
        }
    }

    /**
     * Moves stats saved by older versions (by row/index) onto stable ids using the layout as it
     * is now. Cheap no-op when there is nothing to migrate.
     */
    fun migrate(ctx: Context, layout: String, ids: List<List<String>>) {
        load(ctx)
        val s = data[layout] ?: return
        if (s.legacy.isEmpty()) return
        for ((pos, old) in s.legacy) {
            val p = pos.split(':')
            val id = ids.getOrNull(p[0].toIntOrNull() ?: -1)?.getOrNull(p.getOrNull(1)?.toIntOrNull() ?: -1) ?: continue
            val k = s.keys.getOrPut(id) { KeyStat() }
            k.count += old.count
            k.sumDx += old.sumDx
            k.sumDy += old.sumDy
            // Old data has no per-key grid: put the touches at their average spot.
            k.addCell(old.avgDx, old.avgDy, old.count)
        }
        s.legacy.clear()
        unsaved++
        save(ctx)
    }

    // ======================= recording =======================

    /** [dx], [dy]: offset from the key center, -1 = left/top edge, 1 = right/bottom edge. */
    fun record(ctx: Context, store: Boolean, layout: String, id: String, dx: Float, dy: Float) {
        if (!store) {
            // Heatmap screen open but recording off: show the touch, keep nothing.
            listener?.invoke(layout, id, dx, dy)
            return
        }
        load(ctx)
        val s = data.getOrPut(layout) { LayoutStats() }
        s.total++
        val k = s.keys.getOrPut(id) { KeyStat() }
        k.count++
        k.sumDx += dx.coerceIn(-1.5f, 1.5f)
        k.sumDy += dy.coerceIn(-1.5f, 1.5f)
        k.addCell(dx, dy)
        listener?.invoke(layout, id, dx, dy)
        if (++unsaved >= SAVE_EVERY) save(ctx)
    }

    fun layouts(ctx: Context): List<String> {
        load(ctx)
        return data.filterValues { it.total > 0 }.keys.toList()
    }

    fun get(ctx: Context, layout: String): LayoutStats? {
        load(ctx)
        return data[layout]
    }

    fun reset(ctx: Context, layout: String?) {
        load(ctx)
        if (layout == null) data.clear() else data.remove(layout)
        unsaved = 1
        save(ctx)
    }

    fun flush(ctx: Context) {
        if (unsaved > 0) save(ctx)
    }

    // ======================= hitbox autotune =======================

    /** Touches a key needs before its hitbox moves, and touches for the full shift. */
    private const val TUNE_MIN = 15
    private const val TUNE_FULL = 80
    /** Largest shift, as a fraction of half the key size (0.4 = 20% of the key). */
    private const val TUNE_MAX = 0.4f

    /**
     * How far to move each key's touch area (fractions of half the key width/height), toward
     * where the user really touches it. Keys with too few touches are not moved.
     */
    fun hitboxShifts(ctx: Context, layout: String): Map<String, FloatArray> {
        val s = get(ctx, layout) ?: return emptyMap()
        val out = HashMap<String, FloatArray>()
        for ((id, k) in s.keys) {
            if (k.count < TUNE_MIN) continue
            val conf = ((k.count - TUNE_MIN) / (TUNE_FULL - TUNE_MIN).toFloat()).coerceIn(0.3f, 1f)
            val sx = if (abs(k.avgDx) < 0.08f) 0f else k.avgDx.coerceIn(-TUNE_MAX, TUNE_MAX) * conf
            val sy = if (abs(k.avgDy) < 0.08f) 0f else k.avgDy.coerceIn(-TUNE_MAX, TUNE_MAX) * conf
            if (sx != 0f || sy != 0f) out[id] = floatArrayOf(sx, sy)
        }
        return out
    }

    // ======================= file =======================

    // File format (one layout after another):
    //   L <total> <layout name>
    //   S <count> <sumDx> <sumDy> <cell>:<n>,<cell>:<n>|- <key id>
    // Older files: "K <row:index> <count> <sumDx> <sumDy>" and a layout-wide "G" grid
    // (positions, which went stale when keys moved; the grid is dropped).
    private fun save(ctx: Context) {
        val sb = StringBuilder()
        for ((name, s) in data) {
            if (s.total == 0) continue
            sb.append("L ").append(s.total).append(' ').append(name).append('\n')
            for ((id, k) in s.keys) {
                sb.append("S ").append(k.count).append(' ').append(k.sumDx).append(' ').append(k.sumDy).append(' ')
                var any = false
                for (i in k.cells.indices) if (k.cells[i] > 0) {
                    if (any) sb.append(',')
                    sb.append(i).append(':').append(k.cells[i])
                    any = true
                }
                if (!any) sb.append('-')
                sb.append(' ').append(id).append('\n')
            }
            for ((pos, k) in s.legacy) sb.append("K ").append(pos).append(' ').append(k.count).append(' ')
                .append(k.sumDx).append(' ').append(k.sumDy).append('\n')
        }
        PFile(ctx, STATS_FILENAME, STATS_EXT).write(sb.toString())
        unsaved = 0
        version++
    }

    fun reload(ctx: Context) {
        data.clear()
        loaded = false
        load(ctx)
        version++
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        var cur: LayoutStats? = null
        for (line in PFile(ctx, STATS_FILENAME, STATS_EXT).read().lineSequence()) {
            try {
                when {
                    line.startsWith("L ") -> {
                        val rest = line.substring(2)
                        val sp = rest.indexOf(' ')
                        if (sp < 0) continue
                        cur = LayoutStats().also {
                            it.total = rest.substring(0, sp).toInt()
                            data[rest.substring(sp + 1)] = it
                        }
                    }
                    line.startsWith("S ") -> {
                        val s = cur ?: continue
                        val p = line.split(' ')
                        if (p.size < 6) continue
                        val k = KeyStat(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
                        if (p[4] != "-") for (part in p[4].split(',')) {
                            val (i, v) = part.split(':')
                            val idx = i.toInt()
                            if (idx in k.cells.indices) k.cells[idx] = v.toInt()
                        }
                        s.keys[p[5]] = k
                    }
                    line.startsWith("K ") -> {
                        val s = cur ?: continue
                        val p = line.split(' ')
                        if (p.size >= 5) s.legacy[p[1]] = KeyStat(p[2].toInt(), p[3].toFloat(), p[4].toFloat())
                    }
                }
            } catch (_: Exception) {
                // A damaged line only loses that line.
            }
        }
    }
}

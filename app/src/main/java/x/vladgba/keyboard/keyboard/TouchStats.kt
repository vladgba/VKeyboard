package x.vladgba.keyboard.keyboard

import android.content.Context
import x.vladgba.keyboard.core.PFile
import x.vladgba.keyboard.core.STATS_EXT
import x.vladgba.keyboard.core.STATS_FILENAME

/**
 * Where on the keyboard the user touches, for the touch heatmap screen.
 *
 * Privacy: only aggregates are stored (per key: number of touches and average offset from the
 * key center; per layout: a coarse grid of counts). The order of touches is never saved, so
 * typed text can't be reconstructed. Password and incognito fields are not recorded at all
 * (checked by the caller). The live feed for the heatmap screen exists only in memory and works
 * even when recording is off.
 */
object TouchStats {
    const val GRID_W = 32
    const val GRID_H = 16
    private const val SAVE_EVERY = 25

    class KeyStat(var count: Int = 0, var sumDx: Float = 0f, var sumDy: Float = 0f) {
        val avgDx get() = if (count == 0) 0f else sumDx / count
        val avgDy get() = if (count == 0) 0f else sumDy / count
    }

    class LayoutStats {
        var total = 0
        val grid = IntArray(GRID_W * GRID_H)
        /** Key id "row:index" -> stats. */
        val keys = HashMap<String, KeyStat>()
    }

    /** Live touch for the heatmap screen: layout, row, key index, offset from key center (-1..1). */
    var listener: ((String, Int, Int, Float, Float) -> Unit)? = null

    private val data = LinkedHashMap<String, LayoutStats>()
    private var loaded = false
    private var unsaved = 0

    fun keyId(row: Int, index: Int) = "$row:$index"

    /**
     * [nx], [ny]: position on the whole layout (0..1). [dx], [dy]: offset from the key center,
     * -1 = left/top edge, 1 = right/bottom edge.
     */
    fun record(ctx: Context, store: Boolean, layout: String, row: Int, index: Int, nx: Float, ny: Float, dx: Float, dy: Float) {
        if (!store) {
            // Heatmap screen open but recording off: show the touch, keep nothing.
            listener?.invoke(layout, row, index, dx, dy)
            return
        }
        load(ctx)
        val s = data.getOrPut(layout) { LayoutStats() }
        s.total++
        val gx = (nx * GRID_W).toInt().coerceIn(0, GRID_W - 1)
        val gy = (ny * GRID_H).toInt().coerceIn(0, GRID_H - 1)
        s.grid[gy * GRID_W + gx]++
        val k = s.keys.getOrPut(keyId(row, index)) { KeyStat() }
        k.count++
        k.sumDx += dx.coerceIn(-1.5f, 1.5f)
        k.sumDy += dy.coerceIn(-1.5f, 1.5f)
        listener?.invoke(layout, row, index, dx, dy)
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

    // File format (one layout after another):
    //   L <total> <layout name>
    //   G <cell>:<count> ...        (non-empty grid cells)
    //   K <row:index> <count> <sumDx> <sumDy>
    private fun save(ctx: Context) {
        val sb = StringBuilder()
        for ((name, s) in data) {
            if (s.total == 0) continue
            sb.append("L ").append(s.total).append(' ').append(name).append('\n')
            sb.append('G')
            for (i in s.grid.indices) if (s.grid[i] > 0) sb.append(' ').append(i).append(':').append(s.grid[i])
            sb.append('\n')
            for ((id, k) in s.keys) sb.append("K ").append(id).append(' ').append(k.count).append(' ')
                .append(k.sumDx).append(' ').append(k.sumDy).append('\n')
        }
        PFile(ctx, STATS_FILENAME, STATS_EXT).write(sb.toString())
        unsaved = 0
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
                    line.startsWith("G") -> {
                        val s = cur ?: continue
                        for (part in line.substring(1).trim().split(' ')) {
                            if (part.isEmpty()) continue
                            val (i, v) = part.split(':')
                            val idx = i.toInt()
                            if (idx in s.grid.indices) s.grid[idx] = v.toInt()
                        }
                    }
                    line.startsWith("K ") -> {
                        val s = cur ?: continue
                        val p = line.split(' ')
                        if (p.size >= 5) s.keys[p[1]] = KeyStat(p[2].toInt(), p[3].toFloat(), p[4].toFloat())
                    }
                }
            } catch (_: Exception) {
                // A damaged line only loses that line.
            }
        }
    }
}

package x.vladgba.keyboard.core

import android.os.SystemClock
import x.vladgba.keyboard.flex.FlexNode
import x.vladgba.keyboard.flex.FlexParser

/**
 * Clipboard history.
 *  - Recent clips live in memory only: they vanish when the keyboard process restarts, and are
 *    forgotten after `clipExpiry` minutes (0 = never), at most [MAX_RECENT] of them.
 *  - Pinned clips are kept in `clipboard.pins` (part of backups) until unpinned or deleted.
 */
object ClipHistory {
    class Clip(val text: String, var time: Long, var pinned: Boolean)

    private const val MAX_RECENT = 25

    private val recent = ArrayList<Clip>() // newest first
    private var pins: MutableList<Clip>? = null

    /** The system clipboard text last taken into the history, so it isn't re-added on every show. */
    private var lastSeen: String? = null

    private fun now() = SystemClock.elapsedRealtime()

    /** Forget the loaded pins (storage changed: unlock after reboot, restore from backup). */
    fun reload() {
        pins = null
    }

    /**
     * New text on the system clipboard. [fresh] = it was just copied (listener); otherwise it was
     * found when the keyboard opened and is only taken if it's different from the last one seen.
     */
    fun add(ctx: android.content.Context, text: String, fresh: Boolean) {
        if (text.isBlank()) return
        if (!fresh && text == lastSeen) return
        lastSeen = text
        purge()
        if (pinsOf(ctx).any { it.text == text }) return
        recent.removeAll { it.text == text }
        recent.add(0, Clip(text, now(), false))
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
    }

    /** Pinned clips first, then recent ones, newest first. */
    fun items(ctx: android.content.Context): List<Clip> {
        purge()
        return pinsOf(ctx) + recent
    }

    fun setPinned(ctx: android.content.Context, clip: Clip, pin: Boolean) {
        val p = pinsOf(ctx)
        if (pin) {
            recent.remove(clip)
            if (p.none { it.text == clip.text }) p.add(0, clip)
            clip.pinned = true
        } else {
            p.remove(clip)
            clip.pinned = false
            clip.time = now()
            recent.add(0, clip)
        }
        save(ctx)
    }

    fun delete(ctx: android.content.Context, clip: Clip) {
        if (clip.pinned) {
            pinsOf(ctx).remove(clip)
            save(ctx)
        } else {
            recent.remove(clip)
        }
    }

    /** Removes every unpinned clip. */
    fun clearRecent() {
        recent.clear()
    }

    private fun purge() {
        val minutes = Settings.num(SETTING_CLIP_EXPIRY, 15)
        if (minutes <= 0) return
        val cutoff = now() - minutes * 60_000L
        recent.removeAll { it.time < cutoff }
    }

    private fun pinsOf(ctx: android.content.Context): MutableList<Clip> = pins ?: load(ctx).also { pins = it }

    private fun load(ctx: android.content.Context): MutableList<Clip> =
        FlexParser.parse(PFile(ctx, CLIP_PINS_FILENAME, CLIP_PINS_EXT).read()).childs
            .filterIsInstance<String>()
            .map { Clip(it, 0L, true) }
            .toMutableList()

    private fun save(ctx: android.content.Context) {
        val node = FlexNode()
        for (c in pinsOf(ctx)) node.childs.add(c.text)
        PFile(ctx, CLIP_PINS_FILENAME, CLIP_PINS_EXT).write(node.toString())
    }
}

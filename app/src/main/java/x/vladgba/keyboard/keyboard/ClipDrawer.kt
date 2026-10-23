package x.vladgba.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*

/**
 * Clipboard history shown in place of the keys (same size): pinned clips first, then recent ones.
 * Tap a clip to paste it, ★ pins / unpins it, ✕ deletes it, "Clear" removes all unpinned clips.
 * Colors come from the current keyboard theme.
 */
@SuppressLint("ViewConstructor")
class ClipDrawer(ctx: Context, private val kb: Keyboard, private val onClose: () -> Unit) : LinearLayout(ctx) {
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val node get() = kb.currentLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(name: String) = kb.color(name, node)

    init {
        orientation = VERTICAL
        setBackgroundColor(color(COLOR_KEYBOARD_BACKGROUND))
        setPadding(dp(6), dp(4), dp(6), dp(4))

        val header = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("←", R.string.clip_back) { onClose() })
        header.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.clip_title)
            setTextColor(color(COLOR_TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(8), 0, dp(8), 0)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(button(ctx.getString(R.string.clip_clear), R.string.clip_clear) {
            ClipHistory.clearRecent()
            refresh()
        })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))

        addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            addView(list)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        val items = ClipHistory.items(kb.ctx)
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(R.string.clip_empty)
                setTextColor(color(COLOR_KEY_SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            })
            return
        }
        for (clip in items) list.addView(row(clip))
    }

    private fun row(clip: ClipHistory.Clip): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color(COLOR_KEY_BACKGROUND))
            }
        }
        row.addView(TextView(context).apply {
            text = clip.text.trim().replace(Regex("\\s+"), " ")
            setTextColor(color(COLOR_TEXT_PRIMARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(10), dp(8), dp(10))
            setOnClickListener {
                kb.inputConnection?.commitText(clip.text, 1)
                onClose()
            }
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button(if (clip.pinned) "★" else "☆", if (clip.pinned) R.string.clip_unpin else R.string.clip_pin) {
            ClipHistory.setPinned(kb.ctx, clip, !clip.pinned)
            refresh()
        })
        row.addView(button("✕", R.string.clip_delete) {
            ClipHistory.delete(kb.ctx, clip)
            refresh()
        })
        return row.also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }
    }

    private fun button(label: String, description: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = context.getString(description)
        setTextColor(color(COLOR_TEXT_PRIMARY))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        minWidth = dp(44)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }
}

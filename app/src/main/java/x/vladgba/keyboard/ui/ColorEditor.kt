package x.vladgba.keyboard.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.Theme
import x.vladgba.keyboard.flex.FlexNode

/**
 * Fills the `scroll_colors` list of a `color_settings` dialog with the theme colors of [node]
 * (a key or a row in the layout editor). Shows the value the key really uses; colors set on this
 * key/row are marked, others are inherited from the row/layout/theme. Tap to change (the layout
 * behind updates live through [onChange]), long-press to go back to the inherited color.
 */
internal fun fillColorList(ctx: Context, dialog: AlertDialog, node: FlexNode, onChange: () -> Unit = {}) {
    val list = dialog.findViewById<LinearLayout>(R.id.scroll_colors) ?: return
    list.removeAllViews()
    list.addView(TextView(ctx).apply {
        text = ctx.getString(R.string.ce_intro)
        textSize = 12f
        setTextColor(COLOR_SECONDARY_TEXT)
    })
    for (group in ThemeColors.GROUPS) {
        list.addView(TextView(ctx).apply {
            text = ctx.getString(group.title)
            setTextColor(COLOR_ACCENT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, ctx.dp(12), 0, ctx.dp(2))
        })
        for (info in group.items) {
            val own = node.params.containsKey(info.key)
            val color = Theme.parseColor(node.str(info.key), 0)
            val row = Row(ctx)
            row.title.text = ctx.getString(info.title)
            row.setSubtitle(if (own) ctx.getString(R.string.ce_own, ThemeColors.hex(color)) else ctx.getString(R.string.ce_inherited))
            row.end.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = ctx.dp(8).toFloat()
                    setStroke(ctx.dp(1), 0x33000000)
                }
            }, android.widget.FrameLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
            row.selectableBackground()
            row.setOnClickListener {
                val had = node.params[info.key]
                ColorPicker(ctx, color, true, object : ColorPicker.ColorPickerListener {
                    override fun onOk(dialog: ColorPicker, color: Int) {
                        node[info.key] = Theme.toHex(color)
                        onChange()
                        refresh(list, ctx, node, onChange)
                    }

                    override fun onCancel(dialog: ColorPicker) {
                        if (had == null) node.params.remove(info.key) else node[info.key] = had
                        onChange()
                    }
                }, onChange = { c ->
                    node[info.key] = Theme.toHex(c)
                    onChange()
                }, title = ctx.getString(info.title), atBottom = true).show()
            }
            row.setOnLongClickListener {
                if (node.params.remove(info.key) != null) {
                    onChange()
                    refresh(list, ctx, node, onChange)
                }
                true
            }
            list.addView(row)
        }
    }
    list.tag = dialog
}

private fun refresh(list: LinearLayout, ctx: Context, node: FlexNode, onChange: () -> Unit) {
    val d = list.tag as? AlertDialog ?: return
    fillColorList(ctx, d, node, onChange)
}

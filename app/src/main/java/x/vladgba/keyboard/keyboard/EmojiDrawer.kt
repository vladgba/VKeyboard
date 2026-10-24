package x.vladgba.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.*
import x.vladgba.keyboard.flex.FlexParser
import kotlin.math.max

/** Recycled emoji cells, without a second dictionary, history store or library dependency. */
@SuppressLint("ViewConstructor")
class EmojiDrawer(ctx: Context, private val kb: Keyboard, initialPage: String,
    private val onPage: (String) -> Unit, onClose: () -> Unit) : LinearLayout(ctx) {
    private val titles = intArrayOf(R.string.emoji_faces, R.string.emoji_people, R.string.emoji_nature,
        R.string.emoji_food, R.string.emoji_travel, R.string.emoji_activities, R.string.emoji_objects,
        R.string.emoji_symbols, R.string.emoji_flags)
    private val symbols = listOf("😀", "👋", "🐶", "🍔", "🚗", "⚽", "💡", "❤️", "🏁")
    private val tabs = ArrayList<TextView>()
    private val strip = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(strip) }
    private val grid = GridView(ctx)
    private val empty = TextView(ctx)
    private var items = emptyList<String>()
    var category: String = EMOJI_FILENAME
        private set
    private val cellSize get() = max(dp(52), (28 * resources.displayMetrics.scaledDensity).toInt() + dp(12))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(name: String) = kb.color(name, kb.currentLayout)
    private fun background(selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(6).toFloat()
        val fill = color(if (selected) COLOR_KEY_PRESSED_BACKGROUND else COLOR_KEY_BACKGROUND)
        setColor((fill and 0x00ffffff) or (VisualControls.keyAlpha(Color.alpha(fill)) shl 24))
    }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val view = recycled as? TextView ?: TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 28f
                setTextColor(color(COLOR_TEXT_PRIMARY))
                background = background()
                layoutParams = android.widget.AbsListView.LayoutParams(-1, cellSize)
            }
            view.text = items[position]
            view.contentDescription = context.getString(R.string.emoji_insert, items[position])
            return view
        }
    }

    init {
        tag = "emoji-drawer"
        orientation = VERTICAL
        setBackgroundColor(color(COLOR_KEYBOARD_BACKGROUND))
        setPadding(dp(4), dp(4), dp(4), dp(4))
        val header = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(button("←", ctx.getString(R.string.emoji_back), onClose).apply { tag = "emoji-close" }, LayoutParams(dp(48), -1))
        for ((i, file) in EmojiCatalog.files.withIndex()) {
            val title = ctx.getString(titles[i])
            val tab = button("${symbols[i]} $title", title) { selectCategory(file) }.apply {
                tag = "emoji-tab:$file"
                minWidth = dp(88)
                textSize = 14f
                maxLines = 1
            }
            tabs.add(tab)
            strip.addView(tab, LayoutParams(-2, -1))
        }
        header.addView(scroll, LayoutParams(0, -1, 1f))
        header.addView(button("⌫", ctx.getString(R.string.emoji_backspace)) { kb.onKey(-android.view.KeyEvent.KEYCODE_DEL) }, LayoutParams(dp(48), -1))
        addView(header, LayoutParams(-1, max(dp(48), (14 * resources.displayMetrics.scaledDensity).toInt() + dp(16))))
        grid.apply {
            tag = "emoji-grid"
            columnWidth = cellSize
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(4)
            horizontalSpacing = dp(4)
            adapter = this@EmojiDrawer.adapter
            setOnItemClickListener { _, _, position, _ -> items.getOrNull(position)?.let {
                kb.compose.cancel()
                kb.onText(it)
            } }
        }
        empty.apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(color(COLOR_TEXT_PRIMARY))
        }
        addView(FrameLayout(ctx).apply {
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
        }, LayoutParams(-1, 0, 1f))
        grid.emptyView = empty
        selectCategory(initialPage)
    }

    fun selectCategory(page: String) {
        category = page.takeIf { it in EmojiCatalog.files } ?: EMOJI_FILENAME
        val file = PFile(kb.ctx, category)
        val tooLarge = file.exists() && file.length() > 512 * 1024
        val text = if (tooLarge) "" else if (file.exists()) file.read() else PFile.asset(kb.ctx, category).orEmpty()
        val parser = FlexParser(text)
        val node = parser.parse()
        items = if (parser.warnings.isEmpty()) EmojiCatalog.entries(node) else emptyList()
        empty.text = context.getString(if (tooLarge) R.string.emoji_too_large else R.string.emoji_empty)
        adapter.notifyDataSetChanged()
        grid.setSelection(0)
        for ((i, tab) in tabs.withIndex()) {
            tab.isSelected = EmojiCatalog.files[i] == category
            tab.background = background(tab.isSelected)
            if (tab.isSelected) scroll.post { scroll.smoothScrollTo(tab.left, 0) }
        }
        onPage(category)
    }

    private fun button(label: String, description: String, action: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = description
        gravity = Gravity.CENTER
        textSize = 22f
        setTextColor(color(COLOR_TEXT_PRIMARY))
        setPadding(dp(8), 0, dp(8), 0)
        isFocusable = true
        setOnClickListener { action() }
    }
}

package x.vladgba.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.MACROS_EXT
import x.vladgba.keyboard.core.MACROS_FILENAME
import x.vladgba.keyboard.keyboard.Macros
import x.vladgba.keyboard.keyboard.Macros.Step
import x.vladgba.keyboard.keyboard.Macros.Type

/** List of macros. A macro is a sequence of shortcuts, text and pauses run by one key tap. */
class MacrosActivity : LocalizedActivity() {
    private lateinit var page: Page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ui_macros)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        page = Page(this)
        setContentView(page.view)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_HELP, 0, R.string.ui_help).setIcon(android.R.drawable.ic_menu_help)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, MENU_RAW, 1, R.string.ui_edit_as_text)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_HELP -> Ask.info(this, getString(R.string.ui_macros), getString(R.string.ui_macros_help))
            MENU_RAW -> startActivity(Intent(this, RawEditor::class.java).putExtra("name", MACROS_FILENAME).putExtra("ext", MACROS_EXT))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        page.clear()
        page.note(getString(R.string.ui_macros_intro))
        page.button(getString(R.string.ui_macro_new)) { newMacro() }

        val names = Macros.names(this)
        page.header(getString(R.string.ui_macros_yours, names.size))
        if (names.isEmpty()) page.note(getString(R.string.ui_no_macros_yet))
        for (name in names) {
            val steps = Macros.steps(this, name)
            page.row(name, Macros.summary(steps).ifEmpty { getString(R.string.ui_macro_empty) }) { editMacro(name) }
        }

        page.header(getString(R.string.ui_macro_examples))
        page.note(getString(R.string.ui_macro_examples_sub))
        for ((name, steps) in EXAMPLES) {
            page.row(name, Macros.summary(steps)) {
                if (Macros.exists(this, name)) {
                    Toast.makeText(this, getString(R.string.ui_macro_exists, name), Toast.LENGTH_SHORT).show()
                } else {
                    Macros.save(this, name, steps)
                    render()
                }
            }
        }
    }

    private fun newMacro() {
        Ask.text(this, getString(R.string.ui_macro_new), "", getString(R.string.ui_macro_name_hint)) { raw ->
            val name = raw.trim()
            when {
                name.isEmpty() || name.contains('"') -> Toast.makeText(this, R.string.name_empty, Toast.LENGTH_SHORT).show()
                Macros.exists(this, name) -> Toast.makeText(this, getString(R.string.ui_macro_exists, name), Toast.LENGTH_SHORT).show()
                else -> {
                    Macros.save(this, name, emptyList())
                    editMacro(name)
                }
            }
        }
    }

    // ======================= macro editor =======================

    private fun editMacro(initialName: String) {
        var name = initialName
        val steps = Macros.steps(this, name).toMutableList()
        val editor = Page(this)
        lateinit var dialog: AlertDialog

        fun persist() {
            Macros.save(this, name, steps)
        }

        fun render() {
            editor.clear()
            editor.note(getString(R.string.ui_macro_usage, name))
            if (steps.isEmpty()) editor.note(getString(R.string.ui_macro_empty_hint))
            for ((i, step) in steps.withIndex()) {
                val r = editor.row("${i + 1}.  ${Macros.describe(step)}", stepKind(step)) {
                    editStep(step) { steps[i] = it; persist(); render() }
                }
                val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                fun tool(icon: Int, desc: Int, action: () -> Unit) = tools.addView(ImageButton(this).apply {
                    setImageResource(icon)
                    contentDescription = getString(desc)
                    background = null
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setOnClickListener { action(); persist(); render() }
                })
                if (i > 0) tool(android.R.drawable.arrow_up_float, R.string.ui_move_up) { steps.add(i - 1, steps.removeAt(i)) }
                if (i < steps.size - 1) tool(android.R.drawable.arrow_down_float, R.string.ui_move_down) { steps.add(i + 1, steps.removeAt(i)) }
                tool(android.R.drawable.ic_menu_delete, R.string.delete) { steps.removeAt(i) }
                r.end.addView(tools)
            }
            editor.header(getString(R.string.ui_macro_add_step))
            editor.row(getString(R.string.ui_step_shortcut), getString(R.string.ui_step_shortcut_sub)) {
                ComboBuilder.show(this, "") { steps.add(Step(Type.KEY, it.spec())); persist(); render() }
            }
            editor.row(getString(R.string.ui_step_text), getString(R.string.ui_step_text_sub)) {
                Ask.text(this, getString(R.string.ui_step_text), "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
                    if (it.isNotEmpty()) { steps.add(Step(Type.TEXT, it)); persist(); render() }
                }
            }
            editor.row(getString(R.string.ui_step_wait), getString(R.string.ui_step_wait_sub)) {
                askWait("200") { steps.add(Step(Type.WAIT, it)); persist(); render() }
            }
        }

        render()
        dialog = AlertDialog.Builder(this)
            .setTitle(name)
            .setView(editor.view)
            .setPositiveButton(R.string.ui_done, null)
            .setNeutralButton(R.string.ui_rename) { _, _ ->
                Ask.text(this, getString(R.string.ui_rename), name) { raw ->
                    val n = raw.trim()
                    if (n.isNotEmpty() && n != name && !n.contains('"') && !Macros.exists(this, n)) {
                        Macros.rename(this, name, n)
                        name = n
                    }
                    this.render()
                }
            }
            .setNegativeButton(R.string.delete) { _, _ ->
                confirm(this, R.string.delete, R.string.ui_macro_delete_confirm, R.string.delete) {
                    Macros.delete(this, name)
                    this.render()
                }
            }
            .create()
        dialog.setOnDismissListener { this.render() }
        dialog.show()
    }

    private fun stepKind(step: Step) = getString(
        when (step.type) {
            Type.KEY -> R.string.ui_step_shortcut
            Type.TEXT -> R.string.ui_step_text
            Type.WAIT -> R.string.ui_step_wait
        }
    )

    private fun editStep(step: Step, onDone: (Step) -> Unit) = when (step.type) {
        Type.KEY -> ComboBuilder.show(this, step.value) { onDone(Step(Type.KEY, it.spec())) }
        Type.TEXT -> Ask.text(this, getString(R.string.ui_step_text), step.value, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            if (it.isNotEmpty()) onDone(Step(Type.TEXT, it))
        }
        Type.WAIT -> askWait(step.value) { onDone(Step(Type.WAIT, it)) }
    }

    private fun askWait(value: String, onOk: (String) -> Unit) {
        Ask.text(this, getString(R.string.ui_step_wait), value, getString(R.string.ui_step_wait_sub), InputType.TYPE_CLASS_NUMBER) {
            val ms = it.trim().toIntOrNull()?.coerceIn(0, Macros.MAX_WAIT_MS) ?: return@text
            onOk(ms.toString())
        }
    }

    companion object {
        private const val MENU_HELP = 1
        private const val MENU_RAW = 2

        private val EXAMPLES = listOf(
            "selectAllCopy" to listOf(Step(Type.KEY, "ctrl+a"), Step(Type.KEY, "ctrl+c")),
            "duplicateLine" to listOf(Step(Type.KEY, "home"), Step(Type.KEY, "shift+end"), Step(Type.KEY, "ctrl+c"),
                Step(Type.KEY, "end"), Step(Type.KEY, "enter"), Step(Type.KEY, "ctrl+v")),
            "deleteWord" to listOf(Step(Type.KEY, "ctrl+shift+dpad_left"), Step(Type.KEY, "del")),
            "appSwitcher" to listOf(Step(Type.KEY, "alt+tab")),
        )
    }
}

package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.PFile
import x.vladgba.keyboard.core.SETTINGS_FILENAME

import x.vladgba.keyboard.keyboard.Keyboard

/**
 * Visual layout editor. Tap a key to edit it, swipe a key to move it to a neighbour position.
 */
class LayoutEditor : LocalizedActivity() {
    private lateinit var kb: Keyboard
    private lateinit var name: String
    private var dirty = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name") ?: return finish()
        if (name == SETTINGS_FILENAME) {
            startActivity(Intent(this, RawEditor::class.java).putExtra("name", name))
            return finish()
        }
        setContentView(R.layout.layout_edit)
        title = name

        kb = Keyboard(this, null)
        kb.editInterface = EditInterface(kb) { dirty = true }
        if (!kb.openForEditing(name)) {
            Toast.makeText(this, R.string.layout_load_failed, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, RawEditor::class.java).putExtra("name", name))
            return finish()
        }
        findViewById<LinearLayout>(R.id.keyb_layout).addView(kb.view)

        findViewById<Button>(R.id.add_row).setOnClickListener {
            kb.currentLayout.addRow(0)
            changed()
        }
        findViewById<Button>(R.id.add_key).setOnClickListener {
            val layout = kb.currentLayout
            val row = layout.rows.firstOrNull() ?: layout.addRow(0)
            layout.addKey(row, 0)
            changed()
        }
        findViewById<Button>(R.id.layout_save).setOnClickListener {
            val ok = PFile(this, name).write(kb.currentLayout.toString())
            if (ok) dirty = false
            Toast.makeText(this, if (ok) R.string.file_saved else R.string.file_save_fail, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.layout_raw).setOnClickListener {
            startActivity(Intent(this, RawEditor::class.java).putExtra("name", name))
        }
    }

    private fun changed() {
        dirty = true
        kb.view.requestLayout()
        kb.invalidate()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!dirty) return finish()
        confirm(this, R.string.confirm_title, R.string.confirm_unsaved) { finish() }
    }

    override fun onResume() {
        super.onResume()
        // Pick up changes made in the raw editor (only when nothing unsaved would be lost).
        if (this::kb.isInitialized && !dirty) kb.openForEditing(name)
    }
}

package x.vladgba.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.LAYOUT_EXT
import x.vladgba.keyboard.core.PFile
import x.vladgba.keyboard.core.THEME_EXT
import x.vladgba.keyboard.core.Theme

/** Opened from file managers: imports a `.txt` layout or `.ini` theme. */
class FileImport : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri == null) return finish()
        try {
            val content = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return finish()
            val fileName = displayName(uri)
            val ext = if (fileName.endsWith(".$THEME_EXT")) THEME_EXT else LAYOUT_EXT
            ask(fileName.substringBeforeLast('.'), ext, content)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.file_import_fail, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
    }

    private fun ask(name: String, ext: String, content: String) {
        val input = EditText(this).apply {
            text = editable(name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.file_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ok = PFile(this, input.text.toString().trim(), ext).write(content)
                if (ext == THEME_EXT) Theme.invalidate()
                Toast.makeText(this, if (ok) R.string.file_imported else R.string.file_import_fail, Toast.LENGTH_LONG).show()
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
            .setOnCancelListener { finish() }
            .show()
    }
}

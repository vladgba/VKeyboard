package x.vladgba.keyboard.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.LAYOUT_EXT
import x.vladgba.keyboard.core.PFile

/** Saves a layout/theme to a user-chosen document. */
class FileExport : LocalizedActivity() {
    private var name = ""
    private var ext = LAYOUT_EXT

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name") ?: return finish()
        ext = intent.getStringExtra("ext") ?: LAYOUT_EXT
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "$name.$ext")
        }, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQUEST && resultCode == RESULT_OK && uri != null) {
            try {
                // Bug fix: themes were exported from the .txt file of the same name.
                contentResolver.openOutputStream(uri)?.use { it.write(PFile(this, name, ext).read().toByteArray()) }
                Toast.makeText(this, R.string.file_saved, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.file_save_fail, Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST = 1
    }
}

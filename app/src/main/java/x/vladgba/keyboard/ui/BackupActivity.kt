package x.vladgba.keyboard.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import x.vladgba.keyboard.R
import x.vladgba.keyboard.core.BackupManager
import x.vladgba.keyboard.core.LAYOUT_EXT
import x.vladgba.keyboard.core.SETTINGS_FILENAME
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Screen for creating and restoring full and partial backups of keyboard data. */
class BackupActivity : LocalizedActivity() {
    private lateinit var page: Page

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.bk_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        page = Page(this)
        build()
        setContentView(page.view)
    }

    override fun onNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun build() {
        page.note(getString(R.string.bk_sub))

        // Full backup
        page.header(getString(R.string.bk_sec_full))
        page.row(getString(R.string.bk_export_full), getString(R.string.bk_export_full_sub)) {
            exportDoc("vkeyboard_backup_${timestamp()}.zip", "application/zip", REQ_EXPORT_FULL)
        }
        page.row(getString(R.string.bk_restore_full), getString(R.string.bk_restore_full_sub)) {
            importDoc(arrayOf("application/zip", "application/octet-stream", "*/*"), REQ_IMPORT_BACKUP)
        }

        // Partial backup
        page.header(getString(R.string.bk_sec_partial))
        page.row(getString(R.string.bk_export_settings), getString(R.string.bk_export_settings_sub)) {
            exportDoc("$SETTINGS_FILENAME.$LAYOUT_EXT", "text/plain", REQ_EXPORT_SETTINGS)
        }
        page.row(getString(R.string.bk_import_settings), getString(R.string.bk_import_settings_sub)) {
            importDoc(arrayOf("text/plain", "*/*"), REQ_IMPORT_SETTINGS)
        }
        page.row(getString(R.string.bk_export_config), getString(R.string.bk_export_config_sub)) {
            exportDoc("vkeyboard_config_${timestamp()}.zip", "application/zip", REQ_EXPORT_CONFIG)
        }
        page.row(getString(R.string.bk_export_layouts), getString(R.string.bk_export_layouts_sub)) {
            exportDoc("vkeyboard_layouts_${timestamp()}.zip", "application/zip", REQ_EXPORT_LAYOUTS)
        }
    }

    private fun timestamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun exportDoc(title: String, mime: String, req: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, title)
        }, req)
    }

    private fun importDoc(mimes: Array<String>, req: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        }, req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQ_EXPORT_FULL -> performExport(uri) { BackupManager.createBackup(this, it, BackupManager.BackupScope.FULL) }
            REQ_EXPORT_CONFIG -> performExport(uri) { BackupManager.createBackup(this, it, BackupManager.BackupScope.CONFIG_ONLY) }
            REQ_EXPORT_LAYOUTS -> performExport(uri) { BackupManager.createBackup(this, it, BackupManager.BackupScope.LAYOUTS_THEMES) }
            REQ_EXPORT_SETTINGS -> performExport(uri) { BackupManager.exportSingleFile(this, SETTINGS_FILENAME, LAYOUT_EXT, it) }

            REQ_IMPORT_BACKUP -> {
                confirm(this, R.string.bk_confirm_restore, R.string.bk_confirm_restore_msg, R.string.yes) {
                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val res = BackupManager.restoreBackup(this, stream)
                            if (res.success) {
                                Toast.makeText(this, getString(R.string.bk_restore_success, res.count), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this, "${getString(R.string.bk_restore_fail)}: ${res.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.bk_restore_fail, Toast.LENGTH_LONG).show()
                    }
                }
            }

            REQ_IMPORT_SETTINGS -> {
                confirm(this, R.string.bk_confirm_restore, R.string.bk_confirm_restore_msg, R.string.yes) {
                    try {
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val ok = BackupManager.importSingleFile(this, SETTINGS_FILENAME, LAYOUT_EXT, stream)
                            Toast.makeText(this, if (ok) R.string.file_imported else R.string.file_import_fail, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.file_import_fail, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun performExport(uri: Uri, op: (java.io.OutputStream) -> Boolean) {
        try {
            val ok = contentResolver.openOutputStream(uri)?.use { op(it) } ?: false
            Toast.makeText(this, if (ok) R.string.bk_export_success else R.string.bk_export_fail, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.bk_export_fail, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQ_EXPORT_FULL = 101
        const val REQ_EXPORT_CONFIG = 102
        const val REQ_EXPORT_LAYOUTS = 103
        const val REQ_EXPORT_SETTINGS = 104
        const val REQ_IMPORT_BACKUP = 105
        const val REQ_IMPORT_SETTINGS = 106
    }
}

package x.vladgba.keyboard.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import x.vladgba.keyboard.R

internal fun editable(s: String): Editable = Editable.Factory.getInstance().newEditable(s)

/** Red "Yes / No" confirmation dialog. */
internal fun confirm(ctx: Context, title: Int, message: Int, positive: Int = R.string.yes, onYes: () -> Unit) {
    val dialog = AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positive) { _, _ -> onYes() }
        .setNegativeButton(R.string.no) { d, _ -> d.cancel() }
        .create()
    dialog.show()
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.RED)
}

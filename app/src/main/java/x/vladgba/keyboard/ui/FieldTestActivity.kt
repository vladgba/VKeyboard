package x.vladgba.keyboard.ui

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import x.vladgba.keyboard.R

/**
 * Text fields of every common kind (as named in Android's `inputType`), to try how the keyboard
 * behaves in each: layout, suggestion bar, learning, Enter action, auto-capitals.
 */
class FieldTestActivity : LocalizedActivity() {
    private class Field(val label: String, val inputType: Int, val imeOptions: Int = 0, val multiLine: Boolean = false)

    private val text = InputType.TYPE_CLASS_TEXT
    private val num = InputType.TYPE_CLASS_NUMBER
    private val date = InputType.TYPE_CLASS_DATETIME

    private val fields = listOf(
        Field("text", text),
        Field("textMultiLine", text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, multiLine = true),
        Field("textCapSentences", text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES),
        Field("textCapWords", text or InputType.TYPE_TEXT_FLAG_CAP_WORDS),
        Field("textCapCharacters", text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS),
        Field("textAutoCorrect", text or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT),
        Field("textNoSuggestions", text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS),
        Field("textPersonName", text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME),
        Field("textEmailAddress", text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        Field("textUri", text or InputType.TYPE_TEXT_VARIATION_URI),
        Field("textShortMessage", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE),
        Field("textPostalAddress", text or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS),
        Field("textPassword", text or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        Field("textVisiblePassword", text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD),
        Field("textWebPassword", text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD),
        Field("text + flagNoPersonalizedLearning (incognito)", text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING),
        Field("number", num),
        Field("numberSigned|numberDecimal", num or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL),
        Field("numberPassword (PIN)", num or InputType.TYPE_NUMBER_VARIATION_PASSWORD),
        Field("phone", InputType.TYPE_CLASS_PHONE),
        Field("datetime", date),
        Field("date", date or InputType.TYPE_DATETIME_VARIATION_DATE),
        Field("time", date or InputType.TYPE_DATETIME_VARIATION_TIME),
        Field("text + actionGo", text, EditorInfo.IME_ACTION_GO),
        Field("text + actionSearch", text, EditorInfo.IME_ACTION_SEARCH),
        Field("text + actionSend", text, EditorInfo.IME_ACTION_SEND),
        Field("text + actionNext", text, EditorInfo.IME_ACTION_NEXT),
        Field("text + actionDone", text, EditorInfo.IME_ACTION_DONE),
        Field("text + flagNoEnterAction", text, EditorInfo.IME_FLAG_NO_ENTER_ACTION),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.ft_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val page = Page(this)
        page.note(getString(R.string.ft_intro))
        for (f in fields) {
            page.header(f.label)
            page.add(EditText(this).apply {
                inputType = f.inputType
                imeOptions = f.imeOptions
                if (f.multiLine) minLines = 3 else setSingleLine(true)
                hint = f.label
            }, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(page.view)
    }
}

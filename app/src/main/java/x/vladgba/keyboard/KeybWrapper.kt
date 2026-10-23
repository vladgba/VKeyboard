package x.vladgba.keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import x.vladgba.keyboard.core.AppLocale
import x.vladgba.keyboard.core.DirectBoot
import x.vladgba.keyboard.core.Settings
import x.vladgba.keyboard.core.Theme
import x.vladgba.keyboard.core.Labels
import x.vladgba.keyboard.keyboard.ImeFrame
import x.vladgba.keyboard.keyboard.Keyboard
import x.vladgba.keyboard.keyboard.Macros
import x.vladgba.keyboard.keyboard.TouchStats

/**
 * The input method service. Android remembers enabled keyboards by component name
 * (`x.vladgba.keyboard/.KeybWrapper`), so renaming this class or the package makes users
 * re-enable the keyboard.
 */
class KeybWrapper : InputMethodService() {
    private lateinit var keyboard: Keyboard
    private var localeVersion = 0

    /**
     * Started before the first unlock after a reboot (direct boot): the keyboard runs on the
     * bundled defaults in device-protected storage until [onUserUnlocked] switches it to the
     * user's own layouts and settings.
     */
    private var startedLocked = false
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onUserUnlocked()
    }

    override fun onCreate() {
        super.onCreate()
        startedLocked = DirectBoot.isLocked(this)
        if (startedLocked) {
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, filter, RECEIVER_EXPORTED)
            else registerReceiver(unlockReceiver, filter)
        }
        AppLocale.migrate(this)
        AppLocale.applyTo(this)
        localeVersion = AppLocale.version
        keyboard = Keyboard(DirectBoot.storage(this), this)
    }

    /** First unlock after a direct-boot start: reload everything from the user's own storage. */
    private fun onUserUnlocked() {
        if (!startedLocked) return
        startedLocked = false
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        AppLocale.migrate(this)
        AppLocale.applyTo(this)
        Settings.invalidate()
        Theme.invalidate()
        Macros.invalidate()
        TouchStats.reload(this)

        keyboard.onDestroy()
        keyboard = Keyboard(this, this)
        frame?.let { (it.parent as? ViewGroup)?.removeView(it) }
        frame = null
        if (isInputViewShown) {
            setInputView(onCreateInputView())
            currentInputEditorInfo?.let { keyboard.onStartInput(it) }
            keyboard.onShow()
        }
    }

    private var frame: ImeFrame? = null

    override fun onCreateInputView(): View {
        frame?.let { (it.parent as? ViewGroup)?.removeView(it) }
        return (frame ?: ImeFrame(this, keyboard).also { frame = it })
    }

    /**
     * Only the keyboard counts: the transparent head-room above it (used by popups) neither
     * pushes the app's content up nor catches the app's touches.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val f = frame ?: return
        if (!f.isShown || isFullscreenMode) return
        val kv = f.keyboardView
        val loc = IntArray(2)
        kv.getLocationInWindow(loc)
        val top = loc[1]
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, f.width, top + kv.height)
    }

    override fun onStartInput(attr: EditorInfo, restarting: Boolean) {
        super.onStartInput(attr, restarting)
        keyboard.onStartInput(attr)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // In case the unlock broadcast was missed.
        if (startedLocked && !DirectBoot.isLocked(this)) onUserUnlocked()
        // In-app language changed (or Android reset our resources): refresh translated labels.
        if (AppLocale.applyTo(this) || localeVersion != AppLocale.version) {
            localeVersion = AppLocale.version
            Labels.clear()
            keyboard.invalidate()
        }
        keyboard.onStartInput(info)
        keyboard.onShow()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        keyboard.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboard.onHide()
        super.onFinishInputView(finishingInput)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent) =
        keyboard.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) =
        keyboard.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

    override fun onConfigurationChanged(cfg: Configuration) {
        super.onConfigurationChanged(cfg)
        AppLocale.applyTo(this) // older Android: a configuration change resets the language
        keyboard.onConfigurationChanged(cfg)
        keyboard.invalidate()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        keyboard.onTrimMemory(level)
    }

    override fun onDestroy() {
        if (startedLocked) try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        keyboard.onDestroy()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    override fun setInputView(view: View) {
        super.setInputView(view)
        updateSoftInputWindowLayoutParams()
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
        updateSoftInputWindowLayoutParams()
    }

    /** Pins the keyboard to the bottom of its window (needed on some Android versions/launchers). */
    private fun updateSoftInputWindowLayoutParams() {
        val w = window.window ?: return
        w.attributes?.let {
            if (it.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                it.height = ViewGroup.LayoutParams.MATCH_PARENT
                w.attributes = it
            }
        }

        val view = w.findViewById<View>(android.R.id.inputArea)?.parent as? View ?: return
        val lp = view.layoutParams ?: return
        val height = if (isFullscreenMode) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (lp.height != height) lp.height = height
        when (lp) {
            is LinearLayout.LayoutParams -> lp.gravity = Gravity.BOTTOM
            is FrameLayout.LayoutParams -> lp.gravity = Gravity.BOTTOM
        }
        view.layoutParams = lp
    }
}

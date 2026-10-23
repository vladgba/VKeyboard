package x.vladgba.keyboard.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import java.io.File

/**
 * Direct boot: after a reboot, until the user unlocks the phone once, the app's normal
 * (credential-encrypted) storage can't be read or written, and opening its preferences throws.
 * The keyboard service is direct-boot aware so it still works on the lock screen; meanwhile it
 * keeps its files in device-protected storage, filled with the bundled defaults.
 */
object DirectBoot {
    /** True before the first unlock after a reboot. */
    fun isLocked(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 24 && ctx.getSystemService(UserManager::class.java)?.isUserUnlocked == false

    /**
     * [base] (theme, display, services unchanged) with its files and preferences in
     * device-protected storage. The user's own layouts and settings are not copied there.
     */
    fun lockedStorage(base: Context): Context {
        if (Build.VERSION.SDK_INT < 24) return base
        val de = base.createDeviceProtectedStorageContext()
        return object : ContextWrapper(base) {
            override fun getFilesDir(): File = de.filesDir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                de.getSharedPreferences(name, mode)
        }
    }

    /** [ctx] while the user is unlocked, otherwise [lockedStorage]. */
    fun storage(ctx: Context): Context = if (isLocked(ctx)) lockedStorage(ctx) else ctx
}

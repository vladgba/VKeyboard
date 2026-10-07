package x.vladgba.keyboard.keyboard

import android.util.Log
import java.io.DataOutputStream

/** Text conversions for the clipboard key, and root command execution. */
class KeyAction(private val kb: Keyboard) {
    fun utf2char(tx: String) = splitFor(tx, "\\u") { it.trim().toInt(16).toChar().toString() }
    fun char2utfEscape(tx: String) = splitFor(tx, "") { "\\u" + it[0].code.toString(16).uppercase().padStart(4, '0') }
    fun hex2char(tx: String) = splitFor(tx, "0x") { it.trim().toInt(16).toChar().toString() }
    fun char2hex(tx: String) = splitFor(tx, "") { " 0x" + it[0].code.toString(16).uppercase().padStart(4, '0') }
    fun dec2char(tx: String) = splitFor(tx, " ") { it.trim().toInt().toChar().toString() }
    fun char2dec(tx: String) = splitFor(tx, "") { " " + it[0].code.toString(10) }
    fun oct2char(tx: String) = splitFor(tx, " 0") { it.trim().toInt(8).toChar().toString() }
    fun char2oct(tx: String) = splitFor(tx, "") { " " + it[0].code.toString(8) }
    fun bin2char(tx: String) = splitFor(tx, "0b") { it.trim().toInt(2).toChar().toString() }
    fun char2bin(tx: String) = splitFor(tx, "") { " " + it[0].code.toString(2) }

    /** Converts every part; a part that fails to parse is skipped instead of aborting everything. */
    private fun splitFor(tx: String, delimiter: String, fn: (String) -> String) {
        val out = StringBuilder()
        for (part in tx.split(delimiter)) {
            if (part.isBlank()) continue
            try {
                out.append(fn(part))
            } catch (_: Exception) {
            }
        }
        if (out.isNotEmpty()) kb.onText(out.toString())
    }

    /** Runs a root command without blocking the UI thread. */
    fun suExec(cmd: String) {
        if (cmd.isBlank()) return
        Thread { suExecBlocking(cmd) }.start()
    }

    companion object {
        fun suExecBlocking(cmd: String) {
            try {
                val p = Runtime.getRuntime().exec("su")
                DataOutputStream(p.outputStream).use {
                    it.writeBytes(cmd)
                    it.writeBytes("\nexit\n")
                    it.flush()
                }
                p.waitFor()
            } catch (e: Exception) {
                Log.e("KeyAction", "su failed", e)
            }
        }
    }
}

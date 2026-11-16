package com.bryanchi.kite.timer

import android.content.Context
import android.provider.Settings
import com.bryanchi.kite.Kite
import com.bryanchi.kite.data.Perms

/**
 * Turns the whole screen grey while a social app is open, using Android's own colour-correction
 * switch. Whatever the switch was set to before is put back afterwards.
 */
object Grayscale {
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"
    private const val MONOCHROME = 0

    fun available(c: Context) = Perms.canWriteSecure(c)

    private fun isOn() = Kite.prefs.sp.getBoolean("greyApplied", false)

    /** Call often: it only touches system settings when the wanted state changes. */
    fun set(c: Context, on: Boolean) {
        if (on == isOn()) return
        if (!available(c)) {
            Kite.prefs.sp.edit().putBoolean("greyApplied", false).apply()
            return
        }
        runCatching {
            val cr = c.contentResolver
            val sp = Kite.prefs.sp
            if (on) {
                sp.edit()
                    .putInt("greyPrevEnabled", Settings.Secure.getInt(cr, ENABLED, 0))
                    .putInt("greyPrevMode", Settings.Secure.getInt(cr, MODE, -1))
                    .putBoolean("greyApplied", true)
                    .apply()
                Settings.Secure.putInt(cr, MODE, MONOCHROME)
                Settings.Secure.putInt(cr, ENABLED, 1)
            } else {
                Settings.Secure.putInt(cr, ENABLED, sp.getInt("greyPrevEnabled", 0))
                Settings.Secure.putInt(cr, MODE, sp.getInt("greyPrevMode", -1))
                sp.edit().putBoolean("greyApplied", false).apply()
            }
        }
    }
}

package com.bryanchi.kite.timer

import android.content.SharedPreferences
import com.bryanchi.kite.data.SocialDay
import java.util.Calendar

/**
 * Strict daily budget for social apps.
 * - Raising the budget only applies tomorrow; lowering applies now.
 * - Blackout hours lock social apps entirely.
 * - The only way past a lock is an escape: stay on the block screen for a wait that doubles every time.
 * - Opening from the launcher costs a pause that grows with each open today.
 */
class SocialTimer(private val sp: SharedPreferences) {

    private fun today() = SocialDay.today()

    val budgetMin: Int
        get() {
            applyPendingBudget()
            return sp.getInt("budget", 30)
        }

    val pendingBudgetMin: Int?
        get() = sp.getInt("pendingBudget", -1).takeIf { it > 0 }

    /** Returns true if applied immediately, false if it waits until tomorrow. */
    fun requestBudget(min: Int): Boolean {
        val m = min.coerceIn(0, 600)
        return if (m <= budgetMin) {
            sp.edit().putInt("budget", m).remove("pendingBudget").remove("pendingBudgetDay").apply()
            true
        } else {
            sp.edit().putInt("pendingBudget", m).putString("pendingBudgetDay", today()).apply()
            false
        }
    }

    private fun applyPendingBudget() {
        val p = sp.getInt("pendingBudget", -1)
        if (p < 0) return
        if (sp.getString("pendingBudgetDay", "") == today()) return
        sp.edit().putInt("budget", p).remove("pendingBudget").remove("pendingBudgetDay").apply()
    }

    var blackoutEnabled: Boolean
        get() = sp.getBoolean("blackoutOn", true)
        private set(v) = sp.edit().putBoolean("blackoutOn", v).apply()
    var blackoutStart: Int
        get() = sp.getInt("blackoutStart", 23)
        private set(v) = sp.edit().putInt("blackoutStart", v).apply()
    var blackoutEnd: Int
        get() = sp.getInt("blackoutEnd", 7)
        private set(v) = sp.edit().putInt("blackoutEnd", v).apply()

    /** Blackout can't be changed while it is active. */
    fun setBlackout(enabled: Boolean, start: Int, end: Int): Boolean {
        if (inBlackout()) return false
        blackoutEnabled = enabled
        blackoutStart = (start + 24) % 24
        blackoutEnd = (end + 24) % 24
        return true
    }

    fun inBlackout(now: Long = System.currentTimeMillis()): Boolean {
        if (!blackoutEnabled || blackoutStart == blackoutEnd) return false
        val h = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return if (blackoutStart < blackoutEnd) h in blackoutStart until blackoutEnd
        else h >= blackoutStart || h < blackoutEnd
    }

    fun usedMs(): Long = sp.getLong("used_${today()}", 0L)

    fun addUsage(ms: Long) {
        val k = "used_${today()}"
        sp.edit().putLong(k, sp.getLong(k, 0L) + ms).apply()
    }

    fun remainingMs(): Long = (budgetMin * 60_000L - usedMs()).coerceAtLeast(0L)

    fun passActive(now: Long = System.currentTimeMillis()) = now < sp.getLong("passUntil", 0L)

    /** Null when social apps may be used right now, otherwise a human reason. */
    fun lockReason(now: Long = System.currentTimeMillis()): String? = when {
        passActive(now) -> null
        inBlackout(now) -> "Blackout until %d:00".format(blackoutEnd)
        remainingMs() <= 0L -> "Today's $budgetMin minutes are used up"
        else -> null
    }

    fun opensToday(): Int = sp.getInt("opens_${today()}", 0)
    fun recordOpen() {
        val k = "opens_${today()}"
        sp.edit().putInt(k, sp.getInt(k, 0) + 1).apply()
    }

    /** 5s, 10s, 20s, then 30s for every further open today. */
    fun pauseSeconds(): Int = listOf(5, 10, 20, 30)[minOf(opensToday(), 3)]

    fun escapesToday(): Int = sp.getInt("escapes_${today()}", 0)

    /** 2 min, 4, 8, 16, capped at 30. */
    fun escapeWaitSeconds(): Int = minOf(120 shl minOf(escapesToday(), 4), 1800)

    fun grantPass(minutes: Int = 5) {
        val k = "escapes_${today()}"
        sp.edit()
            .putInt(k, sp.getInt(k, 0) + 1)
            .putLong("passUntil", System.currentTimeMillis() + minutes * 60_000L)
            .apply()
    }
}

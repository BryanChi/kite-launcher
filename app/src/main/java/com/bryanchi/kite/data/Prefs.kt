package com.bryanchi.kite.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Calendar

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("kite", Context.MODE_PRIVATE)

    /** Package names pinned to the bottom of home, in the user's order. Never reshuffled. */
    var pinned: List<String>
        get() = sp.getString("pinned", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("pinned", v.joinToString(",")).apply()

    var pinnedSeeded: Boolean
        get() = sp.getBoolean("pinnedSeeded", false)
        set(v) = sp.edit().putBoolean("pinnedSeeded", v).apply()

    var socialSeeded: Boolean
        get() = sp.getBoolean("socialSeeded", false)
        set(v) = sp.edit().putBoolean("socialSeeded", v).apply()

    var wheelUses: Int
        get() = sp.getInt("wheelUses", 0)
        set(v) = sp.edit().putInt("wheelUses", v).apply()

    /** The first-run walkthrough has been finished or skipped. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var wheelHaptics: Boolean
        get() = sp.getBoolean("wheelHaptics", true)
        set(v) = sp.edit().putBoolean("wheelHaptics", v).apply()

    /** Turn the whole screen grey while a social app is open. Needs a one-time permission from a computer. */
    var grayscale: Boolean
        get() = sp.getBoolean("grayscale", false)
        set(v) = sp.edit().putBoolean("grayscale", v).apply()

    /** Dim: a translucent black layer over the whole screen, for a dark room. */
    var dimOn: Boolean
        get() = sp.getBoolean("dimOn", false)
        set(v) = sp.edit().putBoolean("dimOn", v).apply()

    /** How dark the Dim layer is, as its alpha. */
    var dimLevel: Float
        get() = sp.getFloat("dimLevel", DIM_DEFAULT).coerceIn(DIM_MIN, DIM_MAX)
        set(v) = sp.edit().putFloat("dimLevel", v.coerceIn(DIM_MIN, DIM_MAX)).apply()

    companion object {
        const val DIM_DEFAULT = 0.5f
        const val DIM_MIN = 0.1f
        const val DIM_MAX = 0.85f
    }

    var autostartConfirmed: Boolean
        get() = sp.getBoolean("autostartConfirmed", false)
        set(v) = sp.edit().putBoolean("autostartConfirmed", v).apply()

    /** Packages under the social timer. Removing one only takes effect tomorrow (strict mode). */
    val social: Set<String>
        get() {
            applyPendingSocialRemovals()
            return sp.getStringSet("social", emptySet())!!.toSet()
        }

    val socialPendingRemoval: Set<String>
        get() = sp.getStringSet("socialRemove", emptySet())!!.toSet()

    fun addSocial(pkg: String) {
        val s = social + pkg
        val pending = socialPendingRemoval - pkg
        sp.edit().putStringSet("social", s).putStringSet("socialRemove", pending).apply()
    }

    fun requestRemoveSocial(pkg: String, today: String) {
        sp.edit()
            .putStringSet("socialRemove", socialPendingRemoval + pkg)
            .putString("socialRemoveDay", today)
            .apply()
    }

    private fun applyPendingSocialRemovals() {
        val pending = socialPendingRemoval
        if (pending.isEmpty()) return
        val day = sp.getString("socialRemoveDay", "") ?: ""
        if (day == SocialDay.today()) return
        val s = sp.getStringSet("social", emptySet())!!.toSet() - pending
        sp.edit().putStringSet("social", s).remove("socialRemove").remove("socialRemoveDay").apply()
    }

    /** Launch counts per hour of day, used for the single "Now" slot. */
    fun recordLaunch(pkg: String) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val log = JSONObject(sp.getString("hourlog", "{}")!!)
        val key = "$hour|$pkg"
        log.put(key, log.optInt(key, 0) + 1)
        val total = JSONObject(sp.getString("launchTotals", "{}")!!)
        total.put(pkg, total.optInt(pkg, 0) + 1)
        val scores = decayedScores()
        scores.put(pkg, scores.optDouble(pkg, 0.0) + 1.0)
        sp.edit()
            .putString("hourlog", log.toString())
            .putString("launchTotals", total.toString())
            .putString("scores", scores.toString())
            .putLong("scoresDay", epochDay())
            .apply()
    }

    private fun epochDay() = System.currentTimeMillis() / 86_400_000L

    /**
     * What the wheel learns from: launch counts that fade about 4% a day, so the order follows what
     * you use these days without reshuffling every time. Starts from the all-time totals.
     */
    private fun decayedScores(): JSONObject {
        val raw = sp.getString("scores", null) ?: return JSONObject(sp.getString("launchTotals", "{}")!!)
        val src = JSONObject(raw)
        val days = (epochDay() - sp.getLong("scoresDay", epochDay())).coerceIn(0L, 120L)
        if (days == 0L) return src
        val factor = Math.pow(0.96, days.toDouble())
        val out = JSONObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = src.optDouble(k, 0.0) * factor
            if (v >= 0.05) out.put(k, v)
        }
        return out
    }

    fun wheelScores(): Map<String, Double> {
        val o = decayedScores()
        val out = HashMap<String, Double>()
        val keys = o.keys()
        while (keys.hasNext()) { val k = keys.next(); out[k] = o.optDouble(k, 0.0) }
        return out
    }

    fun hourScores(hour: Int): Map<String, Int> {
        val log = JSONObject(sp.getString("hourlog", "{}")!!)
        val out = HashMap<String, Int>()
        val keys = log.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val h = k.substringBefore('|').toIntOrNull() ?: continue
            val pkg = k.substringAfter('|')
            val dist = minOf((h - hour + 24) % 24, (hour - h + 24) % 24)
            val weight = when (dist) { 0 -> 3; 1 -> 1; else -> 0 }
            if (weight > 0) out[pkg] = (out[pkg] ?: 0) + weight * log.getInt(k)
        }
        return out
    }

    fun launchTotals(): Map<String, Int> {
        val o = JSONObject(sp.getString("launchTotals", "{}")!!)
        val out = HashMap<String, Int>()
        val keys = o.keys()
        while (keys.hasNext()) { val k = keys.next(); out[k] = o.getInt(k) }
        return out
    }
}

/** The "day" for limits rolls over at 4 AM, so late nights count toward the day they started. */
object SocialDay {
    const val ROLLOVER_HOUR = 4
    fun today(now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = now - ROLLOVER_HOUR * 3_600_000L }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}

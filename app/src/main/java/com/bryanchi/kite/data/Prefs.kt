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
        sp.edit().putString("hourlog", log.toString()).putString("launchTotals", total.toString()).apply()
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

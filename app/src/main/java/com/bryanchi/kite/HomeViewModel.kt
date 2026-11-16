package com.bryanchi.kite

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bryanchi.kite.data.AppEntry
import com.bryanchi.kite.data.Card
import com.bryanchi.kite.data.Perms
import com.bryanchi.kite.data.SocialDay
import com.bryanchi.kite.timer.GateActivity
import kotlinx.coroutines.launch
import java.util.Calendar

enum class Overlay { NONE, ALL_APPS, SETTINGS }

data class PermState(
    val home: Boolean = false,
    val usage: Boolean = false,
    val overlay: Boolean = false,
    val notify: Boolean = false,
    val battery: Boolean = false,
    val autostart: Boolean = false,
    val assistant: Boolean = false,
) {
    val timerReady get() = usage && overlay && battery && autostart
}

/** Social apps that start under the timer if installed. Users can add any others. */
private val DEFAULT_SOCIAL = listOf(
    "com.instagram.android", "com.instagram.barcelona", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
    "com.twitter.android", "com.facebook.katana", "com.facebook.lite", "com.snapchat.android",
    "com.reddit.frontpage", "com.pinterest", "com.bereal.ft",
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()

    val apps = Kite.apps.apps
    val cards = Kite.parking.cards

    var pinned by mutableStateOf(Kite.prefs.pinned)
        private set
    var social by mutableStateOf(Kite.prefs.social)
        private set
    var nowPkg by mutableStateOf<String?>(null)
        private set
    var overlay by mutableStateOf(Overlay.NONE)
    var drawerLetter by mutableStateOf<Char?>(null)
    var cardDetail by mutableStateOf<Card?>(null)
    var menuFor by mutableStateOf<AppEntry?>(null)
    var perm by mutableStateOf(PermState())
        private set
    var wheelUses by mutableIntStateOf(Kite.prefs.wheelUses)
        private set
    /** Bumped to re-read timer values that live in SharedPreferences. */
    var tick by mutableIntStateOf(0)
        private set

    init {
        viewModelScope.launch {
            apps.collect { list ->
                if (list.isEmpty()) return@collect
                seedSocial(list)
                seedPinned(list)
                computeNow(list)
            }
        }
    }

    fun refresh() {
        Kite.parking.prune()
        social = Kite.prefs.social
        pinned = Kite.prefs.pinned
        perm = PermState(
            home = Perms.isDefaultHome(ctx),
            usage = Perms.hasUsageAccess(ctx),
            overlay = Perms.canOverlay(ctx),
            notify = Perms.canNotify(ctx),
            battery = Perms.ignoresBattery(ctx),
            autostart = Kite.prefs.autostartConfirmed,
            assistant = Perms.isAssistant(ctx),
        )
        apps.value.takeIf { it.isNotEmpty() }?.let { seedPinned(it); computeNow(it) }
        Kite.apps.reloadAsync()
        tick++
    }

    fun bump() { tick++ }

    fun back() {
        when {
            cardDetail != null -> cardDetail = null
            menuFor != null -> menuFor = null
            else -> { overlay = Overlay.NONE; drawerLetter = null }
        }
    }

    fun closeAll() {
        cardDetail = null; menuFor = null; overlay = Overlay.NONE; drawerLetter = null
    }

    fun openDrawer(letter: Char? = null) {
        drawerLetter = letter
        overlay = Overlay.ALL_APPS
    }

    fun launch(app: AppEntry) {
        if (app.pkg in social) {
            ctx.startActivity(GateActivity.pauseIntent(ctx, app.pkg))
        } else {
            Kite.prefs.recordLaunch(app.pkg)
            Kite.apps.launch(app)
        }
        closeAll()
    }

    fun wheelUsed() {
        Kite.prefs.wheelUses = Kite.prefs.wheelUses + 1
        wheelUses = Kite.prefs.wheelUses
    }

    // ---- pinned (locked positions) ----

    fun togglePin(pkg: String) {
        val p = Kite.prefs.pinned
        Kite.prefs.pinned = if (pkg in p) p - pkg else p + pkg
        pinned = Kite.prefs.pinned
        computeNow(apps.value)
    }

    fun movePin(pkg: String, delta: Int) {
        val p = Kite.prefs.pinned.toMutableList()
        val i = p.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in p.indices) return
        p.removeAt(i); p.add(j, pkg)
        Kite.prefs.pinned = p
        pinned = p
    }

    // ---- social timer membership ----

    fun addSocial(pkg: String) {
        Kite.prefs.addSocial(pkg)
        social = Kite.prefs.social
        tick++
    }

    fun removeSocial(pkg: String) {
        Kite.prefs.requestRemoveSocial(pkg, SocialDay.today())
        Toast.makeText(ctx, "Leaves the timer tomorrow", Toast.LENGTH_SHORT).show()
        tick++
    }

    // ---- parking lot ----

    fun openCard(card: Card) {
        if (!Kite.parking.open(ctx, card)) {
            cardDetail = card
            Toast.makeText(ctx, "Can't jump back into that one, here's what you saved", Toast.LENGTH_SHORT).show()
        }
    }

    fun uninstall(pkg: String) {
        Perms.start(ctx, Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)))
    }

    // ---- seeding and the Now slot ----

    private fun seedSocial(list: List<AppEntry>) {
        if (Kite.prefs.socialSeeded) return
        val installed = list.map { it.pkg }.toSet()
        DEFAULT_SOCIAL.filter { it in installed }.forEach { Kite.prefs.addSocial(it) }
        Kite.prefs.socialSeeded = true
        social = Kite.prefs.social
    }

    private fun seedPinned(list: List<AppEntry>) {
        if (Kite.prefs.pinnedSeeded || !Perms.hasUsageAccess(ctx)) return
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(now - 14 * 86_400_000L, now) }.getOrNull() ?: return
        val installed = list.map { it.pkg }.toSet()
        val top = stats.values.sortedByDescending { it.totalTimeInForeground }
            .map { it.packageName }
            .filter { it in installed && it !in social }
            .distinct()
            .take(5)
        if (Kite.prefs.pinned.isEmpty()) Kite.prefs.pinned = top
        Kite.prefs.pinnedSeeded = true
        pinned = Kite.prefs.pinned
    }

    private fun computeNow(list: List<AppEntry>) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val installed = list.map { it.pkg }.toSet()
        nowPkg = Kite.prefs.hourScores(hour).entries
            .filter { it.key in installed && it.key !in pinned && it.key !in social }
            .maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key
    }
}

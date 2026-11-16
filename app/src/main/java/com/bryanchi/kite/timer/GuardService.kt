package com.bryanchi.kite.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bryanchi.kite.Kite
import com.bryanchi.kite.MainActivity
import com.bryanchi.kite.R
import com.bryanchi.kite.data.Perms

/**
 * Watches which app is on screen (usage access) and covers social apps with the block screen
 * when the budget is gone or it's blackout time. Catches opens from notifications and recents too,
 * not just from the launcher.
 */
class GuardService : Service() {

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTIF_ID = 7
        private const val TAG = "KiteGuard"

        fun start(context: Context) {
            if (!Perms.hasUsageAccess(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            }.onFailure { Log.w(TAG, "could not start guard", it) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var usm: UsageStatsManager
    private lateinit var power: PowerManager
    private var current: String? = null
    private var queryFrom = 0L
    private var lastTick = 0L
    private var lastNotif = 0L
    private var lastGate = 0L
    private var lastSocialPkg: String? = null
    /** Last time each social app was on screen, to tell a fresh open from a quick app switch. */
    private val lastSeen = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(UsageStatsManager::class.java)
        power = getSystemService(PowerManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Social timer", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        val now = System.currentTimeMillis()
        queryFrom = now - 30 * 60_000L
        lastTick = now
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                step()
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(queryFrom, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == current) current = null
            }
        }
        // Overlap windows slightly: events can land late. Replaying them in order is harmless.
        queryFrom = now - 3000

        val delta = (now - lastTick).coerceIn(0L, 3000L)
        lastTick = now

        val pkg = current
        if (power.isInteractive && pkg != null && pkg != packageName && pkg in Kite.prefs.social) {
            val timer = Kite.timer
            val freshOpen = pkg != lastSocialPkg && now - (lastSeen[pkg] ?: 0L) > 2 * 60_000L
            lastSocialPkg = pkg
            lastSeen[pkg] = now
            when {
                timer.lockReason(now) != null -> showGate(now, GateActivity.blockIntent(this, pkg))
                // Opened some other way (notification, recents, a link): same pause as opening from Kite.
                freshOpen && !timer.recentlyApproved(pkg, now) ->
                    showGate(now, GateActivity.pauseOverIntent(this, pkg))
                else -> timer.addUsage(delta)
            }
        } else if (pkg != packageName) {
            lastSocialPkg = null
        }

        if (now - lastNotif > 15_000) {
            lastNotif = now
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun showGate(now: Long, intent: Intent) {
        if (GateActivity.isShowing || now - lastGate < 1500) return
        lastGate = now
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "could not show gate (overlay permission?)", it) }
    }

    private fun buildNotification(): Notification {
        val timer = Kite.timer
        val text = timer.lockReason() ?: "${timer.remainingMs() / 60_000} min of social left today"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kite)
            .setContentTitle("Social timer")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}

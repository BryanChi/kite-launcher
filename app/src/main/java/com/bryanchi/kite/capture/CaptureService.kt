package com.bryanchi.kite.capture

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.util.Patterns
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.bryanchi.kite.Kite
import com.bryanchi.kite.data.AppRepository

/**
 * Parks whatever is on screen, from any app, without Kite being the phone's assistant.
 *
 * Triggers: press volume up and volume down together, the "Park" Quick Settings tile, the
 * "Park this screen" app shortcut, or the accessibility button. Each takes a screenshot and reads
 * the app, the visible text and any link on screen into one parking-lot card. The service reads
 * nothing at any other time.
 */
class CaptureService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: CaptureService? = null

        val running get() = instance != null

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, CaptureService::class.java)
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Closes the notification shade, so a capture from the tile shows the app, not the shade. */
        fun closeShade() {
            val s = instance ?: return
            if (Build.VERSION.SDK_INT >= 31) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            else s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        /**
         * Parks the screen after [delayMs], which gives a tile's shade or a shortcut's window time to
         * get out of the way. Returns false if the service isn't switched on.
         */
        fun capture(delayMs: Long = 0L): Boolean {
            val s = instance ?: return false
            s.handler.postDelayed({ s.capture() }, delayMs)
            return true
        }

        private const val TAG = "KiteCapture"
        private val WAIT_TOKEN = Any()
        /** How long to wait for the app to come back before parking anyway. */
        private const val WAIT_MS = 1500L
        /** Time for the app to finish drawing once it's back. */
        private const val SETTLE_MS = 250L
        /** Only bring back an app that was on screen this recently. */
        private const val RECENT_MS = 10 * 60_000L

        /**
         * Adds a screenshot of whatever is on screen after [delayMs] to card [cardId], if the
         * service is running and a real app (not home or Kite) is in front.
         */
        fun attachScreenshot(cardId: String, delayMs: Long, toast: String) {
            val s = instance ?: return
            s.handler.postDelayed({ s.attachShot(cardId, toast) }, delayMs)
        }

        /** The card a link hunt is filling in, and when it started. */
        @Volatile private var pendingCardId: String? = null
        @Volatile private var pendingAt = 0L

        /**
         * The card a link hunt just made, if it started in the last few seconds. ShareActivity and
         * ClipGrabActivity use it to fill in that card instead of making a new one. Taking it clears it.
         */
        fun takePending(): String? {
            val id = pendingCardId ?: return null
            pendingCardId = null
            return id.takeIf { System.currentTimeMillis() - pendingAt < PENDING_MS }
        }

        /** ShareActivity or ClipGrabActivity has put the link on the pending card. */
        fun linkArrived() {
            val s = instance ?: return
            s.handler.post { s.hunt?.linkArrived() }
        }

        private const val PENDING_MS = 6000L

        /** True while the Dim layer is on screen. */
        val isDimmed get() = instance?.dimView != null

        /**
         * Turns the Dim layer on or off and remembers the choice. Call on the main thread. Returns
         * false if the service isn't running, in which case nothing is shown.
         */
        fun setDim(on: Boolean): Boolean {
            Kite.prefs.dimOn = on
            val s = instance ?: return false
            if (on) s.showDim() else s.hideDim()
            return true
        }

        /** Sets how dark the Dim layer is and updates it live if it's showing. */
        fun setDimLevel(level: Float) {
            Kite.prefs.dimLevel = level
            instance?.dimView?.setBackgroundColor(dimColor())
        }

        private fun dimColor() = Color.argb((Kite.prefs.dimLevel * 255).toInt(), 0, 0, 0)
    }

    private var dimView: View? = null

    /**
     * Dim lives in an accessibility overlay: it sits above the status bar, nav bar, keyboard and
     * shade, and touches pass straight through it.
     */
    private fun showDim() {
        if (dimView != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val (w, h) = realSize(wm)
        // A square of the longest side, so it still covers everything after a rotation.
        val side = maxOf(w, h)
        val lp = WindowManager.LayoutParams(
            side, side,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val v = View(this).apply { setBackgroundColor(dimColor()) }
        runCatching { wm.addView(v, lp) }.onSuccess { dimView = v }
    }

    private fun hideDim() {
        val v = dimView ?: return
        dimView = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    private fun realSize(wm: WindowManager): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val p = Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        return p.x to p.y
    }

    internal val handler = Handler(Looper.getMainLooper())
    private var hunt: LinkHunt? = null
    /** The last real app on screen (never a launcher, Kite or the shade) and when it was seen. */
    private var lastPkg: String? = null
    private var lastSeenAt = 0L
    private var busy = false
    /** Home screens, Kite and system UI: never what the user means to park. */
    private var excluded: Set<String> = emptySet()
    /** While bringing an app back before a capture: the package we're waiting to see, and what to do then. */
    private var waitPkg: String? = null
    private var onBack: (() -> Unit)? = null

    // Volume combo state
    private var upHeld = false
    private var downHeld = false
    private var firstKey = 0
    private var swallowUps = 0

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) { capture() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        excluded = findExcluded()
        Log.d(TAG, "connected; excluded=$excluded")
        runCatching { accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback) }
        if (Kite.prefs.dimOn) showDim()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideDim()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideDim()
        hunt = null
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Remember the last real app on screen, so a capture fired from the shade or a shortcut
        // still knows which app it was about.
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            hunt?.windowChanged()
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == waitPkg) {
            Log.d(TAG, "$pkg is back on screen")
            waitPkg = null
            handler.removeCallbacksAndMessages(WAIT_TOKEN)
            handler.postDelayed({ onBack?.invoke(); onBack = null }, SETTLE_MS)
        }
        if (isExcluded(pkg) || pkg.startsWith("com.vivo.upslide")) return
        lastPkg = pkg
        lastSeenAt = System.currentTimeMillis()
    }

    private fun isExcluded(pkg: String) =
        pkg in excluded || pkg == packageName || pkg == "com.android.systemui" || pkg.contains("launcher")

    /** Every installed home screen (vivo's com.bbk.launcher2 and the like), plus Kite and the shade. */
    private fun findExcluded(): Set<String> {
        val home = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        // Settings answers HOME only as a fallback during boot; it's a real app the rest of the time.
        return (home - "com.android.settings").toSet() + setOf(packageName, "com.android.systemui", "com.bbk.launcher2")
    }

    override fun onInterrupt() {}

    /**
     * Volume up + volume down pressed together parks the screen. The first key does its normal job
     * (it can't be held back), so its one step of volume change is undone when the second arrives.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val isUp = code == KeyEvent.KEYCODE_VOLUME_UP
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return swallowUps > 0
                val otherHeld = if (isUp) downHeld else upHeld
                if (isUp) upHeld = true else downHeld = true
                if (otherHeld && swallowUps == 0) {
                    undoVolumeStep(firstKey)
                    swallowUps = 2
                    capture()
                    return true
                }
                if (!otherHeld) firstKey = code
                return swallowUps > 0
            }
            KeyEvent.ACTION_UP -> {
                if (isUp) upHeld = false else downHeld = false
                if (swallowUps > 0) {
                    swallowUps--
                    return true
                }
            }
        }
        return false
    }

    private fun undoVolumeStep(key: Int) {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val dir = if (key == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE
        runCatching { audio.adjustSuggestedStreamVolume(dir, AudioManager.USE_DEFAULT_STREAM_TYPE, 0) }
    }

    // ---------------- capture ----------------

    /**
     * Parks the screen. If a home screen or Kite is in front (a shortcut or gesture usually goes
     * through home first), the last app is brought back first and parked once it's showing.
     */
    fun capture() {
        if (busy) return
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        Log.d(TAG, "capture: active=$active last=$lastPkg seen ${(System.currentTimeMillis() - lastSeenAt) / 1000}s ago")
        if (active == null || isExcluded(active)) {
            val target = lastPkg?.takeIf { System.currentTimeMillis() - lastSeenAt < RECENT_MS }
            if (target == null) {
                Log.d(TAG, "capture: nothing recent to park")
                Toast.makeText(this, "Open the app you want to park first", Toast.LENGTH_SHORT).show()
                return
            }
            busy = true
            buzz()
            Log.d(TAG, "capture: bringing $target back first")
            waitPkg = target
            onBack = { shoot() }
            if (!AppRepository.resumeTask(this, target)) {
                Log.d(TAG, "capture: couldn't bring $target back")
                waitPkg = null; onBack = null; busy = false
                Toast.makeText(this, "Couldn't reopen that app", Toast.LENGTH_SHORT).show()
                return
            }
            handler.postAtTime({
                if (waitPkg != null) {
                    Log.d(TAG, "capture: no window event from $target, parking anyway")
                    waitPkg = null
                    onBack?.invoke(); onBack = null
                }
            }, WAIT_TOKEN, android.os.SystemClock.uptimeMillis() + WAIT_MS)
            return
        }
        busy = true
        buzz()
        shoot()
    }

    /** Takes a screenshot with the Dim layer briefly hidden, so the picture isn't darkened. */
    private fun screenshot(done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { done(null); return }
        val dim = dimView
        dim?.alpha = 0f
        handler.postDelayed({
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    dim?.alpha = 1f
                    val bmp = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        soft
                    }.getOrNull()
                    done(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    dim?.alpha = 1f
                    Log.d(TAG, "screenshot failed: $errorCode")
                    done(null)
                }
            })
        }, if (dim != null) 120L else 0L)
    }

    /** Adds a screenshot of what's on screen now to an existing card (used after Share › Park in Kite). */
    private fun attachShot(cardId: String, toast: String) {
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (active == null || isExcluded(active)) {
            Log.d(TAG, "attachShot: $active in front, skipping")
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
            return
        }
        screenshot { bmp ->
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
            if (bmp == null) return@screenshot
            Thread { runCatching { Kite.parking.attachImage(cardId, bmp) }; Log.d(TAG, "attachShot: added to $cardId") }.start()
        }
    }

    private fun shoot() {
        val read = readScreen()
        Log.d(TAG, "shoot: pkg=${read.pkg} text=${read.text.length} chars url=${read.url}")
        screenshot { save(read, it) }
    }

    private class Read(val pkg: String?, val title: String?, val text: String, val url: String?)

    private fun readScreen(): Read {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val rootPkg = root?.packageName?.toString()
        val pkg = rootPkg?.takeIf { !isExcluded(it) } ?: lastPkg
        if (root == null || rootPkg != pkg) return Read(pkg, null, "", null)
        val sb = StringBuilder()
        var url: String? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || sb.length > 1500) return
            val id = n.viewIdResourceName.orEmpty()
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                if (url == null && (id.endsWith("url_bar") || id.endsWith("url_field") || id.contains("url_view") ||
                        id.endsWith("location_bar_edit_text") || id.endsWith("search_box"))
                ) {
                    url = t
                }
                sb.append(t).append('\n')
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }
        walk(root, 0)
        val text = sb.toString().take(1500)
        // Browsers show the address in a bar; other apps sometimes show a link in plain text.
        val link = (url ?: text.lineSequence().firstOrNull { Patterns.WEB_URL.matcher(it.trim()).matches() && '.' in it })
            ?.trim()
            ?.takeIf { Patterns.WEB_URL.matcher(it).matches() }
            ?.let { if (it.startsWith("http")) it else "https://$it" }
        return Read(pkg, null, text, link)
    }

    private fun save(read: Read, shot: Bitmap?) {
        val label = read.pkg?.let { AppRepository.label(this, it) } ?: "Screen"
        val title = read.url?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.")
            ?: read.text.lineSequence().map { it.trim() }.firstOrNull { it.length in 3..120 && it != label }
            ?: label
        Thread {
            val card = runCatching { Kite.parking.add(read.pkg, label, title, read.text, read.url, null, shot) }.getOrNull()
            Log.d(TAG, "saved card: app=$label pkg=${read.pkg} ok=${card != null} image=${shot != null} url=${read.url}")
            handler.post {
                val pkg = read.pkg
                if (card != null && read.url == null && pkg != null && !isExcluded(pkg) && Kite.prefs.grabLink) {
                    huntLink(card.id, pkg, label)
                } else {
                    busy = false
                    val msg = when {
                        card == null -> "Couldn't park that"
                        read.url != null -> "Parked ✓  $label · link saved"
                        else -> "Parked ✓  $label"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Presses the app's Share button for the exact link; see [LinkHunt]. The card already exists. */
    private fun huntLink(cardId: String, pkg: String, label: String) {
        pendingCardId = cardId
        pendingAt = System.currentTimeMillis()
        Log.d(TAG, "hunt: start for $pkg card=$cardId")
        val h = LinkHunt(this, pkg, ::isExcluded) { linked ->
            hunt = null
            pendingCardId = null
            busy = false
            Toast.makeText(this, if (linked) "Parked ✓  $label · link saved" else "Parked ✓  $label (no link)", Toast.LENGTH_SHORT).show()
        }
        hunt = h
        h.start()
    }

    private fun buzz() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }
}

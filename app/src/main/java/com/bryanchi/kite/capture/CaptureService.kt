package com.bryanchi.kite.capture

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Patterns
import android.view.Display
import android.view.KeyEvent
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var busy = false

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
        runCatching { accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback) }
    }

    override fun onDestroy() {
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Remember the last real app on screen, so a capture fired from the shade or a shortcut
        // still knows which app it was about.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.startsWith("com.vivo.upslide")) return
        lastPkg = pkg
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

    fun capture() {
        if (busy) return
        busy = true
        buzz()
        val read = readScreen()
        if (Build.VERSION.SDK_INT >= 30) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        soft
                    }.getOrNull()
                    save(read, bmp)
                }

                override fun onFailure(errorCode: Int) = save(read, null)
            })
        } else {
            save(read, null)
        }
    }

    private class Read(val pkg: String?, val title: String?, val text: String, val url: String?)

    private fun readScreen(): Read {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val rootPkg = root?.packageName?.toString()
        val pkg = rootPkg?.takeIf { it != packageName && it != "com.android.systemui" } ?: lastPkg
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
            val ok = runCatching { Kite.parking.add(read.pkg, label, title, read.text, read.url, null, shot) }.isSuccess
            handler.post {
                busy = false
                Toast.makeText(this, if (ok) "Parked ✓  $label" else "Couldn't park that", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun buzz() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        }
    }
}

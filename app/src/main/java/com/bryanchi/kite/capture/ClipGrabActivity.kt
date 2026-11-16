package com.bryanchi.kite.capture

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Patterns
import com.bryanchi.kite.Kite

/**
 * Invisible, lives for a moment after the link hunt presses an app's "Copy link". Android only lets
 * the app in focus read the clipboard, so this takes focus, reads the link onto the card just
 * parked, and gets out of the way.
 */
class ClipGrabActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If focus never comes, don't hang around.
        handler.postDelayed({ close() }, 1500)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull()
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val url = Patterns.WEB_URL.matcher(text).let { m -> if (m.find()) m.group() else null }
            ?.let { if (it.startsWith("http")) it else "https://$it" }
        val id = CaptureService.takePending()
        Log.d("KiteCapture", "clipgrab: card=$id url=$url")
        if (id != null && url != null) {
            Thread {
                Kite.parking.updateLink(id, url, null, null)
                CaptureService.linkArrived()
            }.start()
        }
        close()
    }

    private fun close() {
        if (done) return
        done = true
        handler.removeCallbacksAndMessages(null)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

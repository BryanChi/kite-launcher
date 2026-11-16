package com.bryanchi.kite.capture

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSession.AssistState
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.bryanchi.kite.Kite
import com.bryanchi.kite.data.AppRepository

/**
 * Fired by the assistant gesture. Android hands over the screenshot, the app on screen,
 * and (if the app offers it) a link to the exact content. All of it becomes one parking-lot card.
 */
class CaptureSession(context: Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var shot: Bitmap? = null
    private var structure: AssistStructure? = null
    private var content: AssistContent? = null
    private var data: Bundle? = null
    private var gotAssist = false
    private var gotShot = false
    private var saved = false
    private var label: TextView? = null

    private val timeout = Runnable { save() }

    override fun onCreateContentView(): View {
        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context)
        val tv = TextView(context).apply {
            text = "Parking…"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((22 * density).toInt(), (14 * density).toInt(), (22 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 24 * density
                setColor(0xEE1B1D22.toInt())
            }
        }
        root.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = (110 * density).toInt() },
        )
        root.setOnClickListener { hide() }
        label = tv
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shot = null; structure = null; content = null; data = null
        gotAssist = false; gotShot = false; saved = false
        label?.text = "Parking…"
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 2500)
    }

    override fun onHide() {
        handler.removeCallbacks(timeout)
        super.onHide()
    }

    override fun onHandleAssist(state: AssistState) {
        if (state.index != 0) return // multi-window: keep only the focused window
        structure = state.assistStructure
        content = state.assistContent
        data = state.assistData
        gotAssist = true
        if (gotShot) save()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        shot = screenshot
        gotShot = true
        if (gotAssist) save()
    }

    private fun save() {
        if (saved) return
        saved = true
        handler.removeCallbacks(timeout)

        val s = structure
        val c = content
        val pkg = s?.activityComponent?.packageName
            ?: data?.getString(Intent.EXTRA_ASSIST_PACKAGE)
        val appLabel = pkg?.let { AppRepository.label(context, it) } ?: "Screen"
        val text = s?.let { collectText(it) }.orEmpty()
        val web = c?.webUri?.toString()
        val intentUri = c?.intent?.takeIf { c.isAppProvidedIntent || it.data != null }
            ?.toUri(Intent.URI_INTENT_SCHEME)
        val title = when {
            web != null -> web.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            else -> text.lineSequence().map { it.trim() }.firstOrNull { it.length in 3..120 && it != appLabel }
                ?: s?.takeIf { it.windowNodeCount > 0 }?.getWindowNodeAt(0)?.title?.toString()
                ?: appLabel
        }
        val bmp = shot

        Thread {
            runCatching { Kite.parking.add(pkg, appLabel, title, text, web, intentUri, bmp) }
            handler.post {
                label?.text = "Parked ✓  $appLabel"
                handler.postDelayed({ hide() }, 900)
            }
        }.start()
    }

    private fun collectText(s: AssistStructure, max: Int = 1500): String {
        val sb = StringBuilder()
        fun walk(n: AssistStructure.ViewNode) {
            if (sb.length > max) return
            val t = (n.text ?: n.contentDescription)?.toString()?.trim()
            if (!t.isNullOrEmpty()) sb.append(t).append('\n')
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (w in 0 until s.windowNodeCount) walk(s.getWindowNodeAt(w).rootViewNode)
        return sb.toString().take(max)
    }
}

package com.bryanchi.kite.capture

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bryanchi.kite.Kite
import com.bryanchi.kite.data.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Share → Park in Kite". Links reopen in their app; images are kept as the card picture. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        if (i?.action != Intent.ACTION_SEND) { finish(); return }

        val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val url = Patterns.WEB_URL.matcher(text).let { m -> if (m.find()) m.group() else null }
            ?.let { if (it.startsWith("http")) it else "https://$it" }
        val source = referrer?.host?.takeIf { it != "android" && it != packageName && it.contains('.') }
        val appLabel = source?.let { AppRepository.label(this, it) } ?: "Shared"
        val title = subject.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: appLabel }

        // The park link hunt pressed Share › Park in Kite for us: fill in the card it just made
        // (which has the screenshot) instead of making a second one.
        CaptureService.takePending()?.let { pending ->
            // Many apps share just the link; then keep the text read off the screen.
            val onlyLink = Patterns.WEB_URL.matcher(text.trim()).matches()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    Kite.parking.updateLink(
                        pending, url,
                        subject.ifBlank { null },
                        text.takeIf { !onlyLink && it.isNotBlank() },
                    )
                }
                CaptureService.linkArrived()
                finish()
            }
            return
        }

        lifecycleScope.launch {
            val bmp = stream?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                }
            }
            val card = withContext(Dispatchers.IO) {
                Kite.parking.add(source, appLabel, title, text, url, null, bmp)
            }
            // No picture came with the share: once the share sheet is gone, screenshot the app
            // underneath (needs the park shortcut on). The toast waits so it isn't in the picture.
            if (bmp == null && CaptureService.running) {
                CaptureService.attachScreenshot(card.id, delayMs = 500, toast = "Parked in Kite")
            } else {
                Toast.makeText(this@ShareActivity, "Parked in Kite", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}

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

        lifecycleScope.launch {
            val bmp = stream?.let { uri ->
                withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                }
            }
            withContext(Dispatchers.IO) {
                Kite.parking.add(source, appLabel, title, text, url, null, bmp)
            }
            Toast.makeText(this@ShareActivity, "Parked in Kite", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

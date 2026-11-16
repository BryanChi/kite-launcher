package com.bryanchi.kite.timer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bryanchi.kite.Kite
import com.bryanchi.kite.data.AppRepository
import com.bryanchi.kite.ui.KiteTheme
import kotlinx.coroutines.delay

/**
 * Two modes:
 *  - pause: shown when you open a social app from Kite. Waits a few seconds (longer each open).
 *  - block: shown over a social app when it's locked. Go home, or sit through a growing wait for 5 more minutes.
 */
class GateActivity : ComponentActivity() {

    companion object {
        @Volatile
        var isShowing = false

        fun pauseIntent(c: Context, pkg: String) = Intent(c, GateActivity::class.java)
            .putExtra("mode", "pause").putExtra("pkg", pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Pause shown on top of an app that was opened outside Kite. "Continue" just reveals it. */
        fun pauseOverIntent(c: Context, pkg: String) = Intent(c, GateActivity::class.java)
            .putExtra("mode", "pauseOver").putExtra("pkg", pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun blockIntent(c: Context, pkg: String) = Intent(c, GateActivity::class.java)
            .putExtra("mode", "block").putExtra("pkg", pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private var mode by mutableStateOf("pause")
    private var pkg by mutableStateOf("")
    /** Bumped whenever the screen is left, which resets any escape wait in progress. */
    private var visit by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!readIntent(intent)) { finish(); return }
        onBackPressedDispatcher.addCallback(this) { goHome() }
        setContent {
            KiteTheme {
                GateScreen(mode, pkg, visit, onOpen = ::openApp, onHome = ::goHome)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent): Boolean {
        pkg = i.getStringExtra("pkg") ?: return false
        mode = i.getStringExtra("mode") ?: "pause"
        return true
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
    }

    override fun onPause() {
        isShowing = false
        super.onPause()
    }

    override fun onStop() {
        visit++
        super.onStop()
    }

    private fun openApp(countOpen: Boolean) {
        if (countOpen) Kite.timer.recordOpen()
        Kite.timer.markApproved(pkg)
        if (mode == "pauseOver") {
            // The app (and whatever the notification opened) is already underneath.
            finish()
            return
        }
        Kite.prefs.recordLaunch(pkg)
        AppRepository.launchPackage(this, pkg)
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}

@Composable
private fun GateScreen(
    mode: String,
    pkg: String,
    visit: Int,
    onOpen: (countOpen: Boolean) -> Unit,
    onHome: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val timer = Kite.timer
    val label = remember(pkg) { AppRepository.label(context, pkg) }
    val reason = remember(pkg, mode, visit) { timer.lockReason() }
    val dim = Color(0xFF9AA0A6)

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (reason == null && (mode == "pause" || mode == "pauseOver")) {
            var left by remember(pkg, visit) { mutableIntStateOf(timer.pauseSeconds()) }
            LaunchedEffect(pkg, visit) {
                while (left > 0) { delay(1000); left-- }
            }
            Text("Breathe.", fontSize = 44.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(16.dp))
            Text("Opening $label", fontSize = 22.sp)
            Text(
                "Open #${timer.opensToday() + 1} today · ${timer.remainingMs() / 60_000} min left",
                color = dim, fontSize = 15.sp,
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = { onOpen(true) }, enabled = left == 0, modifier = Modifier.fillMaxWidth()) {
                Text(if (left > 0) "Open in $left s" else if (mode == "pauseOver") "Continue to $label" else "Open $label")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        } else if (reason == null) {
            // Block screen raised but the app is allowed now (e.g. pass just granted). Step aside.
            LaunchedEffect(Unit) { onOpen(false) }
        } else {
            var escaping by remember(pkg, visit) { mutableStateOf(false) }
            val wait = remember(pkg, visit) { timer.escapeWaitSeconds() }
            var left by remember(pkg, visit) { mutableIntStateOf(wait) }
            LaunchedEffect(escaping, visit) {
                if (!escaping) return@LaunchedEffect
                while (left > 0) { delay(1000); left-- }
                timer.grantPass(5)
                onOpen(false)
            }
            Text("$label is locked", fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(12.dp))
            Text(reason, color = dim, fontSize = 17.sp)
            Spacer(Modifier.height(40.dp))
            Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Go home") }
            Spacer(Modifier.height(8.dp))
            if (!escaping) {
                OutlinedButton(onClick = { escaping = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Wait ${fmt(wait)} for 5 more minutes")
                }
                Text(
                    "Escapes today: ${timer.escapesToday()}. Each one doubles the next wait.",
                    color = dim, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(fmt(left), fontSize = 48.sp, color = MaterialTheme.colorScheme.primary)
                Text("Leaving this screen resets the wait.", color = dim, fontSize = 13.sp)
            }
        }
    }
}

private fun fmt(s: Int) = "%d:%02d".format(s / 60, s % 60)

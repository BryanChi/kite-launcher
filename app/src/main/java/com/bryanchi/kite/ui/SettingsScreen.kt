package com.bryanchi.kite.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bryanchi.kite.HomeViewModel
import com.bryanchi.kite.Kite
import com.bryanchi.kite.Overlay
import com.bryanchi.kite.data.AppEntry
import com.bryanchi.kite.data.Perms
import com.bryanchi.kite.timer.GuardService

@Composable
fun SettingsScreen(vm: HomeViewModel, byPkg: Map<String, AppEntry>) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = vm.tick
    val p = vm.perm
    val timer = Kite.timer

    Column(
        Modifier.fillMaxSize().background(Color(0xFA0E0F12)).systemBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kite", fontSize = 30.sp, fontWeight = FontWeight.Light, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.overlay = Overlay.NONE }) { Text("Done", color = Accent) }
        }

        Section("Set up")
        SetupRows(vm, SetupGroup.HOME)
        SetupRows(vm, SetupGroup.TIMER)
        SetupRows(vm, SetupGroup.PARKING)
        TextButton(onClick = { vm.replayOnboarding() }) { Text("Replay the walkthrough", color = Dim) }

        Section("Letter wheel")
        ToggleRow("Vibrate on each letter", "Small ticks as you sweep, a click when you reach the apps.", Kite.prefs.wheelHaptics) {
            Kite.prefs.wheelHaptics = it; vm.bump()
        }
        Text(
            "The wheel learns: apps you open most sit straight across from their letter, and letters you use most get more room.",
            color = Dim, fontSize = 13.sp, lineHeight = 17.sp,
        )

        Section("Social timer")
        val used = timer.usedMs() / 60_000
        Text(
            "Today: $used of ${timer.budgetMin} min used · ${timer.opensToday()} opens · ${timer.escapesToday()} escapes",
            color = Dim, fontSize = 14.sp,
        )
        timer.lockReason()?.let { Text("Locked now: $it", color = Accent, fontSize = 14.sp) }
        Spacer(Modifier.height(10.dp))

        val shown = timer.pendingBudgetMin ?: timer.budgetMin
        Stepper("Daily budget", "$shown min",
            onMinus = { timer.requestBudget(shown - 5); vm.bump() },
            onPlus = {
                if (!timer.requestBudget(shown + 5)) {
                    Toast.makeText(context, "More time starts tomorrow", Toast.LENGTH_SHORT).show()
                }
                vm.bump()
            })
        timer.pendingBudgetMin?.let {
            Text("$it min starts tomorrow. Lowering it applies right away.", color = Dim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(10.dp))
        val inBlackout = timer.inBlackout()
        fun setBlackout(on: Boolean, s: Int, e: Int) {
            if (!timer.setBlackout(on, s, e)) {
                Toast.makeText(context, "Can't change blackout while it's on", Toast.LENGTH_SHORT).show()
            }
            vm.bump()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Blackout hours", fontSize = 17.sp)
                Text("Social apps fully locked. Can't be changed while active.", color = Dim, fontSize = 13.sp)
            }
            Switch(
                checked = timer.blackoutEnabled,
                enabled = !inBlackout,
                onCheckedChange = { setBlackout(it, timer.blackoutStart, timer.blackoutEnd) },
            )
        }
        if (timer.blackoutEnabled) {
            Stepper("Starts", "%d:00".format(timer.blackoutStart),
                onMinus = { setBlackout(true, timer.blackoutStart - 1, timer.blackoutEnd) },
                onPlus = { setBlackout(true, timer.blackoutStart + 1, timer.blackoutEnd) })
            Stepper("Ends", "%d:00".format(timer.blackoutEnd),
                onMinus = { setBlackout(true, timer.blackoutStart, timer.blackoutEnd - 1) },
                onPlus = { setBlackout(true, timer.blackoutStart, timer.blackoutEnd + 1) })
        }

        Spacer(Modifier.height(12.dp))
        Text("Apps under the timer", fontSize = 17.sp)
        val pending = Kite.prefs.socialPendingRemoval
        if (vm.social.isEmpty()) Text("None yet.", color = Dim, fontSize = 14.sp)
        vm.social.sortedBy { byPkg[it]?.label?.lowercase() ?: it }.forEach { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(byPkg[pkg]?.label ?: pkg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (pkg in pending) Text("leaves tomorrow", color = Dim, fontSize = 13.sp)
                else TextButton(onClick = { vm.removeSocial(pkg) }) { Text("Remove", color = Dim) }
            }
        }
        Text("Add more: long-press any app in All apps.", color = Dim, fontSize = 13.sp)

        Spacer(Modifier.height(12.dp))
        ToggleRow(
            "Grey screen in social apps",
            if (p.secure) "The whole screen loses its colour while a timed app is open. Experimental: not yet tried on vivo."
            else "Needs a one-time command from a computer with USB debugging on:\nadb shell pm grant com.bryanchi.kite android.permission.WRITE_SECURE_SETTINGS",
            Kite.prefs.grayscale && p.secure,
            enabled = p.secure,
        ) { Kite.prefs.grayscale = it; vm.bump() }

        Section("How Kite works")
        Help("Letter wheel", "Press anywhere on home and slide. Slide along the letters, then across onto an app and let go. The app you use most is straight across from its letter. Let go on a letter to see all its apps. Slide back toward the edge of the screen to cancel.")
        Help("Pinned apps", "Your pinned apps sit in the thumb zone and never move. One \"now\" slot under them changes with the time of day.")
        Help("Parking lot", "Use the assistant gesture in any app, or Share › Park in Kite. Tap a card to jump back to that exact page when the app supports it. Cards disappear after 24 hours unless you keep them.")
        Help("Social timer", "Each open from Kite has a pause that grows (5 s, 10 s, 20 s, 30 s). When your budget runs out, or during blackout, the app is covered. The only way through is waiting on the block screen, and each wait doubles.")
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), color = Accent, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp))
}

enum class SetupGroup { HOME, TIMER, PARKING }

/** The permission checklist, shared by Settings and the first-run walkthrough. */
@Composable
fun SetupRows(vm: HomeViewModel, group: SetupGroup) {
    val context = LocalContext.current
    val p = vm.perm
    val homeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refresh() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.refresh(); GuardService.start(context)
    }
    when (group) {
        SetupGroup.HOME -> SetupRow("Make Kite your home screen", "vivo may switch back after big updates. If so, do this again.", p.home) {
            val i = Perms.homeRoleIntent(context)
            if (i != null) runCatching { homeLauncher.launch(i) }.onFailure { Perms.openHomeSettings(context) }
            else Perms.openHomeSettings(context)
        }
        SetupGroup.TIMER -> {
            SetupRow("Usage access", "Lets the social timer see which app is open.", p.usage) { Perms.openUsageAccess(context) }
            SetupRow("Display over other apps", "Lets the timer put the block screen on top of a social app.", p.overlay) { Perms.openOverlay(context) }
            if (Build.VERSION.SDK_INT >= 33) {
                SetupRow("Notifications", "Shows minutes left in the notification shade.", p.notify) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            SetupRow("Unrestricted battery", "Stops OriginOS from killing the timer in the background.", p.battery) { Perms.openBattery(context) }
            val autostartExtra: (@Composable () -> Unit)? = if (p.autostart) null else {
                {
                    TextButton(onClick = { Kite.prefs.autostartConfirmed = true; vm.refresh() }) { Text("I did it", color = Dim) }
                }
            }
            SetupRow(
                "vivo Autostart",
                "Find Kite in the list and switch it on. Android can't confirm this, so tap \"I did it\" afterwards.",
                p.autostart,
                extra = autostartExtra,
            ) { Perms.openVivoAutostart(context) }
        }
        SetupGroup.PARKING -> SetupRow(
            "Digital assistant: Kite",
            "Then long-press home (or swipe up from a bottom corner) in any app to park what's on screen. " +
                "On vivo: Settings › Apps › Default apps › Digital assistant app. Turn on \"Use screenshot\" too.",
            p.assistant,
        ) { Perms.openAssistant(context) }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp)
            Text(detail, color = Dim, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun SetupRow(
    title: String,
    detail: String,
    done: Boolean,
    extra: (@Composable () -> Unit)? = null,
    onFix: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (done) Color(0xFF5FD38D) else Accent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(detail, color = Dim, fontSize = 12.sp, lineHeight = 15.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onClick = onFix) { Text(if (done) "Open" else "Fix", color = if (done) Dim else Accent) }
            extra?.invoke()
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−", fontSize = 22.sp, color = Ink) }
        Text(value, fontSize = 16.sp, modifier = Modifier.width(70.dp), color = Ink)
        TextButton(onClick = onPlus) { Text("+", fontSize = 22.sp, color = Ink) }
    }
}

@Composable
private fun Help(title: String, body: String) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
    Text(body, color = Dim, fontSize = 13.sp, lineHeight = 17.sp)
}

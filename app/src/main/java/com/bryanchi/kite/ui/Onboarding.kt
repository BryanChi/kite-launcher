package com.bryanchi.kite.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bryanchi.kite.HomeViewModel

private const val STEPS = 5

/**
 * First-run walkthrough: what Kite is, a go at the letter wheel with your own apps, then the
 * permissions, one small group at a time. Every step can be skipped and replayed from Settings.
 */
@Composable
fun Onboarding(vm: HomeViewModel, buckets: WheelBuckets) {
    var step by remember { mutableIntStateOf(0) }
    val wheel = rememberWheel()
    var practiceNote by remember { mutableStateOf<String?>(null) }
    var practiceWins by remember { mutableIntStateOf(0) }

    BackHandler { if (step > 0) step-- }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0F12))
            // The whole page is a practice surface on the wheel step. Nothing is opened from here.
            .wheelGesture(wheel, buckets, enabled = step == 1) { r ->
                when (r) {
                    is WheelResult.Launch -> {
                        practiceWins++
                        practiceNote = "That opens ${r.app.label}."
                    }
                    is WheelResult.Letter ->
                        practiceNote = "Nearly. From ${r.letter}, slide across onto an app before you let go."
                }
            },
    ) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = 1f - 0.85f * wheel.vis.value }
                .systemBarsPadding().padding(horizontal = 28.dp, vertical = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(STEPS) { i ->
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == step) Accent else Color(0xFF3A3D44)))
                    }
                }
                TextButton(onClick = { vm.finishOnboarding() }) { Text("Skip", color = Dim) }
            }

            // No scrolling on the practice step: a scroll would swallow the wheel's sweep.
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState(), enabled = step != 1).padding(top = 28.dp)) {
                when (step) {
                    0 -> {
                        Title("Kite")
                        Body("A calmer home screen that gets you where you meant to go.")
                        Spacer(Modifier.height(20.dp))
                        Point("One swipe to any app", "Press anywhere, slide to a letter, then across onto the app.")
                        Point("Pinned apps that never move", "A short list in your thumb zone, plus one slot that follows the time of day.")
                        Point("A strict social timer", "Daily minutes, pauses that grow, and no quick way around them.")
                        Point("A parking lot", "Save what's on screen with one gesture and come back to it later.")
                    }
                    1 -> {
                        Title("The letter wheel")
                        Body(
                            "Try it here, with either thumb. Press anywhere on this page and slide.\n\n" +
                                "1. Slide along the letters. They grow beside your thumb.\n" +
                                "2. Slide across, toward the middle of the screen, onto an app. The one you use most is straight across.\n" +
                                "3. Let go to open it. Slide back to the edge to cancel.",
                        )
                        Spacer(Modifier.height(24.dp))
                        Text(
                            practiceNote ?: "Nothing opens while you practise.",
                            color = if (practiceWins > 0) Accent else Dim, fontSize = 17.sp, lineHeight = 22.sp,
                        )
                        if (practiceWins in 1..2) {
                            Text("Again, a couple more times. It gets quick.", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        } else if (practiceWins > 2) {
                            Text("You've got it.", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    2 -> {
                        Title("Make Kite home")
                        Body("So the home button and the home gesture bring you here.")
                        Spacer(Modifier.height(16.dp))
                        SetupRows(vm, SetupGroup.HOME)
                    }
                    3 -> {
                        Title("The social timer")
                        Body(
                            "Kite watches for the social apps you choose and covers them when today's minutes run out. " +
                                "It needs these to do that, and to stay alive on OriginOS. Skip this step if you don't want the timer.",
                        )
                        Spacer(Modifier.height(16.dp))
                        SetupRows(vm, SetupGroup.TIMER)
                    }
                    else -> {
                        Title("The parking lot")
                        Body("Optional. Switch on the park shortcut, then press both volume keys together in any app to save the screen and a way back to it. Sharing to Kite works without this.")
                        Spacer(Modifier.height(16.dp))
                        SetupRows(vm, SetupGroup.PARKING)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back", color = Dim) }
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (step < STEPS - 1) step++ else vm.finishOnboarding() }) {
                    Text(
                        when {
                            step == STEPS - 1 -> "Done"
                            step == 1 && practiceWins == 0 -> "Skip practice"
                            else -> "Next"
                        },
                    )
                }
            }
        }

        WheelOverlay(wheel, vm.social, showHint = false)
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Light, lineHeight = 40.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, color = Ink, fontSize = 16.sp, lineHeight = 23.sp)
}

@Composable
private fun Point(title: String, detail: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text(detail, color = Dim, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

package com.bryanchi.kite.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bryanchi.kite.Kite

/** How long a still press on empty space waits before the wheel opens by itself. */
private const val HOLD_MS = 120L

private val OnAccent = Color(0xFF1A1200)

/** A wheel wired to the phone's vibration motor. */
@Composable
fun rememberWheel(): WheelState {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wheel = remember { WheelState(scope) }
    val haptics = remember { WheelHaptics(context) }
    wheel.onHaptic = haptics::play
    return wheel
}

/**
 * Small, crisp vibrations straight from the motor. Going through the view's haptic feedback would
 * be silenced on phones that have "touch vibration" off, which is the default on many of them.
 */
class WheelHaptics(context: Context) {
    private val vibrator: Vibrator? = runCatching { context.getSystemService(Vibrator::class.java) }.getOrNull()
        ?.takeIf { it.hasVibrator() }

    fun play(kind: WheelHaptic) {
        val v = vibrator ?: return
        if (!Kite.prefs.wheelHaptics) return
        val effect = when (kind) {
            WheelHaptic.TICK, WheelHaptic.APP -> VibrationEffect.EFFECT_TICK
            WheelHaptic.LOCK -> VibrationEffect.EFFECT_CLICK
            WheelHaptic.CONFIRM -> VibrationEffect.EFFECT_HEAVY_CLICK
            WheelHaptic.CANCEL -> VibrationEffect.EFFECT_DOUBLE_CLICK
        }
        runCatching { v.vibrate(VibrationEffect.createPredefined(effect)) }
    }
}

/**
 * Opens the wheel from anywhere on this surface.
 * On empty space a short still press is enough; on top of a button or a row it takes a slide,
 * so taps and long-presses there keep working.
 */
@Composable
fun Modifier.wheelGesture(
    wheel: WheelState,
    buckets: WheelBuckets,
    enabled: Boolean,
    onResult: (WheelResult) -> Unit,
): Modifier {
    val screenDensity = LocalDensity.current
    val bars = WindowInsets.systemBars
    val currentBuckets by rememberUpdatedState(buckets)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentResult by rememberUpdatedState(onResult)
    return this.pointerInput(wheel) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!currentEnabled) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            // A child (button, app row) took the press: only a slide may open the wheel.
            val go = if (down.isConsumed) {
                awaitSlide(down.id, down.position, slop)
            } else {
                withTimeoutOrNull(HOLD_MS) { awaitSlide(down.id, down.position, slop) } ?: true
            }
            if (!go || !currentEnabled) return@awaitEachGesture
            wheel.start(
                down.position, currentBuckets, size, this.density,
                bars.getTop(screenDensity).toFloat(), bars.getBottom(screenDensity).toFloat(),
            )
            if (!wheel.active) return@awaitEachGesture
            var finished = false
            try {
                while (true) {
                    // Take moves before anything underneath sees them, so a list can't scroll under the wheel.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    if (!change.pressed) {
                        finished = true
                        wheel.release()?.let { currentResult(it) }
                        break
                    }
                    wheel.move(change.position)
                }
            } finally {
                if (!finished) wheel.cancel()
            }
        }
    }
}

/** True once the finger has slid past the touch slop; false if it lifted or something else took it. */
private suspend fun AwaitPointerEventScope.awaitSlide(id: PointerId, from: Offset, slop: Float): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return false
        if (!change.pressed || change.isConsumed) return false
        if ((change.position - from).getDistance() > slop) {
            change.consume()
            return true
        }
    }
}

@Composable
fun WheelOverlay(w: WheelState, social: Set<String>, showHint: Boolean) {
    if (!w.present) return
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = w.vis.value }) {
        Box(Modifier.fillMaxSize().background(Color(0xC4000000)))

        // A faint line where the letters sit, so the sweep has something to follow.
        Canvas(Modifier.fillMaxSize()) {
            val path = Path()
            for (i in 0..28) {
                val p = w.railPoint(i / 28f)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val fade = if (w.cancelArmed) 0.4f else 1f - 0.6f * w.lockT.value.coerceIn(0f, 1f)
            drawPath(
                path, Color.White.copy(alpha = 0.14f * fade * w.openT.value.coerceIn(0f, 1f)),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        w.apps.forEachIndexed { i, app ->
            key(app.key) {
                val selected = w.stage == 1 && w.appIdx == i
                AppBubble(w, i, selected, launching = w.launched && w.appIdx == i) {
                    val icon = app.icon
                    if (icon != null) {
                        Image(
                            icon, app.label, Modifier.size(44.dp),
                            colorFilter = if (app.pkg in social) grey else null,
                        )
                    } else {
                        Text(app.label.take(2), fontSize = 16.sp)
                    }
                }
            }
        }
        if (w.hasMore) {
            val i = w.apps.size
            key("more-${w.hovered}") {
                AppBubble(w, i, selected = w.stage == 1 && w.appIdx == i, launching = false) {
                    Text("All ${w.moreCount}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        w.letters.forEachIndexed { i, c ->
            key(c) {
                val selected = i == w.hovered && !w.cancelArmed
                Box(
                    Modifier
                        .size(WheelState.LETTER_SIZE.dp)
                        .graphicsLayer {
                            val center = w.letterCenter(i)
                            val scale = 1f + 0.9f * w.letterMag(i)
                            translationX = center.x - size.width / 2f
                            translationY = center.y - size.height / 2f
                            scaleX = scale
                            scaleY = scale
                            alpha = w.letterAlpha(i)
                        }
                        .drawBehind { if (selected) drawCircle(Accent) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        c.toString(),
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) OnAccent else Ink,
                    )
                }
            }
        }

        WheelLabel(w)

        if (showHint) {
            Text(
                "Slide along the letters. Then slide across onto an app and let go. Slide back to the edge to cancel.",
                Modifier.align(Alignment.TopCenter).systemBarsPadding().padding(top = 20.dp, start = 28.dp, end = 28.dp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center, color = Ink,
            )
        }
    }
}

@Composable
private fun AppBubble(
    w: WheelState,
    index: Int,
    selected: Boolean,
    launching: Boolean,
    content: @Composable () -> Unit,
) {
    val pop by animateFloatAsState(
        if (selected) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "pop",
    )
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(130)) }
    Box(
        Modifier
            .size(WheelState.APP_SIZE.dp)
            .graphicsLayer {
                val lock = w.lockT.value.coerceIn(0f, 1.15f)
                val center = w.appCenter(index)
                // The app you let go on swells as the wheel fades, handing off to the app opening.
                val send = if (launching) 0.45f * (1f - w.vis.value) else 0f
                val scale = (0.62f + 0.38f * lock) * (1f + 0.24f * pop + send) * (0.7f + 0.3f * appear.value)
                translationX = center.x - size.width / 2f
                translationY = center.y - size.height / 2f
                scaleX = scale
                scaleY = scale
                alpha = (0.5f + 0.5f * lock.coerceAtMost(1f)) * appear.value * if (w.cancelArmed) 0.3f else 1f
            }
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.13f))
                if (selected) drawCircle(Accent, style = Stroke(width = 2.5.dp.toPx()))
            }
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The chosen app's name, beside its icon on the side away from the hand. */
@Composable
private fun WheelLabel(w: WheelState) {
    val text = when {
        w.cancelArmed -> "Let go to cancel"
        w.stage == 1 && w.appIdx in w.apps.indices -> w.apps[w.appIdx].label
        w.stage == 1 && w.hasMore && w.appIdx == w.apps.size && w.hovered in w.letters.indices ->
            "All ${w.moreCount} under ${w.letters[w.hovered]}"
        else -> return
    }
    val boxW = with(LocalDensity.current) { 200.dp.toPx() }
    val side = w.labelSide(boxW)
    Box(
        Modifier
            .size(width = 200.dp, height = 34.dp)
            .graphicsLayer {
                val at = w.labelTopLeft(size.width, size.height)
                translationX = at.x
                translationY = at.y
            },
        contentAlignment = when {
            side < 0 -> Alignment.CenterEnd
            side > 0 -> Alignment.CenterStart
            else -> Alignment.Center
        },
    ) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (w.cancelArmed) Dim else Ink,
        )
    }
}

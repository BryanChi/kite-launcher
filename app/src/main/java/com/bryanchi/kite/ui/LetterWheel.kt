package com.bryanchi.kite.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bryanchi.kite.data.AppEntry
import com.bryanchi.kite.data.BUCKET_ORDER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

sealed interface WheelResult {
    data class Launch(val app: AppEntry) : WheelResult
    data class Letter(val letter: Char) : WheelResult
}

/** Groups apps by first letter; inside a letter, most-launched first. */
fun buildBuckets(apps: List<AppEntry>, launches: Map<String, Int>): Map<Char, List<AppEntry>> {
    val order: (Char) -> Int = { c -> BUCKET_ORDER.indexOf(c).let { if (it < 0) BUCKET_ORDER.size else it } }
    return apps.groupBy { it.bucket }
        .toSortedMap(compareBy<Char> { order(it) })
        .mapValues { (_, list) ->
            list.sortedWith(compareByDescending<AppEntry> { launches[it.pkg] ?: 0 }.thenBy { it.label.lowercase() })
        }
}

/**
 * One continuous gesture: press and slide → a fan of letters opens around your thumb.
 * Rest on a letter for a moment → that letter's apps fan out. Let go on an app to open it.
 * Let go on a letter to see all its apps in the drawer.
 */
class WheelState(private val scope: CoroutineScope) {
    var active by mutableStateOf(false)
        private set
    var stage by mutableIntStateOf(0)
        private set
    var origin by mutableStateOf(Offset.Zero)
        private set
    var letters by mutableStateOf<List<Char>>(emptyList())
        private set
    var letterPos by mutableStateOf<List<Offset>>(emptyList())
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var appPos by mutableStateOf<List<Offset>>(emptyList())
        private set
    var appOrigin by mutableStateOf(Offset.Zero)
        private set
    var hovered by mutableIntStateOf(-1)
        private set

    var onTick: () -> Unit = {}

    private var buckets: Map<Char, List<AppEntry>> = emptyMap()
    private var px = 1f
    private var width = 0f
    private var height = 0f
    /** True when the press started on the left half of the screen: assume the left thumb. */
    var leftHand by mutableStateOf(false)
        private set
    private var finger = Offset.Zero
    private var dwell: Job? = null

    fun start(at: Offset, b: Map<Char, List<AppEntry>>, size: IntSize, density: Float) {
        if (b.isEmpty()) return
        buckets = b
        px = density
        width = size.width.toFloat()
        height = size.height.toFloat()
        origin = at
        finger = at
        // Which thumb? Left half of the screen = left thumb, right half = right thumb.
        leftHand = at.x < width / 2f
        letters = b.keys.toList()
        letterPos = fan(letters.size, at, 112f * px, 60f * px)
        stage = 0
        hovered = -1
        active = true
    }

    fun move(p: Offset) {
        if (!active) return
        finger = p
        val idx = if (stage == 0) nearest(p, origin, letterPos) else nearest(p, appOrigin, appPos)
        if (idx == hovered) return
        hovered = idx
        if (idx >= 0) onTick()
        dwell?.cancel()
        if (stage == 0 && idx >= 0) {
            dwell = scope.launch {
                delay(220)
                if (active && stage == 0 && hovered == idx) openLetter(idx)
            }
        }
    }

    private fun openLetter(i: Int) {
        val list = buckets[letters[i]].orEmpty().take(12)
        apps = list
        appOrigin = finger
        appPos = fan(list.size, finger, 92f * px, 64f * px)
        stage = 1
        hovered = -1
        onTick()
    }

    fun release(): WheelResult? {
        dwell?.cancel()
        val r = when {
            !active -> null
            stage == 1 && hovered in apps.indices -> WheelResult.Launch(apps[hovered])
            stage == 0 && hovered in letters.indices -> WheelResult.Letter(letters[hovered])
            else -> null
        }
        active = false
        return r
    }

    fun cancel() {
        dwell?.cancel()
        active = false
    }

    private fun nearest(p: Offset, center: Offset, pos: List<Offset>): Int {
        if ((p - center).getDistance() < 34f * px) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        pos.forEachIndexed { i, q ->
            val d = (p - q).getDistance()
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD < 46f * px) best else -1
    }

    /**
     * Items on one or two arcs that follow the thumb's natural sweep, clamped to the screen.
     * Left thumb: first item straight up, sweeping clockwise out to the right.
     * Right thumb: mirrored, first item straight up, sweeping out to the left.
     * The first items (A, B, C… or the most-used apps) are always the easiest to reach.
     */
    private fun fan(n: Int, o: Offset, r1: Float, gap: Float): List<Offset> {
        if (n == 0) return emptyList()
        val rings = if (n <= 9) 1 else 2
        val perRing = ceil(n / rings.toDouble()).toInt()
        val startDeg = if (leftHand) 112.0 else 68.0
        val endDeg = if (leftHand) -12.0 else 192.0
        val margin = 22f * px
        return (0 until n).map { i ->
            val ring = i / perRing
            val idx = i % perRing
            val inRing = if (ring == rings - 1) n - ring * perRing else perRing
            val frac = if (inRing == 1) 0.5 else idx / (inRing - 1).toDouble()
            val deg = startDeg + frac * (endDeg - startDeg)
            val rad = Math.toRadians(deg)
            val r = r1 + ring * gap
            Offset(
                (o.x + cos(rad) * r).toFloat().coerceIn(margin, width - margin),
                (o.y - sin(rad) * r).toFloat().coerceIn(margin * 3, height - margin),
            )
        }
    }
}

@Composable
fun WheelOverlay(w: WheelState) {
    if (!w.active) return
    Box(Modifier.fillMaxSize().background(Color(0xC0000000))) {
        val title = when {
            w.stage == 1 && w.hovered in w.apps.indices -> w.apps[w.hovered].label
            w.stage == 0 && w.hovered in w.letters.indices -> w.letters[w.hovered].toString()
            w.stage == 1 -> "Slide onto an app"
            else -> "Slide to a letter"
        }
        Text(
            title,
            // Keep the label on the side away from the thumb so it isn't covered.
            Modifier.align(if (w.leftHand) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = 110.dp, start = 28.dp, end = 28.dp),
            fontSize = 30.sp,
            fontWeight = FontWeight.Light,
        )
        if (w.stage == 0) {
            w.letters.forEachIndexed { i, c ->
                val sel = i == w.hovered
                Bubble(w.letterPos[i], sel, if (sel) 50.dp else 34.dp) {
                    Text(
                        c.toString(),
                        fontSize = if (sel) 22.sp else 15.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        color = if (sel) Color(0xFF1A1200) else Ink,
                    )
                }
            }
            Bubble(w.origin, false, 12.dp) {}
        } else {
            w.apps.forEachIndexed { i, a ->
                val sel = i == w.hovered
                Bubble(w.appPos[i], sel, if (sel) 64.dp else 48.dp) {
                    val icon = a.icon
                    if (icon != null) {
                        Image(icon, a.label, Modifier.size(if (sel) 52.dp else 38.dp))
                    } else {
                        Text(a.label.take(2))
                    }
                }
            }
            Bubble(w.appOrigin, false, 12.dp) {}
        }
    }
}

@Composable
private fun Bubble(center: Offset, selected: Boolean, size: Dp, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .offset {
                val half = size.toPx() / 2
                IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt())
            }
            .size(size)
            .clip(CircleShape)
            .background(if (selected) Accent else Color(0x2EFFFFFF)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

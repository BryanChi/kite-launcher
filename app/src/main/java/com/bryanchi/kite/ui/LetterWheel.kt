package com.bryanchi.kite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.bryanchi.kite.data.AppEntry
import com.bryanchi.kite.data.BUCKET_ORDER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

sealed interface WheelResult {
    /** [center] and [sizePx] say where the icon was on screen, so the app can open out of it. */
    data class Launch(val app: AppEntry, val center: Offset, val sizePx: Float) : WheelResult
    data class Letter(val letter: Char) : WheelResult
}

enum class WheelHaptic { TICK, APP, LOCK, CONFIRM, CANCEL }

/**
 * What the wheel shows: the letters that have apps, each letter's apps (most used first),
 * and how much room each letter gets on the rail. Letters you use more get a wider slot.
 */
class WheelBuckets(
    val letters: List<Char>,
    val apps: Map<Char, List<AppEntry>>,
    val weights: List<Float>,
) {
    val isEmpty get() = letters.isEmpty()

    companion object {
        val EMPTY = WheelBuckets(emptyList(), emptyMap(), emptyList())
    }
}

/** Groups apps by first letter. [scores] are the learned, slowly fading launch counts. */
fun buildBuckets(apps: List<AppEntry>, scores: Map<String, Double>): WheelBuckets {
    if (apps.isEmpty()) return WheelBuckets.EMPTY
    val order: (Char) -> Int = { c -> BUCKET_ORDER.indexOf(c).let { if (it < 0) BUCKET_ORDER.size else it } }
    val grouped = apps.groupBy { it.bucket }
        .toSortedMap(compareBy<Char> { order(it) })
        .mapValues { (_, list) ->
            list.sortedWith(compareByDescending<AppEntry> { scores[it.pkg] ?: 0.0 }.thenBy { it.label.lowercase() })
        }
    val letters = grouped.keys.toList()
    val letterScore = letters.map { c -> grouped[c].orEmpty().sumOf { scores[it.pkg] ?: 0.0 } }
    val top = letterScore.maxOrNull() ?: 0.0
    val weights = letterScore.map { s ->
        if (top < 3.0) 1f else 1f + 0.9f * sqrt((s / top).toFloat())
    }
    return WheelBuckets(letters, grouped, weights)
}

/**
 * One continuous gesture with no timers and no small targets.
 *
 * The letters sit on an arc beside the thumb, on the side the hand doesn't cover. Only where the
 * thumb is along that arc picks the letter, so there is nothing small to hit: slide along it and
 * the letters next to the thumb grow. The chosen letter's apps wait further across the screen.
 * Slide across to pick one (the most used app is straight across), slide back to return to the
 * letters, and further back toward the edge to cancel.
 */
class WheelState(private val scope: CoroutineScope) {
    // ---- what the overlay draws ----
    var present by mutableStateOf(false)
        private set
    var active by mutableStateOf(false)
        private set
    /** 0 = sweeping letters, 1 = picking an app. */
    var stage by mutableIntStateOf(0)
        private set
    var leftHand by mutableStateOf(false)
        private set
    var letters by mutableStateOf<List<Char>>(emptyList())
        private set
    var hovered by mutableIntStateOf(-1)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    /** True when the letter has more apps than fit: one extra "all" slot is shown after [apps]. */
    var hasMore by mutableStateOf(false)
        private set
    var moreCount by mutableIntStateOf(0)
        private set
    var appIdx by mutableIntStateOf(-1)
        private set
    var cancelArmed by mutableStateOf(false)
        private set
    var launched by mutableStateOf(false)
        private set

    val vis = Animatable(0f)
    val openT = Animatable(0f)
    val lockT = Animatable(0f)

    var onHaptic: (WheelHaptic) -> Unit = {}

    // ---- geometry, in "right-hand space" (mirrored for the left hand) ----
    private var buckets = WheelBuckets.EMPTY
    private var px = 1f
    private var width = 0f
    private var height = 0f
    private var top = 0f
    private var bottom = 0f
    private var pu = 0f
    private var py = 0f
    private var rStart = 0f
    private var rBase by mutableFloatStateOf(0f)
    private var fingerPhi by mutableFloatStateOf(0f)
    private var fingerR = 0f
    private var startPhi = 0f
    private var railLo by mutableFloatStateOf(0f)
    private var railHi by mutableFloatStateOf(0f)
    private var bounds = FloatArray(0)
    private var centers = FloatArray(0)
    private var appAngles by mutableStateOf(FloatArray(0))
    private var appOff by mutableFloatStateOf(0f)
    private var appPitch = 0f
    private var travelled = 0f
    private var lastPoint = Offset.Zero
    private var lastTickAt = 0L
    private var cancelIn = 0f

    private val rail get() = RAIL * px

    fun start(at: Offset, b: WheelBuckets, size: IntSize, density: Float, safeTop: Float, safeBottom: Float) {
        if (b.isEmpty || size.width == 0) return
        buckets = b
        px = density
        width = size.width.toFloat()
        height = size.height.toFloat()
        top = safeTop + 30f * px
        bottom = height - safeBottom - 18f * px
        // Which thumb? Left half of the screen = left thumb, right half = right thumb.
        leftHand = at.x < width / 2f
        val u = if (leftHand) width - at.x else at.x
        val r = RADIUS * px
        placeArc(u, at.y.coerceIn(top + 12f * px, bottom - 12f * px), r)
        rStart = r
        rBase = r
        fingerR = hypot(pu - u, py - at.y)
        fingerPhi = atan2(pu - u, py - at.y)
        startPhi = fingerPhi
        lastPoint = at
        travelled = 0f
        // Pulling back toward the hand cancels, when there is room on that side to pull back into.
        val room = width - u
        cancelIn = if (room - 8f * px < 30f * px) Float.MAX_VALUE else min(CANCEL_IN * px, room - 8f * px)

        layoutRail()

        letters = b.letters
        stage = 0
        cancelArmed = false
        launched = false
        appIdx = -1
        hovered = -1
        apps = emptyList()
        hasMore = false
        setLetter(slotAt(fingerPhi), tick = false)

        active = true
        present = true
        scope.launch { vis.animateTo(1f, tween(110)) }
        scope.launch { openT.snapTo(0f); openT.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 420f)) }
        scope.launch { lockT.snapTo(0f) }
        onHaptic(WheelHaptic.LOCK)
    }

    fun move(p: Offset) {
        if (!active) return
        travelled += (p - lastPoint).getDistance()
        lastPoint = p
        val u = if (leftHand) width - p.x else p.x
        val phi = atan2(pu - u, py - p.y)
        val r = hypot(pu - u, py - p.y)
        val dR = r - fingerR
        val along = abs(phi - fingerPhi) * r
        // While the thumb is sweeping along the letters, let the rail drift in and out with it, so
        // only a deliberate slide across (or back) counts as one. The more the movement runs along
        // the rail rather than across it, the more of the drift is forgiven.
        if (stage == 0 && !cancelArmed && along > 0f) {
            val forgive = ((along / (abs(dR) + 0.001f) - 0.6f) / 0.8f).coerceIn(0f, 1f) * 0.9f
            rBase = (rBase + dR * forgive).coerceIn(rStart - 60f * px, rStart + 60f * px)
        }
        fingerPhi = phi
        fingerR = r
        val out = r - rBase

        if (stage == 0) {
            if (!cancelArmed && out < -cancelIn) {
                cancelArmed = true
                onHaptic(WheelHaptic.CANCEL)
            } else if (cancelArmed && out > -cancelIn + 16f * px) {
                cancelArmed = false
            }
            if (cancelArmed) return
            // Once the thumb starts heading outward, hold the letter still.
            if (out < FREEZE * px) setLetter(slotAt(phi), tick = true)
            if (out > LOCK * px && hovered >= 0) lock()
        } else {
            if (out < UNLOCK * px) unlock() else setApp(appAt(phi))
        }
    }

    fun release(): WheelResult? {
        if (!active) return null
        val slots = apps.size + if (hasMore) 1 else 0
        val r: WheelResult? = when {
            cancelArmed -> null
            // A press that never went anywhere is not a choice.
            travelled < 12f * px -> null
            stage == 1 && appIdx in apps.indices ->
                WheelResult.Launch(apps[appIdx], appCenter(appIdx), APP_SIZE * px)
            stage == 1 && appIdx == apps.size && appIdx < slots && hovered in letters.indices ->
                WheelResult.Letter(letters[hovered])
            stage == 0 && hovered in letters.indices -> WheelResult.Letter(letters[hovered])
            else -> null
        }
        launched = r is WheelResult.Launch
        if (r != null) onHaptic(WheelHaptic.CONFIRM)
        close()
        return r
    }

    fun cancel() {
        if (!active) return
        launched = false
        close()
    }

    private fun close() {
        active = false
        scope.launch {
            vis.animateTo(0f, tween(if (launched) 190 else 130))
            if (!active) present = false
        }
    }

    // ---- letters ----

    /**
     * Picks the arc the letters sit on. Its centre is always [r] away from the press, off toward the
     * hand; what varies is how far the arc is tipped. Standing upright beside the thumb is preferred
     * (sweep up and down for letters, sideways for apps), but near the top or bottom of the screen
     * the arc tips to whichever way leaves the thumb the most letters within reach.
     */
    private fun placeArc(u: Float, y: Float, r: Float) {
        val railR = r + rail
        val reach = REACH * px
        var bestScore = -Float.MAX_VALUE
        var bestPhi = Math.toRadians(88.0).toFloat()
        for (k in 0..22) {
            val deg = 55f + k * 2.5f
            val phi = Math.toRadians(deg.toDouble()).toFloat()
            pu = u + r * sin(phi)
            py = y + r * cos(phi)
            val run = visibleRun(railR, phi, 20f * px, strict = true) ?: continue
            val before = (phi - run.first) * railR
            val after = (run.second - phi) * railR
            val appsFit = APP_OFFSETS.any { visibleRun(r + it * px, phi, 34f * px, strict = true) != null }
            val score = reachable(before, after) + reachable(after, before) -
                1.2f * px * abs(deg - 88f) - if (appsFit) 0f else 300f * px
            if (score > bestScore) { bestScore = score; bestPhi = phi }
        }
        pu = u + r * sin(bestPhi)
        py = y + r * cos(bestPhi)
    }

    /** How much of one side of the rail the thumb can use. A bit more if the other side is short. */
    private fun reachable(side: Float, other: Float): Float =
        min(side, (if (other < 120f * px) REACH + 80f else REACH) * px)

    /** Gives every letter its slot on the part of the rail that is on screen and within reach. */
    private fun layoutRail() {
        val r = rStart + rail
        val run = visibleRun(r, fingerPhi, 20f * px, strict = false)!!
        val before = (fingerPhi - run.first).coerceAtLeast(0f) * r
        val after = (run.second - fingerPhi).coerceAtLeast(0f) * r
        val lo0 = max(run.first, fingerPhi - reachable(before, after) / r)
        val hi0 = max(lo0 + 0.01f, min(run.second, fingerPhi + reachable(after, before) / r))
        var lo = lo0
        var hi = hi0
        val total = buckets.weights.sum().coerceAtLeast(1f)
        val wanted = total * PITCH * px / r
        if (wanted < hi - lo) {
            // Few letters: keep the rail short and centred on the thumb.
            lo = (fingerPhi - wanted / 2f).coerceIn(lo0, hi0 - wanted)
            hi = lo + wanted
        }
        val n = buckets.letters.size
        val b = FloatArray(n + 1)
        val c = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) {
            b[i] = lo + (hi - lo) * acc / total
            acc += buckets.weights[i]
            b[i + 1] = lo + (hi - lo) * acc / total
            c[i] = (b[i] + b[i + 1]) / 2f
        }
        bounds = b
        centers = c
        railLo = lo
        railHi = hi
    }

    /**
     * The unbroken range of angles around [near] where a point at radius [r] is on screen.
     * If [near] itself is off screen: null when [strict], otherwise the closest range that is on
     * screen (or a small fan around [near] if nothing is).
     */
    private fun visibleRun(r: Float, near: Float, margin: Float, strict: Boolean): Pair<Float, Float>? {
        val step = Math.toRadians(0.5).toFloat()
        val from = Math.toRadians(-30.0).toFloat()
        val count = 420 // -30° to 180°
        fun ok(i: Int): Boolean {
            val phi = from + i * step
            val u = pu - r * sin(phi)
            val y = py - r * cos(phi)
            return u >= margin && u <= width - margin && y >= top && y <= bottom
        }
        val nearIdx = Math.round((near - from) / step).coerceIn(0, count - 1)
        var seed = -1
        if (ok(nearIdx)) {
            seed = nearIdx
        } else if (strict) {
            return null
        } else {
            for (d in 1 until count) {
                if (nearIdx + d < count && ok(nearIdx + d)) { seed = nearIdx + d; break }
                if (nearIdx - d >= 0 && ok(nearIdx - d)) { seed = nearIdx - d; break }
            }
        }
        if (seed < 0) {
            val half = Math.toRadians(20.0).toFloat()
            return (near - half) to (near + half)
        }
        var a = seed
        var z = seed
        while (a > 0 && ok(a - 1)) a--
        while (z < count - 1 && ok(z + 1)) z++
        return (from + a * step) to (from + z * step)
    }

    private fun slotAt(phi: Float): Int {
        val n = centers.size
        if (n == 0) return -1
        val h = hovered
        if (h in 0 until n) {
            // A little stickiness so a thumb resting on a boundary doesn't flicker between two letters.
            val slack = (bounds[h + 1] - bounds[h]) * 0.14f
            if (phi >= bounds[h] - slack && phi <= bounds[h + 1] + slack) return h
        }
        if (phi <= bounds[0]) return 0
        for (i in 0 until n) if (phi < bounds[i + 1]) return i
        return n - 1
    }

    private fun setLetter(i: Int, tick: Boolean) {
        if (i == hovered || i !in letters.indices) return
        hovered = i
        layoutApps(i)
        if (tick) tick(WheelHaptic.TICK)
    }

    private fun tick(kind: WheelHaptic) {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastTickAt < 22) return
        lastTickAt = now
        onHaptic(kind)
    }

    // ---- apps ----

    /** Lays the letter's apps on an arc beyond the rail, with the most used one straight out. */
    private fun layoutApps(letterIdx: Int) {
        val all = buckets.apps[buckets.letters[letterIdx]].orEmpty()
        val phiL = centers[letterIdx]
        // Sit as far out as the screen allows, so the apps clear the magnified letter.
        var off = APP_OFFSETS.last() * px
        var run = visibleRun(rStart + off, phiL, 34f * px, strict = false)!!
        for (o in APP_OFFSETS) {
            val candidate = visibleRun(rStart + o * px, phiL, 34f * px, strict = true) ?: continue
            off = o * px
            run = candidate
            break
        }
        val pitch = APP_PITCH * px / (rStart + off)
        val fit = (((run.second - run.first) / pitch).toInt() + 1).coerceAtLeast(1)
        var shown = min(all.size, min(MAX_APPS, fit))
        var more = all.size > shown
        if (more && shown > 1 && shown + 1 > fit) shown -= 1
        if (shown < 1) { shown = min(1, all.size); more = all.size > shown }
        val slots = shown + if (more) 1 else 0
        val span = (slots - 1) * pitch
        val first = (phiL - span / 2f).coerceIn(run.first, max(run.first, run.second - span))
        val slotAngles = FloatArray(slots) { first + it * pitch }
        // Nearest slot to "straight out" gets the most used app, and so on outward. "All" goes last.
        val byReach = slotAngles.indices.sortedBy { abs(slotAngles[it] - phiL) }
        val angles = FloatArray(slots)
        for (rank in 0 until slots) angles[rank] = slotAngles[byReach[rank]]
        appAngles = angles
        appOff = off
        appPitch = pitch
        apps = all.take(shown)
        hasMore = more
        moreCount = all.size
        appIdx = -1
    }

    private fun appAt(phi: Float): Int {
        val a = appAngles
        if (a.isEmpty()) return -1
        val cur = appIdx
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in a.indices) {
            var d = abs(a[i] - phi)
            if (i == cur) d -= appPitch * 0.15f
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun setApp(i: Int) {
        if (i == appIdx) return
        appIdx = i
        tick(WheelHaptic.APP)
    }

    private fun lock() {
        stage = 1
        appIdx = -1
        appIdx = appAt(fingerPhi)
        onHaptic(WheelHaptic.LOCK)
        scope.launch { lockT.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 520f)) }
    }

    private fun unlock() {
        stage = 0
        appIdx = -1
        tick(WheelHaptic.TICK)
        scope.launch { lockT.animateTo(0f, tween(120)) }
    }

    // ---- positions for drawing (read during layout/draw, so they follow the thumb every frame) ----

    private fun pos(phi: Float, r: Float): Offset {
        val u = pu - r * sin(phi)
        val y = py - r * cos(phi)
        return Offset(if (leftHand) width - u else u, y)
    }

    /** Where the magnifying "lens" sits on the rail. */
    private fun lensPhi(): Float =
        if (stage == 1 && hovered in centers.indices) centers[hovered]
        else fingerPhi.coerceIn(railLo, railHi)

    /** 0..1: how strongly letter [i] is magnified right now. */
    fun letterMag(i: Int): Float {
        if (cancelArmed || i !in centers.indices) return 0f
        val d = (centers[i] - lensPhi()) * (rBase + rail) / (LENS * px)
        return exp(-d * d)
    }

    /** 0..1: letters ripple out from the thumb when the wheel opens. */
    fun letterOpen(i: Int): Float {
        if (i !in centers.indices) return 0f
        val span = (railHi - railLo).coerceAtLeast(0.01f)
        val far = (abs(centers[i] - startPhi) / span).coerceIn(0f, 1f)
        val t = openT.value * 1.5f - far * 0.5f
        // Past 1 only the spring's own small overshoot is kept, so every letter settles on the rail.
        return if (t >= 1f) 1f + (openT.value - 1f).coerceIn(0f, 0.15f) else t.coerceAtLeast(0f)
    }

    fun letterCenter(i: Int): Offset {
        if (i !in centers.indices) return Offset.Zero
        val r = rBase + rail
        val d = (centers[i] - lensPhi()) * r / (LENS * px)
        val mag = if (cancelArmed) 0f else exp(-d * d)
        // Neighbours step aside so the magnified letter has room.
        val push = if (cancelArmed) 0f else SPREAD * px * d * exp(0.5f - 0.5f * d * d)
        val open = letterOpen(i)
        val p = pos(centers[i] + push / r, rBase + rail * open + BUMP * px * mag)
        // The rail can drift with the thumb; never let that carry a letter off the screen.
        val edge = 14f * px
        return Offset(p.x.coerceIn(edge, max(edge, width - edge)), p.y)
    }

    fun letterAlpha(i: Int): Float {
        val open = letterOpen(i).coerceIn(0f, 1f)
        if (cancelArmed) return open * 0.3f
        val keep = if (i == hovered) 1f else 1f - 0.72f * lockT.value
        return open * keep
    }

    fun appCenter(i: Int): Offset {
        val a = appAngles
        if (i !in a.indices) return Offset.Zero
        val settle = 1f - lockT.value.coerceIn(0f, 1.2f)
        return pos(a[i], rBase + appOff - 12f * px * settle)
    }

    /**
     * Which side of the chosen icon its name goes on: -1 left, +1 right (always the side away from
     * the hand), or 0 when there is no room beside it and the name goes to a fixed spot instead.
     */
    fun labelSide(boxW: Float): Int {
        val a = appAngles
        if (cancelArmed || appIdx !in a.indices) return 0
        val c = pos(a[appIdx], rStart + appOff)
        val dir = if (leftHand) 1 else -1
        val far = c.x + dir * (LABEL_GAP * px + boxW)
        return if (far < 8f * px || far > width - 8f * px) 0 else dir
    }

    /** Top-left corner for the name of the chosen app (or the "let go to cancel" note). */
    fun labelTopLeft(boxW: Float, boxH: Float): Offset {
        if (cancelArmed) {
            val c = pos(fingerPhi.coerceIn(railLo, railHi), rBase + rail)
            return Offset(
                (c.x - boxW / 2f).coerceIn(8f * px, max(8f * px, width - boxW - 8f * px)),
                (c.y - boxH / 2f).coerceIn(top, max(top, bottom - boxH)),
            )
        }
        val side = labelSide(boxW)
        if (side == 0) {
            // No room beside the icon: use the end of the screen the wheel isn't in.
            val y = if (lastPoint.y > height * 0.45f) top + 6f * px else bottom - boxH - 6f * px
            return Offset((width - boxW) / 2f, y)
        }
        val c = appCenter(appIdx)
        val x = if (side < 0) c.x - LABEL_GAP * px - boxW else c.x + LABEL_GAP * px
        return Offset(x, (c.y - boxH / 2f).coerceIn(top - 20f * px, bottom - boxH))
    }

    /** Points along the rail, for the faint guide line. */
    fun railPoint(t: Float): Offset = pos(railLo + (railHi - railLo) * t, rBase + rail * openT.value.coerceIn(0f, 1f))

    companion object {
        const val LETTER_SIZE = 30f
        const val APP_SIZE = 54f
        private const val RADIUS = 340f
        private const val REACH = 280f
        private const val RAIL = 58f
        private const val PITCH = 24f
        private const val LENS = 24f
        private const val BUMP = 26f
        private const val SPREAD = 12f
        private const val FREEZE = 12f
        private const val LOCK = 38f
        private const val UNLOCK = 12f
        private const val CANCEL_IN = 54f
        private const val LABEL_GAP = 40f
        private const val APP_PITCH = 64f
        private const val MAX_APPS = 7
        private val APP_OFFSETS = floatArrayOf(132f, 116f, 100f, 86f)
    }
}

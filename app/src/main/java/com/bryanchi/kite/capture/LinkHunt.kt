package com.bryanchi.kite.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * After a park, tries to get the exact link the way you would by hand: press the app's Share
 * button, then "Park in Kite" (or "Copy link") in the sheet that opens. Best effort, about 4 s at
 * most. If anything unexpected shows up it stops, presses Back once if it opened something, and the
 * card stays screenshot-only.
 *
 * Safety: it never touches password fields, never runs while you're typing (a focused or filled-in
 * text box, so a chat's Send button can't send a draft), and never presses anything on Kite, the
 * shade, a home screen or the lock screen.
 */
class LinkHunt(
    private val svc: CaptureService,
    private val pkg: String,
    private val isExcluded: (String) -> Boolean,
    private val done: (linked: Boolean) -> Unit,
) {
    private val handler = svc.handler
    private val deadline = SystemClock.uptimeMillis() + TOTAL_MS
    private var finished = false
    private var openedSomething = false
    /** The app's own window when we started; a sheet that opens as a new window has another id. */
    private var startWindow = -1

    /** Set while waiting for a window to change after a click. */
    var onWindow: (() -> Unit)? = null
        private set

    fun start() {
        val root = svc.rootInActiveWindow
        if (root == null || root.packageName?.toString() != pkg) return finish(false, "app isn't in front (${root?.packageName})")
        if (locked()) return finish(false, "locked")
        if (typing(root)) return finish(false, "a text box is in use; not pressing anything")
        startWindow = root.windowId
        val btn = pickShareButton(root) ?: return finish(false, "no Share button found")
        Log.d(TAG, "hunt: pressing share '${label(btn)}' id=${btn.viewIdResourceName}")
        if (!click(btn)) return finish(false, "share click failed")
        openedSomething = true
        awaitWindow { inSheet(0) }
    }

    /** Called by the service for every window change while [onWindow] is set. */
    fun windowChanged() {
        val cb = onWindow ?: return
        onWindow = null
        handler.removeCallbacksAndMessages(WAIT_TOKEN)
        handler.postDelayed({ if (!finished) cb() }, SETTLE_MS)
    }

    /** Called when ShareActivity or ClipGrabActivity has put the link on the card. */
    fun linkArrived() {
        if (finished) return
        handler.removeCallbacksAndMessages(WAIT_TOKEN)
        finish(true, "link arrived")
    }

    private fun awaitWindow(then: () -> Unit) {
        onWindow = then
        handler.postAtTime({
            if (onWindow != null && !finished) {
                Log.d(TAG, "hunt: no window change, looking anyway")
                onWindow = null
                then()
            }
        }, WAIT_TOKEN, SystemClock.uptimeMillis() + WINDOW_MS)
    }

    private fun inSheet(hop: Int) {
        if (overTime()) return
        val active = svc.rootInActiveWindow?.packageName?.toString()
        Log.d(TAG, "hunt: sheet hop=$hop active=$active")
        if (active == null || isExcluded(active) || locked()) return finish(false, "unexpected window $active", back = false)
        val roots = roots()
        // A sheet in its own window: generic words like "Copy" and "More" are safe to press there.
        // In the app's own window (some apps draw the sheet in place) only exact phrases are.
        val newWindow = svc.rootInActiveWindow?.windowId != startWindow
        val sheet = listOfNotNull(svc.rootInActiveWindow)

        find(roots) { t -> t.startsWith("park in k") }?.let { node ->
            Log.d(TAG, "hunt: pressing 'Park in Kite'")
            if (click(node)) { openedSomething = false; return waitForLink() }
        }
        find(sheet) { t -> isCopyLink(t, newWindow) }?.let { node ->
            Log.d(TAG, "hunt: pressing copy '${label(node)}'")
            if (click(node)) {
                handler.postDelayed({
                    if (finished) return@postDelayed
                    openedSomething = false
                    Log.d(TAG, "hunt: reading the clipboard")
                    runCatching {
                        svc.startActivity(
                            Intent(svc, ClipGrabActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                        )
                    }.onFailure { Log.d(TAG, "hunt: couldn't start ClipGrab: $it") }
                    waitForLink()
                }, COPY_SETTLE_MS)
                return
            }
        }
        if (hop < MAX_HOPS && newWindow) {
            find(sheet, ::isMore)?.let { node ->
                Log.d(TAG, "hunt: pressing '${label(node)}' for more share options")
                if (click(node)) return awaitWindow { inSheet(hop + 1) }
            }
        }
        finish(false, "nothing to press in the sheet")
    }

    private fun waitForLink() {
        val left = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(800L)
        handler.postAtTime({ if (!finished) finish(false, "link never arrived", back = false) }, WAIT_TOKEN, SystemClock.uptimeMillis() + left)
    }

    private fun overTime(): Boolean {
        if (SystemClock.uptimeMillis() <= deadline) return false
        finish(false, "out of time")
        return true
    }

    private fun finish(linked: Boolean, why: String, back: Boolean = true) {
        if (finished) return
        finished = true
        onWindow = null
        handler.removeCallbacksAndMessages(WAIT_TOKEN)
        Log.d(TAG, "hunt: done linked=$linked ($why)")
        if (!linked && back && openedSomething) {
            val active = svc.rootInActiveWindow?.packageName?.toString()
            if (active != null && !isExcluded(active)) {
                Log.d(TAG, "hunt: pressing Back to close what we opened")
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }
        done(linked)
    }

    // ---------------- finding things ----------------

    private fun locked() = svc.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun roots(): List<AccessibilityNodeInfo> {
        val active = svc.rootInActiveWindow
        val others = runCatching { svc.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .filter { r -> r.packageName?.toString()?.let { !isExcluded(it) } == true }
        return listOfNotNull(active) + others.filter { it != active }
    }

    private fun typing(root: AccessibilityNodeInfo): Boolean {
        var found = false
        walk(root) { n ->
            if (n.isPassword || (n.isEditable && (n.isFocused || !n.text.isNullOrBlank()))) found = true
            !found
        }
        return found
    }

    private fun pickShareButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = svc.resources.displayMetrics
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val minSide = (20 * dm.density).toInt()
        val cands = ArrayList<Pair<AccessibilityNodeInfo, Rect>>()
        walk(root) { n ->
            if (n.isVisibleToUser && !n.isPassword && isShare(n)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() >= minSide && r.height() >= minSide && Rect.intersects(r, screen) && screen.contains(r.centerX(), r.centerY())) {
                    cands += n to r
                }
            }
            true
        }
        Log.d(TAG, "hunt: ${cands.size} share candidates: " + cands.joinToString { "'${label(it.first)}'@${it.second.centerY()}" })
        if (cands.size <= 1) return cands.firstOrNull()?.first
        // One Share button per post in a feed: take the post filling most of the screen, i.e. the
        // button closest below the middle of the screen; failing that, the closest above it.
        val mid = screen.centerY()
        return cands.minByOrNull { (_, r) -> if (r.centerY() >= mid) r.centerY() - mid else 100_000 + (mid - r.centerY()) }?.first
    }

    private fun isShare(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName.orEmpty().substringAfter(":id/").lowercase()
        if ("share" in id || "send" in id) return true
        val t = label(n)
        if (t.isEmpty() || t.length > 40) return false
        return SHARE_WORDS.any { it in t }
    }

    private fun isCopyLink(t: String, loose: Boolean) =
        COPY_LINK_WORDS.any { it in t } || (loose && (t == "copy" || t == "コピー" || t == "复制" || t == "copiar"))

    private fun isMore(t: String) =
        t == "more" || t == "その他" || t == "他のアプリ" || t.startsWith("share via") || t == "更多" || t == "más" ||
            t == "more options" || t == "share to…" || t == "share to..."

    private fun find(roots: List<AccessibilityNodeInfo>, match: (String) -> Boolean): AccessibilityNodeInfo? {
        for (r in roots) {
            var hit: AccessibilityNodeInfo? = null
            walk(r) { n ->
                if (hit == null && n.isVisibleToUser && !n.isPassword && !n.isEditable) {
                    val t = label(n)
                    if (t.isNotEmpty() && t.length <= 40 && match(t)) hit = n
                }
                hit == null
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun label(n: AccessibilityNodeInfo) =
        (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()
            .ifEmpty { n.text?.toString()?.trim()?.lowercase().orEmpty() }

    /** Clicks the node, or its nearest clickable ancestor. */
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isPassword) return false
            if (cur.isClickable && cur.isEnabled) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
            depth++
        }
        return false
    }

    /** Depth-first walk; [visit] returns false to stop. */
    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        var count = 0
        fun go(n: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (n == null || depth > 45 || ++count > 3000) return true
            if (!visit(n)) return false
            for (i in 0 until n.childCount) if (!go(runCatching { n.getChild(i) }.getOrNull(), depth + 1)) return false
            return true
        }
        go(root, 0)
    }

    companion object {
        private const val TAG = "KiteCapture"
        private val WAIT_TOKEN = Any()
        private const val TOTAL_MS = 4000L
        private const val WINDOW_MS = 1200L
        private const val SETTLE_MS = 200L
        private const val COPY_SETTLE_MS = 250L
        private const val MAX_HOPS = 2
        private val SHARE_WORDS = listOf("share", "send", "共有", "シェア", "送信", "分享", "compartir")
        private val COPY_LINK_WORDS = listOf("copy link", "copy url", "リンクをコピー", "リンクのコピー", "复制链接", "copiar enlace")
    }
}

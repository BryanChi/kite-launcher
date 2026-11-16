package com.bryanchi.kite.ui

import android.app.ActivityOptions
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bryanchi.kite.HomeViewModel
import com.bryanchi.kite.Kite
import com.bryanchi.kite.Overlay
import com.bryanchi.kite.data.AppEntry
import com.bryanchi.kite.data.Card
import com.bryanchi.kite.data.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val apps by vm.apps.collectAsState()
    val cards by vm.cards.collectAsState()
    val view = LocalView.current
    val wheel = rememberWheel()
    val thumbZonePx = with(LocalDensity.current) { 200.dp.toPx() }
    val byPkg = remember(apps) { apps.associateBy { it.pkg } }
    // Re-read what the wheel has learned every time home comes back.
    val buckets = remember(apps, vm.tick) { buildBuckets(apps, Kite.prefs.wheelScores()) }
    val homeIsOnTop = vm.overlay == Overlay.NONE && vm.cardDetail == null && vm.menuFor == null && !vm.onboarding

    BackHandler(enabled = !vm.onboarding && (vm.overlay != Overlay.NONE || vm.cardDetail != null || vm.menuFor != null)) { vm.back() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0x44000000), Color(0xAA000000))))
            // Keep Android's back swipe from stealing a wheel that starts at the edge, in the thumb zone.
            .systemGestureExclusion { c ->
                Rect(0f, c.size.height - thumbZonePx, c.size.width.toFloat(), c.size.height.toFloat())
            }
            .wheelGesture(wheel, buckets, enabled = homeIsOnTop) { r ->
                when (r) {
                    is WheelResult.Launch -> {
                        vm.wheelUsed()
                        // Open the app out of the icon the thumb let go on.
                        val half = (r.sizePx / 2f).toInt()
                        val x = r.center.x.toInt() - half
                        val y = r.center.y.toInt() - half
                        val options = runCatching {
                            ActivityOptions.makeScaleUpAnimation(view, x, y, half * 2, half * 2).toBundle()
                        }.getOrNull()
                        vm.launch(r.app, android.graphics.Rect(x, y, x + half * 2, y + half * 2), options)
                    }
                    is WheelResult.Letter -> { vm.wheelUsed(); vm.openDrawer(r.letter) }
                }
            },
    ) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = 1f - 0.8f * wheel.vis.value }
                .systemBarsPadding().padding(horizontal = 24.dp),
        ) {
            TopBar(onSettings = { vm.overlay = Overlay.SETTINGS }, needsSetup = !vm.perm.home || !vm.perm.timerReady)
            SocialStrip(vm, byPkg)
            if (cards.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                ParkingRow(cards, onOpen = vm::openCard, onLong = { vm.cardDetail = it })
            }
            Spacer(Modifier.weight(1f))

            if (vm.wheelUses < 5) {
                Text(
                    "Press anywhere and slide to open any app: along the letters to the one you want, then across onto the app.",
                    color = Dim, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            val pinnedApps = vm.pinned.mapNotNull { byPkg[it] }
            if (pinnedApps.isEmpty()) {
                Text(
                    "Long-press any app in All apps to pin it here. Pinned apps never move.",
                    color = Dim, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            pinnedApps.forEach { app ->
                AppRow(app, big = true, onClick = { vm.launch(app) }, onLong = { vm.menuFor = app })
            }
            vm.nowPkg?.let { byPkg[it] }?.let { app ->
                AppRow(app, big = true, tag = "now", onClick = { vm.launch(app) }, onLong = { vm.menuFor = app })
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { vm.openDrawer() }) { Text("All apps", color = Ink, fontSize = 16.sp) }
                TextButton(onClick = { vm.openDrawer(search = true) }) { Text("Search", color = Dim, fontSize = 16.sp) }
            }
        }

        WheelOverlay(wheel, vm.social, showHint = vm.wheelUses < 3)

        if (vm.overlay == Overlay.ALL_APPS) AllAppsScreen(vm, apps)
        if (vm.overlay == Overlay.SETTINGS) SettingsScreen(vm, byPkg)
        vm.cardDetail?.let { CardDetail(vm, it) }
        vm.menuFor?.let { AppMenu(vm, it) }
        if (vm.onboarding) Onboarding(vm, buckets)
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit, needsSetup: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); delay(10_000) }
    }
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(DateFormat.getTimeFormat(context).format(now), fontSize = 52.sp, fontWeight = FontWeight.Light)
            Text(DateFormat.format("EEEE, MMMM d", now).toString(), color = Dim, fontSize = 16.sp)
        }
        TextButton(onClick = onSettings) {
            Text(if (needsSetup) "Finish setup" else "⋯", color = if (needsSetup) Accent else Ink, fontSize = if (needsSetup) 15.sp else 22.sp)
        }
    }
}

/** Social apps live far from the thumb, greyed out, with today's time left. */
@Composable
private fun SocialStrip(vm: HomeViewModel, byPkg: Map<String, AppEntry>) {
    val socialApps = vm.social.mapNotNull { byPkg[it] }.sortedBy { it.label.lowercase() }
    if (socialApps.isEmpty()) return
    @Suppress("UNUSED_VARIABLE") val t = vm.tick
    val timer = Kite.timer
    val reason = timer.lockReason()
    val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Spacer(Modifier.height(20.dp))
    Text(
        reason ?: "Social · ${timer.remainingMs() / 60_000} min left today",
        color = if (reason != null) Accent else Dim, fontSize = 13.sp,
    )
    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(socialApps, key = { it.key }) { app ->
            val icon = app.icon
            Box(
                Modifier.size(34.dp).alpha(0.55f).clip(RoundedCornerShape(10.dp))
                    .combinedClickableCompat(onClick = { vm.launch(app) }, onLong = { vm.menuFor = app }),
            ) {
                if (icon != null) Image(icon, app.label, Modifier.fillMaxSize(), colorFilter = grey)
                else Text(app.label.take(1))
            }
        }
    }
}

/** Stops touches on a full-screen sheet from reaching the home screen underneath it. */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) {}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLong)

@Composable
fun AppRow(app: AppEntry, big: Boolean, tag: String? = null, grey: Boolean = false, onClick: () -> Unit, onLong: () -> Unit) {
    val greyFilter = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick, onLong)
            .padding(vertical = if (big) 9.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = app.icon
        if (icon != null) {
            Image(
                icon, null, Modifier.size(if (big) 38.dp else 34.dp).alpha(if (grey) 0.6f else 1f),
                colorFilter = if (grey) greyFilter else null,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            app.label, fontSize = if (big) 22.sp else 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (tag != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                tag, fontSize = 11.sp, color = Color(0xFF1A1200),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Accent).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

// ---------------- Parking lot ----------------

@Composable
private fun ParkingRow(cards: List<Card>, onOpen: (Card) -> Unit, onLong: (Card) -> Unit) {
    Text("Parking lot", color = Dim, fontSize = 13.sp)
    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(cards, key = { it.id }) { card -> ParkedCard(card, { onOpen(card) }, { onLong(card) }) }
    }
}

@Composable
private fun cardImage(card: Card, sample: Int): ImageBitmap? {
    val img by produceState<ImageBitmap?>(null, card.id, sample) {
        value = withContext(Dispatchers.IO) {
            Kite.parking.imageFile(card)?.let { f ->
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        }
    }
    return img
}

@Composable
private fun ParkedCard(card: Card, onClick: () -> Unit, onLong: () -> Unit) {
    val img = cardImage(card, 4)
    Box(
        Modifier.size(width = 112.dp, height = 150.dp).clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF22252B))
            .combinedClickableCompat(onClick, onLong),
    ) {
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(
                (if (card.pinned) "📌 " else "") + card.appLabel,
                fontSize = 10.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(card.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun CardDetail(vm: HomeViewModel, card: Card) {
    val img = cardImage(card, 1)
    Column(
        Modifier.fillMaxSize().background(Color(0xF20E0F12)).blockTouches().systemBarsPadding().padding(20.dp),
    ) {
        Text(card.appLabel, color = Accent, fontSize = 14.sp)
        Text(card.title, fontSize = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        card.webUri?.let { Text(it, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF22252B))) {
            if (img != null) {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(
                    card.snippet.ifBlank { "No picture saved" }, color = Dim, fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { Kite.parking.remove(card.id); vm.cardDetail = null }) { Text("Done, remove", color = Dim) }
            TextButton(onClick = { Kite.parking.setPinned(card.id, !card.pinned); vm.cardDetail = null }) {
                Text(if (card.pinned) "Unpin" else "Keep", color = Ink)
            }
            TextButton(onClick = { vm.cardDetail = null; vm.openCard(card) }) { Text("Open", color = Accent) }
        }
    }
}

// ---------------- All apps + search ----------------

@Composable
private fun AllAppsScreen(vm: HomeViewModel, apps: List<AppEntry>) {
    var query by remember { mutableStateOf("") }
    val letter = vm.drawerLetter
    val focus = remember { FocusRequester() }
    val filtered = remember(query, apps, letter) {
        val q = query.trim().lowercase()
        when {
            q.isNotEmpty() -> apps.filter { it.label.lowercase().contains(q) }
                .sortedBy { if (it.label.lowercase().startsWith(q)) 0 else 1 }
            letter != null -> apps.filter { it.bucket == letter }
            else -> apps
        }
    }
    val cards by vm.cards.collectAsState()
    val parked = remember(query, cards) {
        val q = query.trim().lowercase()
        if (q.length < 2) emptyList()
        else cards.filter { c -> listOf(c.title, c.snippet, c.appLabel, c.webUri.orEmpty()).any { it.lowercase().contains(q) } }
    }
    // Only "Search" brings the keyboard up. "All apps" and the wheel just show the list.
    LaunchedEffect(vm.drawerSearch) { if (vm.drawerSearch) runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(Color(0xF20E0F12)).blockTouches().systemBarsPadding().imePadding()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), reverseLayout = true) {
            // reverseLayout keeps the best matches next to the keyboard, under your thumb
            items(filtered, key = { it.key }) { app ->
                AppRow(app, big = false, tag = if (app.pkg in vm.social) "timer" else null, grey = app.pkg in vm.social,
                    onClick = { vm.launch(app) }, onLong = { vm.menuFor = app })
            }
            // Parked things that match the search (their saved on-screen text is searchable too).
            items(parked, key = { "card-" + it.id }) { card ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .combinedClickableCompat(onClick = { vm.closeAll(); vm.openCard(card) }, onLong = { vm.cardDetail = card })
                        .padding(vertical = 8.dp),
                ) {
                    Text("Parked · ${card.appLabel}", color = Accent, fontSize = 12.sp)
                    Text(card.title, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (letter != null && query.isEmpty()) {
                TextButton(onClick = { vm.drawerLetter = null }) { Text("$letter  ✕", color = Accent, fontSize = 16.sp) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Search apps and parked things") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { filtered.firstOrNull()?.let { vm.launch(it) } }),
            )
        }
    }
}

@Composable
private fun AppMenu(vm: HomeViewModel, app: AppEntry) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val t = vm.tick
    val pinned = app.pkg in vm.pinned
    val social = app.pkg in vm.social
    val leaving = app.pkg in Kite.prefs.socialPendingRemoval
    AlertDialog(
        onDismissRequest = { vm.menuFor = null },
        confirmButton = { TextButton(onClick = { vm.menuFor = null }) { Text("Close") } },
        title = { Text(app.label) },
        text = {
            Column {
                MenuItem(if (pinned) "Unpin from home" else "Pin to home") { vm.togglePin(app.pkg); vm.menuFor = null }
                if (pinned) {
                    MenuItem("Move up") { vm.movePin(app.pkg, -1) }
                    MenuItem("Move down") { vm.movePin(app.pkg, +1) }
                }
                when {
                    !social -> MenuItem("Put under social timer") { vm.addSocial(app.pkg) }
                    leaving -> MenuItem("Leaves the timer tomorrow", enabled = false) {}
                    else -> MenuItem("Remove from timer (from tomorrow)") { vm.removeSocial(app.pkg) }
                }
                MenuItem("App info") { Perms.openAppDetails(context, app.pkg); vm.menuFor = null }
                MenuItem("Uninstall") { vm.uninstall(app.pkg); vm.menuFor = null }
            }
        },
    )
}

@Composable
private fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.fillMaxWidth(), color = if (enabled) Ink else Dim)
    }
}

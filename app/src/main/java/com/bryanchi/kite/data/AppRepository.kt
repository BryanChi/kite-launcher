package com.bryanchi.kite.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

data class AppEntry(
    val key: String,
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val icon: ImageBitmap?,
) {
    /** Letter-wheel bucket: A-Z, kana rows (あ か さ …) for Japanese names, '#' for digits, '•' for the rest. */
    val bucket: Char by lazy { bucketOf(label) }
}

/** Wheel order: A-Z, then the kana rows, then digits, then everything else. */
val BUCKET_ORDER: List<Char> = ('A'..'Z').toList() + "あかさたなはまやらわ".toList() + listOf('#', '•')

private val KANA_ROWS = listOf(
    'ぁ' to 'あ', 'か' to 'か', 'さ' to 'さ', 'た' to 'た', 'な' to 'な',
    'は' to 'は', 'ま' to 'ま', 'ゃ' to 'や', 'ら' to 'ら', 'ゎ' to 'わ',
)

fun bucketOf(label: String): Char {
    var c = Normalizer.normalize(label, Normalizer.Form.NFKC).trim().firstOrNull()?.uppercaseChar() ?: return '•'
    if (c in 'A'..'Z') return c
    if (c.isDigit()) return '#'
    if (c in '\u30A1'..'\u30F6') c = c - 0x60 // katakana → hiragana
    if (c in '\u3041'..'\u3096') {
        if (c == 'ゔ') return 'あ'
        return KANA_ROWS.last { (start, _) -> c >= start }.second
    }
    return '•'
}

class AppRepository(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val iconCache = HashMap<String, ImageBitmap?>()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart
                synchronized(iconCache) { iconCache.keys.removeAll { it.startsWith("$pkg/") } }
                reloadAsync()
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
        reloadAsync()
    }

    fun reloadAsync() {
        scope.launch { reload() }
    }

    suspend fun reload() = mutex.withLock {
        val iconPx = (48 * context.resources.displayMetrics.density).toInt()
        val list = userManager.userProfiles.flatMap { user ->
            launcherApps.getActivityList(null, user).map { info ->
                val cn = info.componentName
                val key = "${cn.flattenToString()}#${user.hashCode()}"
                val icon = synchronized(iconCache) { iconCache[key] } ?: runCatching {
                    info.getBadgedIcon(0).toBitmap(iconPx, iconPx).asImageBitmap()
                }.getOrNull().also { synchronized(iconCache) { iconCache[key] = it } }
                AppEntry(key, cn.packageName, info.label.toString(), cn, user, icon)
            }
        }.filter { it.pkg != context.packageName }
            .sortedBy { it.label.lowercase() }
        _apps.value = list
    }

    fun find(pkg: String): AppEntry? = _apps.value.firstOrNull { it.pkg == pkg }

    /** [from] and [options] let the app open out of the icon that was touched. */
    fun launch(app: AppEntry, from: Rect? = null, options: Bundle? = null) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, from, options) }
            .onFailure { launchPackage(context, app.pkg) }
    }

    companion object {
        fun launchPackage(context: Context, pkg: String): Boolean {
            val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(i) }.isSuccess
        }

        /**
         * Brings the app's existing task to the front as it was left, rather than restarting it.
         * Starts the app fresh only if it has no task.
         */
        fun resumeTask(context: Context, pkg: String): Boolean {
            val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            return runCatching { context.startActivity(i) }.isSuccess
        }

        fun label(context: Context, pkg: String): String = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}

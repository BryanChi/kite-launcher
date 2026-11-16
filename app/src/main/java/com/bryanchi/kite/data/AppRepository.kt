package com.bryanchi.kite.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
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
    /** Letter-wheel bucket: A-Z, '#' for digits, '•' for everything else (e.g. Japanese labels). */
    val bucket: Char by lazy {
        val c = Normalizer.normalize(label, Normalizer.Form.NFKC).trim().firstOrNull()?.uppercaseChar() ?: '•'
        when {
            c in 'A'..'Z' -> c
            c.isDigit() -> '#'
            else -> '•'
        }
    }
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

    fun launch(app: AppEntry) {
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, null) }
            .onFailure { launchPackage(context, app.pkg) }
    }

    companion object {
        fun launchPackage(context: Context, pkg: String): Boolean {
            val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(i) }.isSuccess
        }

        fun label(context: Context, pkg: String): String = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}

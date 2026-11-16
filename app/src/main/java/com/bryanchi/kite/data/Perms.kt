package com.bryanchi.kite.data

import android.Manifest
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** Everything the setup checklist needs to check or open. */
object Perms {
    fun isDefaultHome(c: Context) = role(c, RoleManager.ROLE_HOME)
    fun isAssistant(c: Context) = role(c, RoleManager.ROLE_ASSISTANT)

    private fun role(c: Context, r: String) = runCatching {
        val rm = c.getSystemService(RoleManager::class.java)
        rm.isRoleAvailable(r) && rm.isRoleHeld(r)
    }.getOrDefault(false)

    fun homeRoleIntent(c: Context): Intent? = runCatching {
        c.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME)
    }.getOrNull()

    fun hasUsageAccess(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canOverlay(c: Context) = Settings.canDrawOverlays(c)

    fun ignoresBattery(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun canNotify(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Only grantable from a computer: adb shell pm grant com.bryanchi.kite android.permission.WRITE_SECURE_SETTINGS */
    fun canWriteSecure(c: Context) =
        c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun pkgUri(c: Context) = Uri.parse("package:${c.packageName}")

    fun openUsageAccess(c: Context) = start(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    fun openOverlay(c: Context) = start(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(c)))
    fun openBattery(c: Context) = start(
        c,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(c)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    fun openHomeSettings(c: Context) = start(c, Intent(Settings.ACTION_HOME_SETTINGS))
    fun openAssistant(c: Context) = start(
        c,
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    fun openAccessibility(c: Context) = start(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppDetails(c: Context, pkg: String = c.packageName) =
        start(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))

    /** vivo / iQOO "Autostart" (background startup) screens differ by OS version; try the known ones. */
    fun openVivoAutostart(c: Context) = start(
        c,
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(c)),
    )

    fun start(c: Context, vararg intents: Intent): Boolean {
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                c.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}

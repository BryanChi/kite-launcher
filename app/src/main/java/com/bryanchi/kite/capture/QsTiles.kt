package com.bryanchi.kite.capture

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.DrawableRes
import com.bryanchi.kite.R

/** Asks Android to add one of Kite's Quick Settings tiles, with the system's own prompt. */
object QsTiles {
    /** Android 13+ can show the add-tile prompt; older versions need the tile editor. */
    val canPrompt get() = Build.VERSION.SDK_INT >= 33

    fun requestAdd(context: Context, cls: Class<*>, label: String, @DrawableRes icon: Int) {
        if (Build.VERSION.SDK_INT < 33) return
        val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
        runCatching {
            sbm.requestAddTileService(
                ComponentName(context, cls), label, Icon.createWithResource(context, icon), context.mainExecutor,
            ) { result ->
                Log.d("KiteCapture", "add tile $label: result=$result")
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "$label tile added"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "$label is already in Quick Settings"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                    else -> "Couldn't add it here. Add \"$label\" from the Quick Settings tile editor. " +
                        "If it isn't listed, restart the phone once."
                }
                msg?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
        }.onFailure {
            Log.d("KiteCapture", "add tile failed: $it")
            Toast.makeText(context, "Add \"$label\" from the Quick Settings tile editor", Toast.LENGTH_LONG).show()
        }
    }

    fun addPark(c: Context) = requestAdd(c, CaptureTileService::class.java, c.getString(R.string.capture_tile_label), R.drawable.ic_stat_kite)
    fun addDim(c: Context) = requestAdd(c, DimTileService::class.java, c.getString(R.string.dim_tile_label), R.drawable.ic_moon)
}

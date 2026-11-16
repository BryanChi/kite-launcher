package com.bryanchi.kite.capture

import android.app.Activity
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.bryanchi.kite.data.Perms

/** Quick Settings tile: closes the shade, then parks the app underneath it. */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = if (CaptureService.running) null else "Turn on in Kite"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!CaptureService.running) {
            Toast.makeText(this, "Turn on the park shortcut in Kite's settings first", Toast.LENGTH_LONG).show()
            return
        }
        CaptureService.closeShade()
        CaptureService.capture(delayMs = 650)
    }
}

/**
 * "Park this screen" as an app shortcut, so anything that can start a shortcut (vivo's quick-launch
 * gestures, a home-screen shortcut) can park. It has no window of its own: it gets out of the way
 * and parks whatever was underneath.
 */
class CaptureShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CaptureService.capture(delayMs = 450)) {
            Toast.makeText(this, "Turn on the park shortcut in Kite first", Toast.LENGTH_LONG).show()
            Perms.openAccessibility(this)
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

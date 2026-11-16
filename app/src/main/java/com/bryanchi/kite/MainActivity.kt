package com.bryanchi.kite

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bryanchi.kite.timer.GuardService
import com.bryanchi.kite.ui.HomeScreen
import com.bryanchi.kite.ui.KiteTheme

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { KiteTheme { HomeScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
        GuardService.start(this)
    }

    /** Pressing Home while already home closes any open sheet. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.closeAll()
    }
}

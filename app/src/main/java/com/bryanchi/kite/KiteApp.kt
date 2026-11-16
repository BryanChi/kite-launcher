package com.bryanchi.kite

import android.app.Application
import com.bryanchi.kite.data.AppRepository
import com.bryanchi.kite.data.ParkingLot
import com.bryanchi.kite.data.Prefs
import com.bryanchi.kite.timer.SocialTimer

/** Process-wide singletons. Services and activities all run in this one process. */
object Kite {
    lateinit var prefs: Prefs
    lateinit var timer: SocialTimer
    lateinit var apps: AppRepository
    lateinit var parking: ParkingLot
}

class KiteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Kite.prefs = Prefs(this)
        Kite.timer = SocialTimer(Kite.prefs.sp)
        Kite.apps = AppRepository(this)
        Kite.parking = ParkingLot(this)
    }
}

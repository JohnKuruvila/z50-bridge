package io.github.johnkuruvila.z50bridge

import android.app.Application

class Z50App : Application() {
    lateinit var controller: BridgeController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = BridgeController(this)
        controller.refreshStaged()
    }
}

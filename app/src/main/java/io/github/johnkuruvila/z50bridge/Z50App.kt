package io.github.johnkuruvila.z50bridge

import android.app.Application
import io.github.johnkuruvila.z50bridge.car.CarAlerts

class Z50App : Application() {
    lateinit var controller: BridgeController
        private set

    lateinit var carAlerts: CarAlerts
        private set

    override fun onCreate() {
        super.onCreate()
        controller = BridgeController(this)
        controller.refreshStaged()
        carAlerts = CarAlerts(this, controller).also { it.start() }
    }
}

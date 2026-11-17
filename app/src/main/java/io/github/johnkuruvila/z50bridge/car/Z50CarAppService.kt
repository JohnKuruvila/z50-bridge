package io.github.johnkuruvila.z50bridge.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.johnkuruvila.z50bridge.BridgeController
import io.github.johnkuruvila.z50bridge.Z50App
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A real launcher destination for Android Auto, sharing the phone's controller. */
class Z50CarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.Builder(this)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
        .build()

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            Z50CarScreen(carContext, (application as Z50App).controller)
    }
}

internal class Z50CarScreen(
    carContext: CarContext,
    private val controller: BridgeController,
) : Screen(carContext) {
    private fun snapshot(): CarDashboard {
        val state = controller.state.value
        val alerts = (carContext.applicationContext as Z50App).carAlerts
        return carDashboard(state, controller.carSignals(state), alerts.filesAtDriveStart)
    }

    private var dashboard = snapshot()

    init {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val next = snapshot()
                    if (next != dashboard) {
                        dashboard = next
                        invalidate()
                    }
                    delay(2_000L)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        dashboard = snapshot()
        val pane = Pane.Builder()
            .addRow(row("Status", dashboard.status, dashboard.activity))
            .addRow(row("Progress", dashboard.progress, dashboard.queue))
            .build()
        return PaneTemplate.Builder(pane)
            .setTitle("Z50 Bridge")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun row(title: String, text: String, detail: String? = null): Row = Row.Builder()
        .setTitle(title)
        .addText(text)
        .apply { detail?.let { addText(it) } }
        .build()
}

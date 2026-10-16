package io.github.johnkuruvila.z50bridge.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.johnkuruvila.z50bridge.BridgePhase
import io.github.johnkuruvila.z50bridge.MainActivity
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.service.BridgeService
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.theme.BrandDarkColors
import io.github.johnkuruvila.z50bridge.ui.theme.BrandLightColors
import io.github.johnkuruvila.z50bridge.ui.theme.DarkSignals
import io.github.johnkuruvila.z50bridge.ui.theme.LightSignals

/**
 * Home-screen widget in the app's pipeline language: staged clip count as the
 * hero number, a status line with the pipeline signal color, and Sync / Offload
 * (or Stop while a transfer runs).
 */
class Z50Widget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme(colors = ColorProviders(light = BrandLightColors, dark = BrandDarkColors)) {
                WidgetContent(context)
            }
        }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        val prefs = currentState<Preferences>()
        val files = prefs[FILES] ?: 0
        val bytes = prefs[BYTES] ?: 0L
        val phase = prefs[PHASE]?.let { name -> BridgePhase.entries.firstOrNull { it.name == name } } ?: BridgePhase.Idle
        val cameraOnline = prefs[CAMERA] ?: false
        val homelabOnline = prefs[HOMELAB] ?: false
        val compact = LocalSize.current.width < MEDIUM.width
        val transferring = phase == BridgePhase.Syncing || phase == BridgePhase.Offloading

        val (statusText, statusColor) = when {
            phase == BridgePhase.Syncing -> context.getString(R.string.widget_status_syncing) to SYNC
            phase == BridgePhase.Offloading -> context.getString(R.string.widget_status_offloading) to OFFLOAD
            cameraOnline -> context.getString(R.string.widget_status_camera) to ONLINE
            homelabOnline -> context.getString(R.string.widget_status_home) to ONLINE
            phase == BridgePhase.Watching -> context.getString(R.string.widget_status_watching) to IDLE
            else -> context.getString(R.string.widget_status_idle) to IDLE
        }

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(24.dp)
                .background(GlanceTheme.colors.surface)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
                Box(
                    modifier = GlanceModifier
                        .size(8.dp)
                        .cornerRadius(4.dp)
                        .background(statusColor),
                ) {}
                Spacer(GlanceModifier.width(8.dp))
                Text(
                    text = statusText,
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
            Spacer(GlanceModifier.defaultWeight())
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = files.toString(),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 40.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = context.resources.getQuantityString(R.plurals.widget_clips_label, files),
                    modifier = GlanceModifier.padding(bottom = 8.dp),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }
            if (!compact) {
                Text(
                    text = context.getString(R.string.widget_on_phone, formatBytes(bytes)),
                    maxLines = 1,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                )
            }
            Spacer(GlanceModifier.height(12.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (transferring) {
                    Pill(
                        icon = R.drawable.ic_widget_stop,
                        label = context.getString(R.string.common_stop).takeUnless { compact },
                        container = GlanceTheme.colors.errorContainer,
                        content = GlanceTheme.colors.onErrorContainer,
                        action = serviceAction(context, BridgeService.ACTION_STOP_TRANSFER),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                } else {
                    Pill(
                        icon = R.drawable.ic_widget_sync,
                        label = context.getString(R.string.common_sync).takeUnless { compact },
                        container = GlanceTheme.colors.primary,
                        content = GlanceTheme.colors.onPrimary,
                        action = serviceAction(context, BridgeService.ACTION_SYNC),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Pill(
                        icon = R.drawable.ic_widget_offload,
                        label = context.getString(R.string.common_offload).takeUnless { compact },
                        container = GlanceTheme.colors.secondaryContainer,
                        content = GlanceTheme.colors.onSecondaryContainer,
                        action = serviceAction(context, BridgeService.ACTION_OFFLOAD),
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
        }
    }

    @Composable
    private fun Pill(
        icon: Int,
        label: String?,
        container: androidx.glance.unit.ColorProvider,
        content: androidx.glance.unit.ColorProvider,
        action: Action,
        modifier: GlanceModifier,
    ) {
        Row(
            modifier = modifier
                .height(44.dp)
                .cornerRadius(22.dp)
                .background(container)
                .clickable(action),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(content),
                modifier = GlanceModifier.size(20.dp),
            )
            if (label != null) {
                Spacer(GlanceModifier.width(6.dp))
                Text(label, style = TextStyle(color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
    }

    // Sync/Offload call startForeground(); Stop only reaches an already running
    // service and must not be started as a foreground service.
    private fun serviceAction(context: Context, action: String): Action =
        actionStartService(
            Intent(context, BridgeService::class.java).setAction(action),
            isForegroundService = action != BridgeService.ACTION_STOP_TRANSFER,
        )

    companion object {
        private val COMPACT = DpSize(110.dp, 110.dp)
        private val MEDIUM = DpSize(200.dp, 110.dp)

        private val SYNC = ColorProvider(day = LightSignals.sync, night = DarkSignals.sync)
        private val OFFLOAD = ColorProvider(day = LightSignals.offload, night = DarkSignals.offload)
        private val ONLINE = ColorProvider(day = LightSignals.online, night = DarkSignals.online)
        private val IDLE = ColorProvider(day = LightSignals.offline, night = DarkSignals.offline)

        private val FILES = intPreferencesKey("files")
        private val BYTES = longPreferencesKey("bytes")
        private val PHASE = stringPreferencesKey("phase")
        private val CAMERA = booleanPreferencesKey("camera")
        private val HOMELAB = booleanPreferencesKey("homelab")

        suspend fun update(
            context: Context,
            files: Int,
            bytes: Long,
            phase: BridgePhase,
            cameraOnline: Boolean,
            homelabOnline: Boolean,
        ) {
            val manager = GlanceAppWidgetManager(context)
            manager.getGlanceIds(Z50Widget::class.java).forEach { id ->
                updateAppWidgetState(context, id) { prefs ->
                    prefs[FILES] = files
                    prefs[BYTES] = bytes
                    prefs[PHASE] = phase.name
                    prefs[CAMERA] = cameraOnline
                    prefs[HOMELAB] = homelabOnline
                }
            }
            Z50Widget().updateAll(context)
        }
    }
}

class Z50WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = Z50Widget()
}

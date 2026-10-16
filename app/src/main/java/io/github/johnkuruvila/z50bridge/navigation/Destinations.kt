package io.github.johnkuruvila.z50bridge.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.johnkuruvila.z50bridge.R
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object HomeKey : NavKey

@Serializable
data object ClipsKey : NavKey

@Serializable
data object ActivityKey : NavKey

/** Pushed on top of the current tab; hides the navigation bar. [section] null is the category list. */
@Serializable
data class SettingsKey(val section: SettingsSection? = null) : NavKey

@Serializable
enum class SettingsSection { Camera, Sync, Offload, OffloadNetworks, Storage, Appearance, Integrations, About }

/** The camera's own settings, read from and written to the Z50. */
@Serializable
data object DashcamSettingsKey : NavKey

@Serializable
data object LiveViewKey : NavKey

@Serializable
data class ClipDetailKey(val filename: String) : NavKey

enum class TopDestination(
    val key: NavKey,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(HomeKey, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    Clips(ClipsKey, R.string.nav_clips, Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    Activity(ActivityKey, R.string.nav_activity, Icons.Filled.History, Icons.Outlined.History),
}

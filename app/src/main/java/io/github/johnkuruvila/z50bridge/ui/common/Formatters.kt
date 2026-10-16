package io.github.johnkuruvila.z50bridge.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.johnkuruvila.z50bridge.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "—"
    if (bytesPerSec < 1024) return "$bytesPerSec B/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB/s", kb)
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), "%.1f MB/s", mb)
}

/** Speed split into a big number and its unit, e.g. ("38.2", "MB/s"). */
fun splitSpeed(bytesPerSec: Long): Pair<String, String> {
    if (bytesPerSec <= 0) return "—" to "MB/s"
    val kb = bytesPerSec / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f", kb) to "KB/s"
    val mb = kb / 1024.0
    return String.format(Locale.getDefault(), if (mb < 100) "%.1f" else "%.0f", mb) to "MB/s"
}

fun formatEta(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return "—"
    if (seconds < 60) return "${seconds}s"
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    if (hours > 0) return "${hours}h ${mins.toString().padStart(2, '0')}m"
    return "${mins}m ${secs.toString().padStart(2, '0')}s"
}

fun formatClock(timeMs: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timeMs))
}

@Composable
fun formatRelativeTime(timeMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timeMs).coerceAtLeast(0)
    val secs = diff / 1000
    val mins = secs / 60
    val hours = mins / 60
    val days = hours / 24

    return when {
        secs < 45 -> stringResource(R.string.relative_just_now)
        mins < 60 -> pluralStringResource(R.plurals.relative_minutes_ago, mins.toInt(), mins)
        hours < 24 -> pluralStringResource(R.plurals.relative_hours_ago, hours.toInt(), hours)
        days == 1L -> stringResource(R.string.relative_yesterday)
        else -> pluralStringResource(R.plurals.relative_days_ago, days.toInt(), days)
    }
}

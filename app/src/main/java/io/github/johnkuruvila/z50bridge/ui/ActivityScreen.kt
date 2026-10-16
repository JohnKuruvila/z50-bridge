package io.github.johnkuruvila.z50bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.ActivityItem
import io.github.johnkuruvila.z50bridge.ActivityKind
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.common.formatClock
import io.github.johnkuruvila.z50bridge.ui.common.shareDiagnostics
import io.github.johnkuruvila.z50bridge.ui.components.EmptyState
import io.github.johnkuruvila.z50bridge.ui.components.Z50TopBar
import io.github.johnkuruvila.z50bridge.ui.home.activityVisual
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import io.github.johnkuruvila.z50bridge.ui.theme.signals
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class ActivityFilter(val labelRes: Int) {
    All(R.string.activity_filter_all),
    Syncs(R.string.activity_filter_syncs),
    Offloads(R.string.activity_filter_offloads),
    Errors(R.string.activity_filter_errors),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    items: List<ActivityItem>,
    buildDiagnostics: suspend () -> String,
    onClear: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.All) }
    var query by rememberSaveable { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val copiedText = stringResource(R.string.activity_copied)
    val shareFailed = stringResource(R.string.diagnostics_share_failed)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val filtered = remember(items, filter, query) {
        items.filter { item ->
            val matchesFilter = when (filter) {
                ActivityFilter.All -> true
                ActivityFilter.Syncs -> item.kind == ActivityKind.Sync
                ActivityFilter.Offloads -> item.kind == ActivityKind.Offload
                ActivityFilter.Errors -> item.kind == ActivityKind.Error || item.error
            }
            matchesFilter && (query.isBlank() || item.text.contains(query, ignoreCase = true))
        }
    }
    val sections = filtered.groupBy { dayLabel(it.timeMs) }

    // Back clears an active search or filter before leaving the screen.
    BackHandler(enabled = query.isNotEmpty() || filter != ActivityFilter.All) {
        query = ""
        filter = ActivityFilter.All
    }
    val errorCount = items.count { it.kind == ActivityKind.Error || it.error }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Z50TopBar(
                title = stringResource(R.string.title_activity),
                subtitle = if (items.isEmpty()) null else activitySubtitle(items.size, errorCount),
                scrollBehavior = scrollBehavior,
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_share)) },
                                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        if (!shareDiagnostics(context, buildDiagnostics())) AppToasts.show(context, shareFailed)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.activity_clear_cd)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                                enabled = items.isNotEmpty(),
                                onClick = {
                                    menuOpen = false
                                    showClearDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        ) {
            if (items.isNotEmpty()) {
                item(key = "search") {
                    SearchField(query = query, onQuery = { query = it })
                }
                item(key = "filters") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActivityFilter.entries.forEach { option ->
                            val isSelected = filter == option
                            FilterChip(
                                selected = isSelected,
                                onClick = { filter = option },
                                label = { Text(stringResource(option.labelRes)) },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else {
                                    null
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    val searching = query.isNotBlank() || filter != ActivityFilter.All
                    EmptyState(
                        icon = if (searching) Icons.Outlined.SearchOff else Icons.Outlined.History,
                        title = stringResource(if (searching) R.string.activity_no_match else R.string.activity_empty),
                        body = stringResource(if (searching) R.string.activity_no_match_body else R.string.activity_empty_body),
                    )
                }
            }

            sections.forEach { (day, dayItems) ->
                item(key = "day-$day") {
                    Text(
                        text = day,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
                    )
                }
                itemsIndexed(dayItems, key = { _, it -> "${it.timeMs}_${it.text.hashCode()}" }) { index, item ->
                    TimelineRow(
                        item = item,
                        first = index == 0,
                        last = index == dayItems.lastIndex,
                        onRetry = onRetry,
                        onCopy = {
                            copyToClipboard(context, item)
                            scope.launch { snackbar.showSnackbar(copiedText) }
                        },
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.activity_clear_title)) },
            text = { Text(stringResource(R.string.activity_clear_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        onClear()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.activity_search)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/** One event on the day's timeline: a colored node on a rail, then the card. */
@Composable
private fun TimelineRow(
    item: ActivityItem,
    first: Boolean,
    last: Boolean,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = activityVisual(item)
    val rail = MaterialTheme.colorScheme.outlineVariant
    val isError = item.kind == ActivityKind.Error || item.error

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val x = size.width / 2f
                val nodeCenter = 30.dp.toPx()
                if (!first) drawLine(rail, Offset(x, 0f), Offset(x, nodeCenter), strokeWidth = 2.dp.toPx())
                if (!last) drawLine(rail, Offset(x, nodeCenter), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, bottom = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (isError) MaterialTheme.signals.error.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer)
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatClock(item.timeMs),
                    style = Numeric.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = "[${item.kind.name}] ${item.text}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(10.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isError) {
                            TextButton(
                                onClick = onRetry,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.signals.error),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.common_retry), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        TextButton(onClick = onCopy) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.common_copy), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun activitySubtitle(events: Int, errors: Int): String {
    val eventText = pluralStringResource(R.plurals.activity_events, events, events)
    if (errors == 0) return eventText
    return stringResource(R.string.activity_subtitle, eventText, pluralStringResource(R.plurals.activity_errors, errors, errors))
}

@Composable
private fun dayLabel(timeMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
    val today = Instant.now().atZone(zone).toLocalDate()
    return when (date) {
        today -> stringResource(R.string.activity_day_today)
        today.minusDays(1) -> stringResource(R.string.activity_day_yesterday)
        else -> DateTimeFormatter.ofPattern("EEEE, d MMM", locale).format(date)
    }
}

private fun copyToClipboard(context: Context, item: ActivityItem) {
    val text = "${formatClock(item.timeMs)} [${item.kind.name}] ${item.text}"
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.activity_clip_label), text))
}

package io.github.johnkuruvila.z50bridge.ui.clips

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.StagedClip
import io.github.johnkuruvila.z50bridge.TrashToken
import io.github.johnkuruvila.z50bridge.ui.common.formatBytes
import io.github.johnkuruvila.z50bridge.ui.components.EmptyState
import io.github.johnkuruvila.z50bridge.ui.components.Tag
import io.github.johnkuruvila.z50bridge.ui.components.Z50TopBar
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import kotlinx.coroutines.launch

private enum class ClipFilter(val labelRes: Int) {
    All(R.string.clips_filter_all),
    Front(R.string.clips_filter_front),
    Rear(R.string.clips_filter_rear),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClipsScreen(
    clips: List<StagedClip>,
    totalBytes: Long,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onOpenClip: (StagedClip) -> Unit,
    onTrashClips: (List<StagedClip>) -> TrashToken?,
    onRestoreTrash: (TrashToken) -> Unit,
    onShareClips: (List<StagedClip>) -> Unit,
    onClearAll: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ClipFilter.All) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val resources = LocalResources.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val visible = when (filter) {
        ClipFilter.All -> clips
        ClipFilter.Front -> clips.filter { !it.isRear }
        ClipFilter.Rear -> clips.filter { it.isRear }
    }
    val sections = visible.groupBy { it.dateFormatted }
    val selectionMode = selected.isNotEmpty()
    // Drop selections for clips that disappeared (offloaded or deleted elsewhere).
    val liveSelection = selected.filterTo(mutableSetOf()) { name -> clips.any { it.filename == name } }
    if (liveSelection.size != selected.size) selected = liveSelection

    // Back unwinds in-screen state before leaving: selection first, then filter.
    BackHandler(enabled = filter != ClipFilter.All) { filter = ClipFilter.All }
    BackHandler(enabled = selectionMode) { selected = emptySet() }

    fun undoableTrash(targets: List<StagedClip>) {
        val token = onTrashClips(targets) ?: return
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = resources.getQuantityString(R.plurals.clips_deleted, targets.size, targets.size),
                actionLabel = resources.getString(R.string.common_undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onRestoreTrash(token)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            pluralStringResource(R.plurals.clips_selected, selected.size, selected.size),
                            style = Numeric.large,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.clips_exit_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = { selected = visible.map { it.filename }.toSet() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.clips_select_all))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            } else {
                Z50TopBar(
                    title = stringResource(R.string.title_clips),
                    subtitle = if (clips.isEmpty()) {
                        null
                    } else {
                        pluralStringResource(R.plurals.clips_subtitle, clips.size, clips.size, formatBytes(totalBytes))
                    },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.clips_refresh_cd))
                        }
                        if (clips.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.common_more))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.clips_select)) },
                                        leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            selected = visible.map { it.filename }.toSet()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.clips_clear_all_cd)) },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                                        enabled = !busy,
                                        onClick = {
                                            menuOpen = false
                                            showClearAllConfirm = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (clips.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.VideoLibrary,
                    title = stringResource(R.string.clips_caught_up),
                    body = stringResource(R.string.clips_empty_body),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 164.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                        FilterGroup(filter = filter, onFilter = { filter = it })
                    }
                    if (visible.isEmpty()) {
                        item(key = "no-match", span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(
                                icon = Icons.Outlined.Videocam,
                                title = stringResource(R.string.clips_no_match),
                                body = stringResource(R.string.clips_filter_body),
                            )
                        }
                    }
                    sections.forEach { (date, dayClips) ->
                        item(key = "header-$date", span = { GridItemSpan(maxLineSpan) }) {
                            DayHeader(
                                date = date,
                                count = dayClips.size,
                                bytes = dayClips.sumOf { it.sizeBytes },
                            )
                        }
                        items(dayClips, key = { it.filename }) { clip ->
                            val isSelected = clip.filename in selected
                            ClipTile(
                                clip = clip,
                                selectionMode = selectionMode,
                                selected = isSelected,
                                onClick = {
                                    if (selectionMode) {
                                        selected = if (isSelected) selected - clip.filename else selected + clip.filename
                                    } else {
                                        onOpenClip(clip)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = if (isSelected) selected - clip.filename else selected + clip.filename
                                },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = selectionMode,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                HorizontalFloatingToolbar(expanded = true) {
                    IconButton(onClick = { onShareClips(clips.filter { it.filename in selected }) }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.clips_share_selected))
                    }
                    FilledIconButton(
                        onClick = {
                            undoableTrash(clips.filter { it.filename in selected })
                            selected = emptySet()
                        },
                        enabled = !busy,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.clips_delete_selected))
                    }
                }
            }
        }
    }

    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.clips_clear_dialog_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.clips_clear_dialog_body,
                        clips.size,
                        clips.size,
                        formatBytes(totalBytes),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAll()
                        showClearAllConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(R.string.common_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FilterGroup(filter: ClipFilter, onFilter: (ClipFilter) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val options = ClipFilter.entries
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = filter == option,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onFilter(option)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(option.labelRes))
            }
        }
    }
}

@Composable
private fun DayHeader(date: String, count: Int, bytes: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(date, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            text = pluralStringResource(R.plurals.clips_day_summary, count, count, formatBytes(bytes)),
            style = Numeric.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipTile(
    clip: StagedClip,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "scale")
    val corner by animateDpAsState(if (selected) 28.dp else 20.dp, label = "corner")
    val description = stringResource(
        R.string.clips_tile_cd,
        clip.dateFormatted,
        clip.timeFormatted,
        stringResource(if (clip.isRear) R.string.clip_side_rear else R.string.clip_side_front),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .scale(scale)
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (clip.thumbFile != null) {
                AsyncImage(
                    model = clip.thumbFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
            )
            if (clip.durationFormatted.isNotEmpty()) {
                Tag(
                    text = clip.durationFormatted,
                    container = Color.Black.copy(alpha = 0.55f),
                    content = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (clip.isRear) {
                    Tag(
                        text = stringResource(R.string.clip_side_rear),
                        container = Color.Black.copy(alpha = 0.55f),
                        content = Color.White,
                    )
                }
                if (clip.hasGps) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = selectionMode,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.onPrimary else Color.Black.copy(alpha = 0.3f)),
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(clip.timeFormatted, style = Numeric.medium, modifier = Modifier.weight(1f))
            Text(
                formatBytes(clip.sizeBytes),
                style = Numeric.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

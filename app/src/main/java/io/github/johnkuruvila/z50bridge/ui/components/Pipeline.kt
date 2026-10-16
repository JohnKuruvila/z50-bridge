package io.github.johnkuruvila.z50bridge.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.ui.theme.LocalReducedMotion
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import io.github.johnkuruvila.z50bridge.ui.theme.signals

/** What a pipeline edge is doing right now. */
enum class LinkState { Down, Ready, Flowing }

private val NodeSize = 60.dp
private val NodeColumnWidth = 92.dp

/**
 * The app's signature: camera → phone → homelab as three nodes. The edge that
 * is transferring animates in its signal color (amber for sync, cyan for
 * offload); reachable edges are solid, unreachable ones dotted.
 */
@Composable
fun PipelineDiagram(
    cameraOnline: Boolean,
    homelabOnline: Boolean,
    syncing: Boolean,
    offloading: Boolean,
    stagedLabel: String,
    modifier: Modifier = Modifier,
    /** Tapping the camera or homelab node opens its connection checklist. */
    onCameraClick: (() -> Unit)? = null,
    onHomelabClick: (() -> Unit)? = null,
) {
    val signals = MaterialTheme.signals
    val cameraLink = when {
        syncing -> LinkState.Flowing
        cameraOnline -> LinkState.Ready
        else -> LinkState.Down
    }
    val homelabLink = when {
        offloading -> LinkState.Flowing
        homelabOnline -> LinkState.Ready
        else -> LinkState.Down
    }
    val description = stringResource(
        R.string.pipeline_cd,
        stringResource(if (cameraOnline) R.string.pipeline_online else R.string.pipeline_offline),
        stagedLabel,
        stringResource(if (homelabOnline) R.string.pipeline_online else R.string.pipeline_offline),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.Top,
    ) {
        PipelineNode(
            icon = Icons.Outlined.Videocam,
            label = stringResource(R.string.pipeline_camera),
            status = stringResource(if (cameraOnline) R.string.pipeline_online else R.string.pipeline_offline),
            accent = signals.sync,
            online = cameraOnline,
            active = syncing,
            onClick = onCameraClick,
        )
        PipelineLink(
            state = cameraLink,
            color = signals.sync,
            modifier = Modifier
                .weight(1f)
                .padding(top = NodeSize / 2 - 6.dp),
        )
        PipelineNode(
            icon = Icons.Outlined.PhoneAndroid,
            label = stringResource(R.string.pipeline_phone),
            status = stagedLabel,
            accent = if (offloading) signals.offload else signals.sync,
            online = true,
            active = syncing || offloading,
            statusIsCount = true,
        )
        PipelineLink(
            state = homelabLink,
            color = signals.offload,
            modifier = Modifier
                .weight(1f)
                .padding(top = NodeSize / 2 - 6.dp),
        )
        PipelineNode(
            icon = Icons.Outlined.Dns,
            label = stringResource(R.string.pipeline_homelab),
            status = stringResource(if (homelabOnline) R.string.pipeline_online else R.string.pipeline_offline),
            accent = signals.offload,
            online = homelabOnline,
            active = offloading,
            onClick = onHomelabClick,
        )
    }
}

@Composable
private fun PipelineNode(
    icon: ImageVector,
    label: String,
    status: String,
    accent: Color,
    online: Boolean,
    active: Boolean,
    statusIsCount: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val signals = MaterialTheme.signals
    val container by animateColorAsState(
        if (online) accent.copy(alpha = if (active) 0.28f else 0.16f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "node",
    )
    val tint by animateColorAsState(
        if (online) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        label = "nodeTint",
    )
    Column(
        modifier = Modifier
            .width(NodeColumnWidth)
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(NodeSize)
                    .clip(RoundedCornerShape(22.dp))
                    .background(container)
                    .then(
                        if (active) Modifier.border(2.dp, accent, RoundedCornerShape(22.dp)) else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
            }
            if (!statusIsCount) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 3.dp, y = (-3).dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(if (online) signals.online else signals.offline),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = status,
                style = if (statusIsCount) Numeric.small else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PipelineLink(state: LinkState, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val phase = if (state == LinkState.Flowing && !reduced) {
        val transition = rememberInfiniteTransition(label = "flow")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "phase",
        )
        value
    } else {
        0f
    }
    val muted = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .height(12.dp)
            .padding(horizontal = 2.dp),
    ) {
        val y = size.height / 2f
        val start = Offset(0f, y)
        val end = Offset(size.width, y)
        when (state) {
            LinkState.Down -> drawLine(
                color = muted,
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 6.dp.toPx())),
            )
            LinkState.Ready -> drawLine(
                color = color.copy(alpha = 0.45f),
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            LinkState.Flowing -> {
                val dash = 8.dp.toPx()
                val gap = 6.dp.toPx()
                drawLine(
                    color = color.copy(alpha = 0.18f),
                    start = start,
                    end = end,
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = start,
                    end = end,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dash, gap),
                        phase = -(dash + gap) * phase,
                    ),
                )
            }
        }
    }
}

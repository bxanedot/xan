package app.xan.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopSidebar(
    destination: Destination,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onNavigate: (Destination) -> Unit,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    canToggleCollapse: Boolean = true,
) {
    Column(
        modifier.clip(RoundedCornerShape(24.dp))
            .background(XanColors.Panel)
            .border(1.dp, XanColors.Border.copy(alpha = .7f), RoundedCornerShape(24.dp))
            .padding(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = if (collapsed) 0.dp else 8.dp),
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XanMark(27.dp)
            androidx.compose.animation.AnimatedVisibility(
                visible = !collapsed,
                enter = fadeIn(tween(if (reducedMotion) 0 else 150)) + expandHorizontally(
                    expandFrom = Alignment.Start,
                    animationSpec = tween(if (reducedMotion) 0 else 180),
                ),
                exit = fadeOut(tween(if (reducedMotion) 0 else 100)) + shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    animationSpec = tween(if (reducedMotion) 0 else 140),
                ),
            ) {
                Text("XAN", Modifier.padding(start = 10.dp), color = XanColors.Text, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        listOf(
            Destination.HOME,
            Destination.SEARCH,
            Destination.LIBRARY,
            Destination.RECOGNIZE,
            Destination.SETTINGS,
        ).forEach { item ->
            val selected = destination == item
            val animationDuration = if (reducedMotion) 0 else 160
            val backgroundColor by animateColorAsState(
                targetValue = if (selected) XanColors.Selected else XanColors.Panel,
                animationSpec = tween(animationDuration),
                label = "sidebar-${item.name.lowercase()}-background",
            )
            val iconColor by animateColorAsState(
                targetValue = when {
                    selected -> XanColors.Accent
                    else -> XanColors.Muted
                },
                animationSpec = tween(animationDuration),
                label = "sidebar-${item.name.lowercase()}-icon",
            )
            Row(
                Modifier.fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(backgroundColor)
                    .clickable { onNavigate(item) }
                    .semantics(mergeDescendants = true) { contentDescription = item.label }
                    .padding(horizontal = if (collapsed) 0.dp else 12.dp),
                horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, contentDescription = null, tint = iconColor)
                androidx.compose.animation.AnimatedVisibility(
                    visible = !collapsed,
                    enter = fadeIn(tween(if (reducedMotion) 0 else 150)) + expandHorizontally(
                        expandFrom = Alignment.Start,
                        animationSpec = tween(if (reducedMotion) 0 else 180),
                    ),
                    exit = fadeOut(tween(if (reducedMotion) 0 else 100)) + shrinkHorizontally(
                        shrinkTowards = Alignment.Start,
                        animationSpec = tween(if (reducedMotion) 0 else 140),
                    ),
                ) {
                    Text(
                        item.label,
                        Modifier.padding(start = 12.dp),
                        color = if (selected) XanColors.Text else XanColors.Muted,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = XanColors.Border.copy(alpha = .7f))
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp).height(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = canToggleCollapse, onClick = onToggleCollapsed)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (collapsed) "Expand sidebar" else "Collapse sidebar"
                },
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft,
                contentDescription = null,
                tint = if (canToggleCollapse) XanColors.Muted else XanColors.Muted.copy(alpha = .45f),
            )
            if (!collapsed) Text("Collapse", Modifier.padding(start = 10.dp), color = if (canToggleCollapse) XanColors.Muted else XanColors.Muted.copy(alpha = .45f))
        }
    }
}

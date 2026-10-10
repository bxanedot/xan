package app.xan.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import kotlin.math.abs

internal data class MagnetNavItem(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

// Keep the quick actions and their order aligned with Android's Magnet.
internal val MagnetNavItems = listOf(
    MagnetNavItem("home", "Home", Icons.Rounded.Home),
    MagnetNavItem("search", "Search", Icons.Rounded.Search),
    MagnetNavItem("library", "Library", Icons.Rounded.LibraryMusic),
    MagnetNavItem("recognize", "Recognize Music", Icons.Rounded.Mic),
    MagnetNavItem("shuffle", "Shuffle", Icons.Rounded.Shuffle),
    MagnetNavItem("settings", "Settings", Icons.Rounded.Settings),
)

private const val HUB = 56f
private const val CHIP = MAGNET_ATTACHMENT_SIZE

private data class MagnetMotion(
    val damping: Float,
    val stiffness: Float,
    val staggerMs: Long,
    val startX: Float,
    val startY: Float,
    val rotation: Float,
    val startScaleX: Float,
    val startScaleY: Float,
    val reverseOrder: Boolean = false,
    val alternateSides: Boolean = false,
    val scatter: Boolean = false,
)

// The Magnet cycles through Android's eight distinct spring gestures on each open.
private val MagnetMotions = listOf(
    MagnetMotion(.66f, 350f, 32, 18f, 32f, 8f, .78f, .78f),
    MagnetMotion(.48f, 440f, 40, 48f, 20f, -14f, .68f, .84f),
    MagnetMotion(.80f, 580f, 54, 22f, 36f, -12f, .78f, .78f, alternateSides = true, scatter = true),
    MagnetMotion(.75f, 470f, 24, -110f, 0f, -7f, .92f, 1f),
    MagnetMotion(.56f, 420f, 38, 52f, 58f, 66f, .35f, .35f),
    MagnetMotion(.72f, 510f, 48, 0f, -95f, 0f, .82f, 1.18f, reverseOrder = true),
    MagnetMotion(.62f, 385f, 27, 90f, 48f, 24f, .20f, .20f, alternateSides = true, scatter = true),
    MagnetMotion(.54f, 630f, 58, 0f, 16f, 0f, .58f, 1.40f, reverseOrder = true),
)

/** Floating, backgroundless launcher with Android's six radial navigation/actions. */
@Composable
internal fun MagneticNavigation(
    selectedId: String,
    reducedMotion: Boolean,
    semicircle: Boolean,
    onSelect: (MagnetNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val savedPosition = remember { DesktopBackend.magnetPosition() }
    var launcherPosition by remember { mutableStateOf<Offset?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf(false) }
    var motionVariant by remember { mutableIntStateOf(0) }
    val motion = MagnetMotions[motionVariant]
    val rotation by animateFloatAsState(
        if (!reducedMotion && expanded) 90f else 0f,
        if (reducedMotion) snap() else spring(dampingRatio = motion.damping, stiffness = motion.stiffness),
        label = "magnet-hub-rotation",
    )
    LaunchedEffect(expanded, reducedMotion) {
        if (reducedMotion) showing = expanded
        else if (expanded) showing = true else {
            delay(400)
            showing = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthDp = constraints.maxWidth / density
        val heightDp = constraints.maxHeight / density
        val maxX = (widthDp - HUB).coerceAtLeast(0f)
        val maxY = (heightDp - HUB).coerceAtLeast(0f)
        val targetX = (launcherPosition?.x ?: savedPosition?.first?.times(maxX) ?: (maxX - 18f).coerceAtLeast(0f)).coerceIn(0f, maxX)
        val targetY = (launcherPosition?.y ?: savedPosition?.second?.times(maxY) ?: (maxY - 18f).coerceAtLeast(0f)).coerceIn(0f, maxY)
        val dockSpec: FiniteAnimationSpec<Float> = if (reducedMotion || dragging) snap() else spring(dampingRatio = .78f, stiffness = 520f)
        val x by animateFloatAsState(targetX, dockSpec, label = "magnet-dock-x")
        val y by animateFloatAsState(targetY, dockSpec, label = "magnet-dock-y")
        val hubCenter = Offset(x + HUB / 2f, y + HUB / 2f)
        val centers = if (semicircle) magnetSemicircleCenters(widthDp, heightDp, hubCenter, MagnetNavItems.size)
            else magnetStackCenters(widthDp, heightDp, hubCenter, MagnetNavItems.size)

        fun savePosition(position: Offset) {
            launcherPosition = position
            DesktopBackend.setMagnetPosition(
                if (maxX == 0f) 0f else position.x / maxX,
                if (maxY == 0f) 0f else position.y / maxY,
            )
        }

        if (showing) MagnetNavItems.forEachIndexed { index, item ->
            MagnetAttachment(
                item = item,
                selected = item.id == selectedId,
                center = centers[index],
                hubCenter = hubCenter,
                index = index,
                open = expanded,
                reducedMotion = reducedMotion,
                motion = motion,
                semicircle = semicircle,
                onClick = { expanded = false; onSelect(item) },
            )
        }

        MagnetHub(
            left = x,
            top = y,
            rotation = rotation,
            expanded = expanded,
            reducedMotion = reducedMotion,
            onDrag = { amount ->
                dragging = true
                launcherPosition = Offset(
                    ((launcherPosition?.x ?: targetX) + amount.x / density).coerceIn(0f, maxX),
                    ((launcherPosition?.y ?: targetY) + amount.y / density).coerceIn(0f, maxY),
                )
            },
            onDragEnd = {
                val currentX = launcherPosition?.x ?: targetX
                val currentY = launcherPosition?.y ?: targetY
                val edges = listOf(
                    Offset(currentX, 0f), Offset(maxX, currentY),
                    Offset(currentX, maxY), Offset(0f, currentY),
                )
                val nearest = edges.minBy { abs(currentX - it.x) + abs(currentY - it.y) }
                val inset = 14f
                val attached = when {
                    nearest.y == 0f -> Offset(currentX.coerceIn(inset, (maxX - inset).coerceAtLeast(inset)), inset)
                    nearest.x == maxX -> Offset((maxX - inset).coerceAtLeast(0f), currentY.coerceIn(inset, (maxY - inset).coerceAtLeast(inset)))
                    nearest.y == maxY -> Offset(currentX.coerceIn(inset, (maxX - inset).coerceAtLeast(inset)), (maxY - inset).coerceAtLeast(0f))
                    else -> Offset(inset, currentY.coerceIn(inset, (maxY - inset).coerceAtLeast(inset)))
                }
                savePosition(attached)
                dragging = false
            },
            onClick = {
                if (!expanded) motionVariant = (motionVariant + 1) % MagnetMotions.size
                expanded = !expanded
            },
        )
    }
}

@Composable
private fun MagnetHub(
    left: Float,
    top: Float,
    rotation: Float,
    expanded: Boolean,
    reducedMotion: Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (reducedMotion) 1f else when { pressed -> .91f; hovered -> 1.06f; else -> 1f },
        if (reducedMotion) snap() else spring(dampingRatio = .58f, stiffness = 440f),
        label = "magnet-hub-scale",
    )
    Box(
        Modifier.offset(left.dp, top.dp).size(HUB.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .hoverable(interaction)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDrag = { change, amount -> change.consume(); onDrag(Offset(amount.x, amount.y)) },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = if (expanded) "Close Magnet navigation" else "Open Magnet navigation"
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (expanded) {
            Icon(Icons.Rounded.Close, contentDescription = "Close navigation Magnet", tint = XanColors.Text, modifier = Modifier.size(23.dp).graphicsLayer { rotationZ = rotation })
        } else FourSquareMagnet(Modifier.size(28.dp))
    }
}

@Composable
private fun FourSquareMagnet(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val unit = size.minDimension / 24f
        val stroke = 1.7f * unit
        listOf(Offset(3f, 3f), Offset(13f, 3f), Offset(3f, 13f), Offset(13f, 13f)).forEach { point ->
            val path = Path().apply {
                moveTo((point.x + 1f) * unit, (point.y + 1f) * unit)
                lineTo((point.x + 7f) * unit, (point.y + 1f) * unit)
                lineTo((point.x + 7f) * unit, (point.y + 7f) * unit)
                lineTo((point.x + 1f) * unit, (point.y + 7f) * unit)
                close()
            }
            drawPath(path, XanColors.Text, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun MagnetAttachment(
    item: MagnetNavItem,
    selected: Boolean,
    center: Offset,
    hubCenter: Offset,
    index: Int,
    open: Boolean,
    reducedMotion: Boolean,
    motion: MagnetMotion,
    semicircle: Boolean,
    onClick: () -> Unit,
) {
    val arrival = remember(item.id) { Animatable(0f) }
    LaunchedEffect(open, index, reducedMotion, motion) {
        if (reducedMotion) arrival.snapTo(if (open) 1f else 0f)
        else if (open) {
            val order = if (motion.reverseOrder) index else MagnetNavItems.lastIndex - index
            delay(order * motion.staggerMs)
            arrival.animateTo(1f, spring(motion.damping, motion.stiffness))
        } else arrival.animateTo(0f, spring(.9f, 650f))
    }
    val interaction = remember(item.id) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            selected -> XanColors.Accent.copy(alpha = .84f)
            hovered -> lerp(XanColors.Panel, XanColors.Text, .13f)
            else -> XanColors.Panel
        },
        tween(200), label = "magnet-attachment-fill-${item.id}",
    )
    val hoverScale by animateFloatAsState(
        if (!reducedMotion && hovered) 1.08f else 1f,
        if (reducedMotion) snap() else tween(180), label = "magnet-attachment-hover-${item.id}",
    )
    val dx = center.x - hubCenter.x
    val dy = center.y - hubCenter.y
    val progress = arrival.value
    val direction = if (motion.alternateSides && index % 2 == 1) -1f else 1f
    val scatterX = if (motion.scatter) listOf(-28f, 23f, -17f, 30f, -25f, 19f)[index] else 0f
    val scatterY = if (motion.scatter) listOf(-6f, 7f, -9f, 8f, -4f, 6f)[index] else 0f
    val itemWidth = if (semicircle) CHIP.dp else 184.dp
    val shape = if (semicircle) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(50)
    Box(
        Modifier.offset((center.x - (if (semicircle) CHIP / 2f else 92f)).dp, (center.y - CHIP / 2f).dp)
            .width(itemWidth)
            .height(CHIP.dp)
            .graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                translationX = (1f - progress) * motion.startX.dp.toPx() * direction - dx.dp.toPx() * (1f - progress) + scatterX.dp.toPx() * progress
                translationY = (1f - progress) * (motion.startY + index * 7f).dp.toPx() - dy.dp.toPx() * (1f - progress) + scatterY.dp.toPx() * progress
                val scale = (motion.startScaleX + progress * (1f - motion.startScaleX)) * hoverScale * if (pressed) .91f else 1f
                scaleX = scale
                scaleY = (motion.startScaleY + progress * (1f - motion.startScaleY)) * hoverScale * if (pressed) .91f else 1f
                rotationZ = (1f - progress) * motion.rotation * direction
            }
            .clip(shape).background(fill)
            .then(if (selected) Modifier.border(1.5.dp, XanColors.Accent, shape) else Modifier)
            .hoverable(interaction)
            .clickable(enabled = open && progress > .96f, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = if (semicircle) Alignment.Center else Alignment.CenterStart,
    ) {
        if (semicircle) Icon(item.icon, contentDescription = item.label, tint = XanColors.Text, modifier = Modifier.size(21.dp))
        else Row(Modifier.padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(item.icon, contentDescription = null, tint = XanColors.Text, modifier = Modifier.size(22.dp))
            Text(item.label, color = XanColors.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

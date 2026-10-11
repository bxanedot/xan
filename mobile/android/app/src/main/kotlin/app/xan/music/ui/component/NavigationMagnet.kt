package app.xan.music.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.xan.music.R
import app.xan.music.ui.screens.Screens
import app.xan.music.ui.utils.xanLogoPainter
import kotlinx.coroutines.delay

private data class MagnetMotion(
    val name: String,
    val damping: Float,
    val stiffness: Float,
    val staggerMs: Long,
    val startX: Float,
    val startY: Float,
    val rotation: Float,
    val scaleX: Float,
    val scaleY: Float,
    val reverseOrder: Boolean = false,
    val alternateSides: Boolean = false,
    val scatter: Boolean = false,
)

// Three original gestures, followed by five new paths. Distinct position,
// rotation, scale and ordering make each cycle visibly different.
private val MagnetMotions = listOf(
    MagnetMotion("Float", .66f, 350f, 32, 18f, 32f, 8f, .78f, .78f),
    MagnetMotion("Elastic fan", .48f, 440f, 40, 48f, 20f, -14f, .68f, .84f),
    MagnetMotion("Ripple", .80f, 580f, 54, 22f, 36f, -12f, .78f, .78f, alternateSides = true, scatter = true),
    MagnetMotion("Side sweep", .75f, 470f, 24, -110f, 0f, -7f, .92f, 1f),
    MagnetMotion("Corkscrew", .56f, 420f, 38, 52f, 58f, 66f, .35f, .35f),
    MagnetMotion("Raindrop", .72f, 510f, 48, 0f, -95f, 0f, .82f, 1.18f, reverseOrder = true),
    MagnetMotion("Bloom", .62f, 385f, 27, 90f, 48f, 24f, .20f, .20f, alternateSides = true, scatter = true),
    MagnetMotion("Accordion", .54f, 630f, 58, 0f, 16f, 0f, .58f, 1.40f, reverseOrder = true),
)

private data class MagnetEntry(
    val screen: Screens,
    val iconId: Int,
    val titleId: Int,
    val action: () -> Unit,
)

/** Settings and Downloads have their own entry points; Recognition lives in Magnet. */
internal val MagnetNavigationScreens = listOf(
    Screens.Home,
    Screens.Search,
    Screens.Library,
    Screens.Recognition,
)

/** Both layouts stay attached to the Magnet, including every animation variant. */
internal fun magnetPopupPosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    popupContentSize: IntSize,
    onLeft: Boolean,
    verticalOffset: Int,
): IntOffset = IntOffset(
    (if (onLeft) anchorBounds.left else anchorBounds.right - popupContentSize.width)
        .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
    (anchorBounds.bottom - popupContentSize.height + verticalOffset)
        .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
)

/** A single, thumb-reachable entry to primary navigation on every screen. */
@Composable
fun NavigationMagnet(
    currentRoute: String?,
    onLeft: Boolean,
    onNavigate: (Screens) -> Unit,
    semicircle: Boolean = false,
    showIconNames: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var showing by remember { mutableStateOf(open) }
    var motionVariant by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val popupOffset = with(LocalDensity.current) { -72.dp.roundToPx() }
    val configuration = LocalConfiguration.current
    val menuMaxHeight = configuration.screenHeightDp.dp * .65f
    val arcWidth = (configuration.screenWidthDp - 32).coerceAtMost(320)
    val arcLayout = remember(arcWidth, onLeft, showIconNames, configuration.fontScale) {
        magnetSemicircleLayout(
            arcWidth.coerceAtLeast(240).toFloat(),
            onLeft,
            itemCount = MagnetNavigationScreens.size,
            labelSpace = if (showIconNames) 56f * configuration.fontScale else 0f,
        )
    }
    // Fall back to a scrolling stack when the arc and optional enlarged names cannot fit.
    val useSemicircle = semicircle && arcWidth >= 240 &&
        configuration.screenHeightDp >= maxOf(360f, arcLayout.height + 32f)
    // Cycle through eight distinct spring gestures, including five new paths.
    // Compose's animation clock honours the system animator duration scale.
    val motion = MagnetMotions[motionVariant]
    val damping = motion.damping
    val stiffness = motion.stiffness
    val glassConfig = LocalGlassEffectConfig.current
    val useGlass = glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()
    val rotation by animateFloatAsState(
        if (open) 90f else 0f, spring(damping, stiffness), label = "magnetRotation",
    )
    val scale by animateFloatAsState(
        if (open && !useSemicircle) 1.06f else 1f,
        spring(damping, stiffness), label = "magnetPress",
    )
    val expandedDescription = stringResource(
        if (open) R.string.navigation_magnet_expanded else R.string.navigation_magnet_collapsed,
    )
    BackHandler(enabled = open) { open = false }
    LaunchedEffect(currentRoute, semicircle, onLeft, showIconNames) { open = false }
    LaunchedEffect(open) {
        if (open) showing = true else {
            delay(400)
            showing = false
        }
    }

    val entries = MagnetNavigationScreens.map { screen ->
        val iconId = when (screen) {
            Screens.Home -> R.drawable.xan_icon_modules
            Screens.Search -> R.drawable.xan_icon_search
            Screens.Library -> R.drawable.xan_icon_library
            else -> screen.icon
        }
        MagnetEntry(screen, iconId, screen.titleId) { onNavigate(screen) }
    }
    val toggle: () -> Unit = {
        if (!open) motionVariant = (motionVariant + 1) % MagnetMotions.size
        open = !open
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Box(modifier) {
        if (showing) {
            Popup(
                popupPositionProvider = remember(useSemicircle, onLeft, popupOffset) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            return magnetPopupPosition(
                                anchorBounds = anchorBounds,
                                windowSize = windowSize,
                                popupContentSize = popupContentSize,
                                onLeft = onLeft,
                                verticalOffset = if (useSemicircle) 0 else popupOffset,
                            )
                        }
                    }
                },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                if (useSemicircle) {
                    SemicircleMagnetMenu(
                        layout = arcLayout,
                        entries = entries,
                        currentRoute = currentRoute,
                        showIconNames = showIconNames,
                        open = open,
                        rotation = rotation,
                        scale = scale,
                        onToggle = toggle,
                        onSelect = { entry ->
                            if (open) {
                                open = false
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                entry.action()
                            }
                        },
                    )
                } else {
                    Column(
                        (if (showIconNames) Modifier.widthIn(min = 172.dp, max = 260.dp) else Modifier.width(68.dp))
                            .heightIn(max = menuMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = if (onLeft) Alignment.Start else Alignment.End,
                    ) {
                        entries.forEachIndexed { index, entry ->
                            val label = stringResource(entry.titleId)
                            val arrival = remember { Animatable(0f) }
                            LaunchedEffect(open, motionVariant) {
                                if (open) {
                                    val order = if (motion.reverseOrder) index else entries.lastIndex - index
                                    delay(order * motion.staggerMs)
                                    arrival.animateTo(1f, spring(damping, stiffness))
                                } else {
                                    arrival.animateTo(0f, spring(.9f, 650f))
                                }
                            }
                            val selected = currentRoute == entry.screen.route ||
                                currentRoute?.startsWith("${entry.screen.route}/") == true ||
                                (entry.screen == Screens.Search && currentRoute?.startsWith("search/") == true)
                            val itemInteraction = remember { MutableInteractionSource() }
                            val itemPressed by itemInteraction.collectIsPressedAsState()
                            val compression by animateFloatAsState(
                                if (itemPressed) .91f else 1f,
                                spring(damping, stiffness), label = "magnetItemPress",
                            )
                            val shape = RoundedCornerShape(50)
                            val itemColor = if (useGlass) glassConfig.textColor
                                else if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            val itemBackground = if (useGlass) {
                                Modifier.liquidGlass(glassConfig, shape = shape, highlightAlpha = .3f)
                            } else {
                                Modifier.background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    shape,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        val progress = arrival.value
                                        alpha = progress.coerceIn(0f, 1f)
                                        val direction = if (motion.alternateSides && index % 2 == 1) -1f else 1f
                                        val scatterX = if (motion.scatter) intArrayOf(-28, 23, -17, 30, -25, 19)[index] else 0
                                        val scatterY = if (motion.scatter) intArrayOf(-6, 7, -9, 8, -4, 6)[index] else 0
                                        translationY = (1f - progress) * ((motion.startY + index * 7f).dp.toPx() + scatterY)
                                        translationX = (1f - progress) * (motion.startX.dp.toPx() * direction + scatterX)
                                        rotationZ = (1f - progress) * motion.rotation * direction
                                        scaleX = (motion.scaleX + progress * (1f - motion.scaleX)) * compression
                                        scaleY = (motion.scaleY + progress * (1f - motion.scaleY)) * compression
                                    }
                                    .clip(shape)
                                    .then(itemBackground)
                                    .then(if (selected) Modifier.border(1.dp, itemColor.copy(alpha = .6f), shape) else Modifier)
                                    .clickable(interactionSource = itemInteraction, indication = null) {
                                        if (open) {
                                            open = false
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            entry.action()
                                        }
                                    }
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = label
                                        this.selected = selected
                                    },
                            ) {
                                Row(
                                    if (showIconNames) Modifier.defaultMinSize(minHeight = 52.dp).padding(horizontal = 18.dp)
                                        else Modifier.size(52.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = if (showIconNames) Arrangement.spacedBy(12.dp) else Arrangement.Center,
                                ) {
                                    if (entry.screen == Screens.Home) {
                                        Image(xanLogoPainter(inAppTile = false), contentDescription = null, modifier = Modifier.size(26.dp))
                                    } else {
                                        Icon(
                                            painterResource(entry.iconId),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            tint = itemColor,
                                        )
                                    }
                                    if (showIconNames) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = itemColor,
                                            modifier = Modifier.clearAndSetSemantics { },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        GlassCircleButton(
            onClick = toggle,
            size = 56.dp,
            opaqueInSolidMode = true,
            modifier = Modifier
                .semantics { stateDescription = expandedDescription }
                .graphicsLayer {
                    alpha = if (useSemicircle && showing) 0f else 1f
                    rotationZ = rotation; scaleX = scale; scaleY = scale
                },
        ) {
            Icon(
                painterResource(if (open) R.drawable.xan_icon_close else R.drawable.xan_icon_modules),
                contentDescription = stringResource(
                    if (open) R.string.close_navigation_magnet else R.string.open_navigation_magnet,
                ),
                modifier = Modifier.size(if (useSemicircle) 20.dp else 26.dp),
            )
        }
    }
}

@Composable
private fun SemicircleMagnetMenu(
    layout: MagnetSemicircleLayout,
    entries: List<MagnetEntry>,
    currentRoute: String?,
    showIconNames: Boolean,
    open: Boolean,
    rotation: Float,
    scale: Float,
    onToggle: () -> Unit,
    onSelect: (MagnetEntry) -> Unit,
) {
    val expandedDescription = stringResource(
        if (open) R.string.navigation_magnet_expanded else R.string.navigation_magnet_collapsed,
    )
    Box(Modifier.size(layout.width.dp, layout.height.dp)) {
        entries.forEachIndexed { index, entry ->
            val point = layout.items[index]
            val arrival = remember { Animatable(0f) }
            LaunchedEffect(open) {
                if (open) delay(index * 24L)
                arrival.animateTo(if (open) 1f else 0f, spring(.72f, 420f))
            }
            val selected = currentRoute == entry.screen.route ||
                currentRoute?.startsWith("${entry.screen.route}/") == true ||
                (entry.screen == Screens.Search && currentRoute?.startsWith("search/") == true)
            val label = stringResource(entry.titleId)
            GlassCircleButton(
                onClick = { onSelect(entry) },
                size = 52.dp,
                opaqueInSolidMode = true,
                modifier = Modifier
                    .absoluteOffset((point.x - 26f).dp, (point.y - 26f).dp)
                    .graphicsLayer {
                        val progress = arrival.value.coerceIn(0f, 1f)
                        alpha = progress.coerceIn(0f, 1f)
                        translationX = (layout.magnet.x - point.x).dp.toPx() * (1f - progress)
                        translationY = (layout.magnet.y - point.y).dp.toPx() * (1f - progress)
                        scaleX = .4f + progress * .6f
                        scaleY = scaleX
                    }
                    .semantics { this.selected = selected }
                    .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50)) else Modifier),
            ) {
                if (entry.screen == Screens.Home) {
                    Image(xanLogoPainter(inAppTile = false), contentDescription = label, modifier = Modifier.size(20.dp))
                } else {
                    Icon(painterResource(entry.iconId), contentDescription = label, modifier = Modifier.size(18.dp))
                }
            }
            if (showIconNames) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .absoluteOffset((point.x - 48f).coerceIn(0f, layout.width - 96f).dp, (point.y + 32f).dp)
                        .width(96.dp)
                        .graphicsLayer {
                            val progress = arrival.value.coerceIn(0f, 1f)
                            alpha = progress
                            translationX = (layout.magnet.x - point.x).dp.toPx() * (1f - progress)
                            translationY = (layout.magnet.y - point.y).dp.toPx() * (1f - progress)
                        }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clearAndSetSemantics { },
                )
            }
        }
        GlassCircleButton(
            onClick = onToggle,
            size = 56.dp,
            opaqueInSolidMode = true,
            modifier = Modifier
                .absoluteOffset((layout.magnet.x - 28f).dp, (layout.magnet.y - 28f).dp)
                .semantics {
                    stateDescription = expandedDescription
                }
                .graphicsLayer { rotationZ = rotation; scaleX = scale; scaleY = scale },
        ) {
            Icon(
                painterResource(if (open) R.drawable.xan_icon_close else R.drawable.xan_icon_modules),
                contentDescription = stringResource(if (open) R.string.close_navigation_magnet else R.string.open_navigation_magnet),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

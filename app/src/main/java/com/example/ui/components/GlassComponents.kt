package com.example.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.abs

private val TopHighlightBrush: Brush
    get() = Brush.horizontalGradient(
        colors = listOf(
            Color.Transparent,
            if (PmpThemeController.isDarkModeActive) Color(0xFF64748B).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.90f),
            Color.Transparent
        )
    )

private val DisabledButtonBrush: Brush
    get() = if (PmpThemeController.isDarkModeActive) {
        Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF0F172A)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFE2EAF2), Color(0xFFD6E2EE)))
    }

private val SharedBadgeShape = RoundedCornerShape(8.dp)
private val SharedButtonShape = RoundedCornerShape(14.dp)
private val SharedTabPillShape = RoundedCornerShape(12.dp)
private val SharedInputShape = RoundedCornerShape(14.dp)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    glowAccentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = PmpThemeController.isDarkModeActive
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    val borderBrush = remember(borderColor, isDark) {
        if (borderColor != null) {
            Brush.linearGradient(
                listOf(
                    borderColor,
                    borderColor.copy(alpha = 0.25f)
                )
            )
        } else {
            FrostedGlassBorderBrush
        }
    }

    val cardBgBrush = remember(backgroundColor, glowAccentColor, isDark) {
        val effectiveBg = if (isDark && backgroundColor == Color.White) {
            Color(0xFF1E293B)
        } else {
            backgroundColor
        }
        when {
            effectiveBg != null -> Brush.verticalGradient(
                listOf(effectiveBg, effectiveBg.copy(alpha = 0.95f))
            )
            glowAccentColor != null -> if (isDark) {
                Brush.linearGradient(
                    listOf(
                        Color(0xFF1E293B).copy(alpha = 0.96f),
                        glowAccentColor.copy(alpha = 0.14f),
                        Color(0xFF0F172A).copy(alpha = 0.96f)
                    )
                )
            } else {
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.97f),
                        glowAccentColor.copy(alpha = 0.05f),
                        Color(0xFFF8FAFC).copy(alpha = 0.95f)
                    )
                )
            }
            else -> FrostedGlassGradient
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(cardBgBrush)
            .border(
                border = BorderStroke(1.dp, borderBrush),
                shape = shape
            )
            .then(clickModifier)
    ) {
        // Subtle specular highlight line across top edge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TopHighlightBrush)
        )

        // Crisp foreground content
        content()
    }
}

@Composable
fun GlassStatCard(
    title: String,
    value: String,
    subtitle: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    unit: String? = null
) {
    val dynamicValueFontSize = remember(value) {
        when {
            value.length > 13 -> 14.sp
            value.length > 10 -> 16.sp
            value.length > 7 -> 18.sp
            else -> 20.sp
        }
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp),
        cornerRadius = 18.dp,
        glowAccentColor = accentColor
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .requiredSize(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(accentColor.copy(alpha = 0.12f))
                                .border(0.5.dp, accentColor.copy(alpha = 0.30f), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = dynamicValueFontSize,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!unit.isNullOrBlank()) {
                        Text(
                            text = unit,
                            style = MaterialTheme.typography.bodySmall,
                            color = accentColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GlassBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(SharedBadgeShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), SharedBadgeShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    gradient: Brush = GlassAccentBlueGradient,
    textColor: Color = TextPureWhite,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(SharedButtonShape)
            .background(if (enabled) gradient else DisabledButtonBrush)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.50f else 0.2f), SharedButtonShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = text,
                color = textColor,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GlassTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    val scrollState = rememberScrollState()

    // Smoothly bring selected tab into view
    LaunchedEffect(selectedIndex, tabs.size) {
        if (selectedIndex in tabs.indices && scrollState.maxValue > 0) {
            val step = scrollState.maxValue.toFloat() / (tabs.size - 1).coerceAtLeast(1)
            val targetScroll = (selectedIndex * step).toInt().coerceIn(0, scrollState.maxValue)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tabName ->
            GlassTabPill(
                text = tabName,
                selected = selectedIndex == index,
                onClick = { onTabSelected(index) },
                modifier = Modifier.wrapContentWidth(unbounded = true)
            )
        }
    }
}

@Composable
private fun GlassTabPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 84.dp)
            .heightIn(min = 42.dp)
            .clip(SharedTabPillShape)
            .background(if (selected) GlassAccentBlueGradient else GlassCardGradient)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) PmpCyan.copy(alpha = 0.75f) else GlassBorder,
                shape = SharedTabPillShape
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = 9.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) TextPureWhite else TextDark,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    isNumber: Boolean = false,
    readOnly: Boolean = false
) {
    val isDark = PmpThemeController.isDarkModeActive
    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextDark,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = placeholder,
                    color = TextSubtle,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null, tint = PmpBlue) }
            },
            readOnly = readOnly,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = if (isDark) Color(0xFF1E293B) else Color.White,
                unfocusedContainerColor = GlassInput,
                focusedBorderColor = PmpBlue,
                unfocusedBorderColor = GlassBorder,
                focusedTextColor = TextDark,
                unfocusedTextColor = TextDark,
                cursorColor = PmpBlue
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isNumber) KeyboardType.Decimal else KeyboardType.Text
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun GlassDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextDark,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(GlassInput)
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedOption.ifEmpty { "اختر..." },
                    color = if (selectedOption.isNotEmpty()) TextDark else TextSubtle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = PmpBlue,
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(PmpWhiteSurface)
                    .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option,
                                color = if (option == selectedOption) PmpBlue else TextDark,
                                fontWeight = if (option == selectedOption) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Samsung One UI-style interactive pull-down page container:
 * - Moves the entire page content downward together as a single unit following the user's finger in real time.
 * - Applies natural hyperbolic elastic resistance for a smooth, realistic dragging experience.
 * - Reveals an atmospheric empty One UI background area at the top of the screen while pulled down.
 * - Smoothly animates the page back to its original position (0f) using a spring animation upon release.
 * - Uses NestedScrollConnection + non-intrusive pointer tracking so buttons, inputs, horizontal rows,
 *   and normal vertical scrolling work without any interference.
 */
@Composable
fun OneUiPullDownContainer(
    modifier: Modifier = Modifier,
    pageTitle: String = "",
    screenKey: Any? = null,
    onDeepPullTriggered: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val isDark = PmpThemeController.isDarkModeActive

    // Raw accumulated finger pull distance (in pixels); mapped through hyperbolic elastic damping
    var rawPullPx by remember { mutableFloatStateOf(0f) }
    val animatedOffsetPx = remember { Animatable(0f) }
    var lastNestedScrollFrameTime by remember { mutableLongStateOf(0L) }
    var deepPullFiredInGesture by remember { mutableStateOf(false) }

    // Reset immediately when switching between screens
    LaunchedEffect(screenKey) {
        rawPullPx = 0f
        deepPullFiredInGesture = false
        animatedOffsetPx.snapTo(0f)
    }

    val pullRevealBgBrush = remember(isDark) {
        if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF060911),
                    Color(0xFF0B1120),
                    Color(0xFF0F172A)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFE2E8F0),
                    Color(0xFFEDF2F7),
                    Color(0xFFF8FAFC)
                )
            )
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(pullRevealBgBrush)
            .testTag("one_ui_pull_down_root")
    ) {
        val containerHeightPx = if (maxHeight != androidx.compose.ui.unit.Dp.Infinity && maxHeight.value.isFinite() && maxHeight > 0.dp) {
            with(density) { maxHeight.toPx().coerceIn(600f, 4000f) }
        } else {
            1800f
        }
        val maxElasticTranslationPx = remember(containerHeightPx) {
            (containerHeightPx * 0.44f).coerceIn(320f, 1800f)
        }
        val deepPullThresholdPx = remember(maxElasticTranslationPx) {
            maxElasticTranslationPx * 0.58f
        }

        fun computeElasticOffset(raw: Float): Float =
            computeElasticOffset(raw, maxElasticTranslationPx)


        fun computeInverseRawFromElastic(elasticPx: Float): Float {
            val clamped = elasticPx.coerceIn(0f, maxElasticTranslationPx * 0.96f)
            if (clamped <= 0f) return 0f
            val frac = clamped / maxElasticTranslationPx
            return (maxElasticTranslationPx * (frac / (1f - frac))) / 0.65f
        }

        fun applyRealtimeFingerDelta(deltaY: Float) {
            if (deltaY == 0f) return
            // Sync rawPullPx with current visual offset if a spring animation was interrupted mid-flight
            if (animatedOffsetPx.isRunning) {
                rawPullPx = computeInverseRawFromElastic(animatedOffsetPx.value)
            }
            if (deltaY > 0f) {
                rawPullPx = (rawPullPx + deltaY).coerceAtMost(maxElasticTranslationPx * 4.5f)
            } else {
                // Moving upward while pulled down: reduce elastic offset smoothly 1:1 with upward finger motion
                val currentElastic = computeElasticOffset(rawPullPx)
                val nextElastic = (currentElastic + deltaY).coerceAtLeast(0f)
                rawPullPx = computeInverseRawFromElastic(nextElastic)
            }
            val targetElastic = computeElasticOffset(rawPullPx)
            if (targetElastic >= deepPullThresholdPx && !deepPullFiredInGesture) {
                deepPullFiredInGesture = true
                onDeepPullTriggered?.invoke()
            }
            scope.launch {
                animatedOffsetPx.snapTo(targetElastic)
            }
        }

        fun springBackToOrigin(initialVelocityPx: Float = 0f) {
            deepPullFiredInGesture = false
            if (rawPullPx <= 0.5f && animatedOffsetPx.value <= 0.5f) {
                rawPullPx = 0f
                return
            }
            rawPullPx = 0f
            scope.launch {
                animatedOffsetPx.animateTo(
                    targetValue = 0f,
                    initialVelocity = initialVelocityPx.coerceIn(-2400f, 1200f),
                    animationSpec = spring(
                        dampingRatio = 0.84f,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }

        val nestedScrollConnection = remember(maxElasticTranslationPx) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y < 0f && animatedOffsetPx.value > 0.5f) {
                        lastNestedScrollFrameTime = System.nanoTime()
                        val beforeElastic = animatedOffsetPx.value
                        applyRealtimeFingerDelta(available.y)
                        val afterElastic = computeElasticOffset(rawPullPx)
                        val consumedY = (afterElastic - beforeElastic).coerceIn(available.y, 0f)
                        return Offset(0f, consumedY)
                    }
                    return Offset.Zero
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    if (source == NestedScrollSource.UserInput && available.y > 0f) {
                        lastNestedScrollFrameTime = System.nanoTime()
                        applyRealtimeFingerDelta(available.y)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (animatedOffsetPx.value > 0.5f) {
                        springBackToOrigin(available.y)
                        return Velocity(0f, available.y)
                    }
                    return Velocity.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (animatedOffsetPx.value > 0.5f) {
                        springBackToOrigin(available.y)
                    }
                    return Velocity.Zero
                }
            }
        }

        val isPulledDown by remember {
            derivedStateOf { animatedOffsetPx.value > 1f }
        }
        val titleRevealThresholdPx = remember(density) { with(density) { 56.dp.toPx() } }
        val showPulledTitle by remember(titleRevealThresholdPx) {
            derivedStateOf { animatedOffsetPx.value > titleRevealThresholdPx }
        }

        // Revealed Empty One UI Background Area at the Top of the Screen
        if (isPulledDown) {
            val currentOffsetPx = animatedOffsetPx.value
            val pullProgress = (currentOffsetPx / (maxElasticTranslationPx * 0.55f)).coerceIn(0f, 1f)
            val revealedHeightDp = with(density) { currentOffsetPx.toDp() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(revealedHeightDp)
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = (pullProgress * 1.15f).coerceIn(0f, 1f)
                            scaleX = 0.92f + (0.08f * pullProgress)
                            scaleY = 0.92f + (0.08f * pullProgress)
                        }
                        .padding(horizontal = 24.dp)
                ) {
                    if (pageTitle.isNotBlank() && showPulledTitle) {
                        Text(
                            text = pageTitle,
                            color = PmpBrandNavy.copy(alpha = 0.85f),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    // Subtle One UI pull-down pill handle at bottom of revealed area
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(4.5.dp)
                            .clip(CircleShape)
                            .background(PmpBrandNavy.copy(alpha = 0.22f))
                    )
                }
            }
        }

        val activePullShape = remember {
            RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
        }
        val flatPullShape = remember {
            RoundedCornerShape(0.dp)
        }

        // Unified Page Unit Moving Together in Real Time
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .pointerInput(maxElasticTranslationPx) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var totalDy = 0f
                        var totalDx = 0f
                        var isNonScrollablePullActive = false
                        val pointerId = down.id

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                            if (change == null || event.changes.none { it.pressed }) {
                                break
                            }

                            val delta = change.positionChange()
                            totalDy += delta.y
                            totalDx += delta.x

                            val nestedHandledRecently = (System.nanoTime() - lastNestedScrollFrameTime) < 32_000_000L
                            if (!nestedHandledRecently && !change.isConsumed) {
                                if (!isNonScrollablePullActive) {
                                    // Activate fallback pull on non-scrollable areas (headers, static pages) only after clear vertical downward drag
                                    if (totalDy > touchSlop && abs(totalDy) > abs(totalDx) * 1.25f) {
                                        isNonScrollablePullActive = true
                                    }
                                }
                                if (isNonScrollablePullActive && (delta.y > 0f || animatedOffsetPx.value > 0.5f)) {
                                    applyRealtimeFingerDelta(delta.y)
                                    change.consume()
                                }
                            }
                        }

                        // Guarantee spring return whenever finger leaves the screen
                        if (animatedOffsetPx.value > 0.5f || rawPullPx > 0.5f) {
                            springBackToOrigin(0f)
                        }
                    }
                }
                .graphicsLayer {
                    val liveOffsetPx = animatedOffsetPx.value
                    val liveProgress = (liveOffsetPx / (maxElasticTranslationPx * 0.55f)).coerceIn(0f, 1f)
                    translationY = liveOffsetPx
                    shape = if (liveOffsetPx > 1f) activePullShape else flatPullShape
                    clip = liveOffsetPx > 1f
                    shadowElevation = if (liveOffsetPx > 2f) (liveProgress * 14f) else 0f
                }
                .background(GlassBackgroundBrush)
        ) {
            content()
        }
    }
}

internal fun computeElasticOffset(raw: Float, maxElasticTranslationPx: Float): Float {
    if (raw <= 0f || maxElasticTranslationPx <= 0f) return 0f
    val normalized = raw / maxElasticTranslationPx
    // Natural Samsung One UI rubber-band curve: responsive initial tracking with progressive resistance
    return maxElasticTranslationPx * (1f - (1f / (1f + normalized * 0.65f)))
}




package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

private val TopHighlightBrush = Brush.horizontalGradient(
    colors = listOf(
        Color.Transparent,
        Color.White.copy(alpha = 0.90f),
        Color.Transparent
    )
)

private val DisabledButtonBrush = Brush.linearGradient(
    listOf(Color(0xFFE2EAF2), Color(0xFFD6E2EE))
)

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
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    val borderBrush = remember(borderColor) {
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

    val cardBgBrush = remember(backgroundColor, glowAccentColor) {
        when {
            backgroundColor != null -> Brush.verticalGradient(
                listOf(backgroundColor, backgroundColor.copy(alpha = 0.95f))
            )
            glowAccentColor != null -> Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = 0.97f),
                    glowAccentColor.copy(alpha = 0.05f),
                    Color(0xFFF8FAFC).copy(alpha = 0.95f)
                )
            )
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
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
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
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (enabled) gradient else DisabledButtonBrush)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.50f else 0.2f), shape)
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
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in tabs.indices && scrollState.maxValue > 0) {
            val step = scrollState.maxValue.toFloat() / (tabs.size - 1).coerceAtLeast(1)
            val targetScroll = (selectedIndex * step).toInt().coerceIn(0, scrollState.maxValue)
            scrollState.animateScrollTo(targetScroll)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tabName ->
            GlassTabPill(
                text = tabName,
                selected = selectedIndex == index,
                onClick = { onTabSelected(index) },
                modifier = Modifier.wrapContentWidth()
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
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 72.dp)
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) GlassAccentBlueGradient else GlassCardGradient)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) PmpCyan.copy(alpha = 0.7f) else GlassBorder,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = 14.dp,
                vertical = 8.dp
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
            overflow = TextOverflow.Clip,
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
                focusedContainerColor = Color.White,
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
                    .background(Color.White)
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

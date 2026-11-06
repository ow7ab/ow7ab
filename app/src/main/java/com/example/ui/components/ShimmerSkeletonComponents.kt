package com.example.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/**
 * Unified UI data state for sections and screens in PMP PAINTS:
 * - Loading: Displays screen-specific Skeleton layout with smooth Shimmer animation.
 * - Success: Displays the real loaded content with a gentle fade-in transition.
 * - Empty: Displays a clean empty state with helpful guidance.
 * - Error: Displays an actionable error message with a Retry button.
 */
sealed interface PmpSectionUiState {
    data object Loading : PmpSectionUiState
    data object Success : PmpSectionUiState
    data class Empty(
        val title: String = "لا توجد بيانات مسجلة حتى الآن",
        val subtitle: String = "ستظهر السجلات والبيانات هنا فور إضافتها أو مزامنتها"
    ) : PmpSectionUiState
    data class Error(
        val message: String = "تعذر تحميل البيانات في الوقت الحالي",
        val canRetry: Boolean = true
    ) : PmpSectionUiState
}

/**
 * Shared CompositionLocal holding a single normalized shimmer progress [0f..1f]
 * so all skeleton elements on a screen share ONE lightweight animation clock
 * instead of spawning multiple InfiniteTransitions.
 */
val LocalShimmerProgress = compositionLocalOf { 0.35f }
val LocalReduceMotionEnabled = compositionLocalOf { false }

/**
 * Checks whether the user has enabled "Reduce Motion / Remove Animations" in Android Accessibility settings.
 */
@Composable
fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        }.getOrDefault(false)
    }
}

/**
 * Provides a single shared shimmer animation clock to all child [ShimmerBox] and Skeleton components.
 * Automatically stops when removed from composition or when Reduce Motion is enabled.
 */
@Composable
fun ProvideShimmerEffect(
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val reduceMotion = rememberReduceMotionEnabled()
    val progress = if (enabled && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "pmp_shared_shimmer_clock")
        val animated by transition.animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1150, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "pmp_shimmer_progress"
        )
        animated
    } else {
        0.5f
    }

    CompositionLocalProvider(
        LocalShimmerProgress provides progress,
        LocalReduceMotionEnabled provides reduceMotion
    ) {
        content()
    }
}

/**
 * High-performance modifier that draws the PMP PAINTS frosted shimmer gradient using [drawWithCache]
 * and the shared [LocalShimmerProgress] clock.
 */
@Composable
fun Modifier.pmpShimmerBackground(
    cornerRadius: Dp = 10.dp,
    baseColor: Color = Color(0xFFE2E8F0),
    highlightColor: Color = Color(0xFFF8FAFC),
    accentTint: Color = PmpBrandNavy.copy(alpha = 0.06f)
): Modifier {
    val progress = LocalShimmerProgress.current
    val reduceMotion = LocalReduceMotionEnabled.current

    return this.drawWithCache {
        val radiusPx = cornerRadius.toPx()
        val width = size.width.coerceAtLeast(1f)
        val height = size.height.coerceAtLeast(1f)
        val shimmerBandWidth = (width * 0.65f).coerceAtLeast(120f)

        onDrawBehind {
            if (reduceMotion) {
                drawRoundRect(
                    color = baseColor.copy(alpha = 0.75f),
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )
            } else {
                val startX = (progress * width) - shimmerBandWidth * 0.5f
                val endX = startX + shimmerBandWidth
                val brush = Brush.linearGradient(
                    colors = listOf(
                        baseColor.copy(alpha = 0.82f),
                        accentTint.compositeOver(highlightColor),
                        Color.White.copy(alpha = 0.96f),
                        accentTint.compositeOver(highlightColor),
                        baseColor.copy(alpha = 0.82f)
                    ),
                    start = Offset(startX, 0f),
                    end = Offset(endX, height * 0.35f)
                )
                drawRoundRect(
                    brush = brush,
                    cornerRadius = CornerRadius(radiusPx, radiusPx)
                )
            }
        }
    }
}

private fun Color.compositeOver(background: Color): Color {
    val fgA = this.alpha
    val bgA = background.alpha * (1f - fgA)
    val outA = fgA + bgA
    if (outA == 0f) return Color.Transparent
    return Color(
        red = (this.red * fgA + background.red * bgA) / outA,
        green = (this.green * fgA + background.green * bgA) / outA,
        blue = (this.blue * fgA + background.blue * bgA) / outA,
        alpha = outA
    )
}

/**
 * Basic building block for a rounded skeleton bar or icon placeholder.
 */
@Composable
fun ShimmerBlock(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    width: Dp? = null,
    cornerRadius: Dp = 8.dp,
    baseColor: Color = Color(0xFFE2E8F0)
) {
    val sizeMod = if (width != null) {
        modifier.width(width).height(height)
    } else {
        modifier.height(height)
    }
    Box(
        modifier = sizeMod
            .clip(RoundedCornerShape(cornerRadius))
            .pmpShimmerBackground(cornerRadius = cornerRadius, baseColor = baseColor)
    )
}

/**
 * Circular skeleton placeholder for avatars, emblems, or status icons.
 */
@Composable
fun ShimmerCircle(
    size: Dp = 36.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .pmpShimmerBackground(cornerRadius = size / 2)
    )
}

/**
 * Skeleton matching [GlassStatCard] exact dimensions (`minHeight = 104.dp`, `cornerRadius = 18.dp`).
 */
@Composable
fun ShimmerStatCardSkeleton(
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 104.dp),
        cornerRadius = 18.dp
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    ShimmerCircle(size = 8.dp)
                    ShimmerBlock(modifier = Modifier.fillMaxWidth(0.68f), height = 12.dp, cornerRadius = 6.dp)
                }
                ShimmerBlock(width = 30.dp, height = 30.dp, cornerRadius = 9.dp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShimmerBlock(modifier = Modifier.fillMaxWidth(0.58f), height = 22.dp, cornerRadius = 7.dp)
                ShimmerBlock(width = 28.dp, height = 12.dp, cornerRadius = 5.dp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBlock(modifier = Modifier.fillMaxWidth(0.45f), height = 10.dp, cornerRadius = 5.dp)
        }
    }
}

/**
 * Skeleton matching a list row card (Invoices, Treasury movements, Stock moves, Debts, Products).
 */
@Composable
fun ShimmerListRowCardSkeleton(
    modifier: Modifier = Modifier,
    showTrailingBadge: Boolean = true,
    showBottomMetaRow: Boolean = false
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 16.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    ShimmerBlock(width = 38.dp, height = 38.dp, cornerRadius = 11.dp)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShimmerBlock(modifier = Modifier.fillMaxWidth(0.65f), height = 14.dp, cornerRadius = 6.dp)
                        ShimmerBlock(modifier = Modifier.fillMaxWidth(0.48f), height = 11.dp, cornerRadius = 5.dp)
                    }
                }
                if (showTrailingBadge) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ShimmerBlock(width = 76.dp, height = 16.dp, cornerRadius = 6.dp)
                        ShimmerBlock(width = 52.dp, height = 18.dp, cornerRadius = 8.dp)
                    }
                }
            }
            if (showBottomMetaRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ShimmerBlock(width = 88.dp, height = 11.dp, cornerRadius = 5.dp)
                    ShimmerBlock(width = 88.dp, height = 11.dp, cornerRadius = 5.dp)
                    ShimmerBlock(width = 72.dp, height = 11.dp, cornerRadius = 5.dp)
                }
            }
        }
    }
}

/**
 * Skeleton matching a horizontal row of tab pills (`GlassTabRow`).
 */
@Composable
fun ShimmerTabRowSkeleton(
    tabCount: Int = 4,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(tabCount) { index ->
            ShimmerBlock(
                width = if (index == 0) 98.dp else 86.dp,
                height = 40.dp,
                cornerRadius = 12.dp
            )
        }
    }
}

/**
 * Host composable that smoothly transitions between Skeleton Loading, Real Content, Empty State, and Error State.
 * - Stops Shimmer animation immediately once `state` is no longer `Loading`.
 * - Uses a fast, non-jumping fade transition (`220ms`) when data finishes loading.
 */
@Composable
fun PmpAnimatedSectionHost(
    state: PmpSectionUiState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    skeletonContent: @Composable () -> Unit,
    emptyContent: (@Composable (PmpSectionUiState.Empty) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 220)) togetherWith
                fadeOut(animationSpec = tween(durationMillis = 160))
        },
        contentKey = { target ->
            when (target) {
                is PmpSectionUiState.Loading -> "loading"
                is PmpSectionUiState.Success -> "success"
                is PmpSectionUiState.Empty -> "empty"
                is PmpSectionUiState.Error -> "error"
            }
        },
        label = "pmp_section_skeleton_transition",
        modifier = modifier
    ) { currentState ->
        when (currentState) {
            is PmpSectionUiState.Loading -> {
                ProvideShimmerEffect(enabled = true) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pmp_skeleton_loading_container")
                            .semantics { contentDescription = "جاري تحميل البيانات" }
                    ) {
                        skeletonContent()
                    }
                }
            }
            is PmpSectionUiState.Success -> {
                content()
            }
            is PmpSectionUiState.Empty -> {
                if (emptyContent != null) {
                    emptyContent(currentState)
                } else {
                    PmpEmptyStateCard(
                        title = currentState.title,
                        subtitle = currentState.subtitle
                    )
                }
            }
            is PmpSectionUiState.Error -> {
                PmpErrorStateCard(
                    message = currentState.message,
                    onRetry = if (currentState.canRetry) onRetry else null
                )
            }
        }
    }
}

@Composable
fun PmpEmptyStateCard(
    title: String,
    subtitle: String,
    icon: ImageVector = Icons.Default.Inbox,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pmp_empty_state_card"),
        cornerRadius = 18.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PmpBrandNavy.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = PmpBrandNavy,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                color = TextDark,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun PmpErrorStateCard(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pmp_error_state_card"),
        cornerRadius = 18.dp,
        borderColor = PmpRed.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(PmpRed.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = PmpRed,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = message,
                color = TextDark,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                GlassButton(
                    text = "إعادة المحاولة",
                    icon = Icons.Default.Refresh,
                    gradient = GlassAccentBlueGradient,
                    onClick = onRetry,
                    modifier = Modifier.widthIn(min = 160.dp)
                )
            }
        }
    }
}

// =========================================================================================
// Screen-Specific Skeleton Layouts (Matching Real Component Geometries)
// =========================================================================================

/**
 * 1. Dashboard Skeleton:
 * Matches the 2x2 Financial Stat Cards, Quick Access Grid, Cashboxes Strip, and Recent Movements.
 */
@Composable
fun DashboardSkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Row 1 of Financial Stat Cards (SDG & EGP)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        // Row 2 of Stat Cards (Active Cashboxes & Low Stock Alert)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        // Quick Access Modules Card Skeleton
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ShimmerBlock(width = 140.dp, height = 16.dp, cornerRadius = 6.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    repeat(3) {
                        ShimmerBlock(
                            modifier = Modifier.weight(1f),
                            height = 68.dp,
                            cornerRadius = 14.dp
                        )
                    }
                }
            }
        }
        // Recent Movements List Skeleton
        repeat(3) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true)
        }
    }
}

/**
 * 2. Sales & Invoices & Customers Skeleton:
 * Matches Invoice cards / Customer statement cards with line items, totals, and status badges.
 */
@Composable
fun SalesSkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        repeat(4) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
        }
    }
}

/**
 * 3. Purchases & Raw Materials Skeleton:
 * Matches Raw Material selector bar, procurement form fields, and purchase history cards.
 */
@Composable
fun PurchasesSkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShimmerBlock(width = 165.dp, height = 16.dp, cornerRadius = 6.dp)
                ShimmerBlock(modifier = Modifier.fillMaxWidth(), height = 48.dp, cornerRadius = 12.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ShimmerBlock(modifier = Modifier.weight(1f), height = 48.dp, cornerRadius = 12.dp)
                    ShimmerBlock(modifier = Modifier.weight(1f), height = 48.dp, cornerRadius = 12.dp)
                }
                ShimmerBlock(modifier = Modifier.fillMaxWidth(), height = 44.dp, cornerRadius = 14.dp)
            }
        }
        repeat(3) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
        }
    }
}

/**
 * 4. Treasury & Cashboxes Skeleton:
 * Matches Cashbox balance cards and financial movement rows.
 */
@Composable
fun TreasurySkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        repeat(4) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = false)
        }
    }
}

/**
 * 5. Inventory & Finished Products Skeleton:
 * Matches Warehouse filter pills and Product stock cards.
 */
@Composable
fun InventorySkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ShimmerTabRowSkeleton(tabCount = 3)
        repeat(4) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
        }
    }
}

/**
 * 6. Factory & Production Batches Skeleton:
 * Matches Raw material stock gauges and Production batch cards.
 */
@Composable
fun FactorySkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        repeat(3) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
        }
    }
}

/**
 * 7. Reports & Financial Analytics & Debts Skeleton:
 * Matches 4 KPI summary cards, filter bar, and report table/invoice/debt rows.
 */
@Composable
fun ReportsSkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
            ShimmerStatCardSkeleton(modifier = Modifier.weight(1f))
        }
        repeat(4) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
        }
    }
}

/**
 * 8. Price List (PosterPriceScreen) Skeleton:
 * Matches price table cards with 250g/900g badges and discounted price columns.
 */
@Composable
fun PosterPricesSkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(5) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = false)
        }
    }
}

/**
 * 9. Users & Permissions Skeleton:
 * Matches employee account cards with role badges and permission tags.
 */
@Composable
fun UsersSkeletonSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(3) {
            ShimmerListRowCardSkeleton(showTrailingBadge = true, showBottomMetaRow = true)
        }
    }
}

/**
 * 10. App Update Check Skeleton (for SettingsScreen when checking version.json / Firestore):
 */
@Composable
fun UpdateVersionCardSkeleton(modifier: Modifier = Modifier) {
    ProvideShimmerEffect(enabled = true) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFF8FAFC))
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                .padding(14.dp)
                .testTag("version_card_shimmer_skeleton")
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShimmerBlock(width = 150.dp, height = 15.dp, cornerRadius = 6.dp)
                    ShimmerBlock(width = 68.dp, height = 20.dp, cornerRadius = 8.dp)
                }
                ShimmerBlock(modifier = Modifier.fillMaxWidth(0.85f), height = 11.dp, cornerRadius = 5.dp)
                ShimmerBlock(modifier = Modifier.fillMaxWidth(0.60f), height = 11.dp, cornerRadius = 5.dp)
            }
        }
    }
}

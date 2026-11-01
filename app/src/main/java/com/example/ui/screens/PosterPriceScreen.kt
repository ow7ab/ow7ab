package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.PosterPriceEntity
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.PosterImageExporter
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PosterPriceScreen(
    viewModel: PmpViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val posterPrices by viewModel.posterPrices.collectAsState()
    val availablePaintColors by viewModel.availablePaintColors.collectAsState()
    val format = remember { NumberFormat.getNumberInstance(Locale.US) }

    var discountPercent by remember { mutableFloatStateOf(15f) }
    var selectedMonth by remember { mutableStateOf("أكتوبر") }
    var selectedRegionCurrency by remember { mutableStateOf("SDG") }

    // Multi-selection of paint kinds to appear on the price poster (User Requirement 11)
    val defaultPosterKinds = remember {
        listOf("أبيض", "ألوان", "فضي", "ذهبي", "ورنيش")
    }
    var selectedPosterKinds by remember {
        mutableStateOf(defaultPosterKinds.toSet())
    }
    var showSelectKindsDialog by remember { mutableStateOf(false) }

    // Editing prices state
    var editingWeight by remember { mutableStateOf("900") }
    var editingKind by remember { mutableStateOf("أبيض") }
    var newPriceText by remember { mutableStateOf("") }
    var showEditDialog by remember { mutableStateOf(false) }

    var showAddColorDialog by remember { mutableStateOf(false) }
    var newColorNameInput by remember { mutableStateOf("") }

    var isExportingPosterImage by remember { mutableStateOf(false) }

    val weights = listOf("250", "900")

    val defaults250 = mapOf(
        "أبيض" to 500000L,
        "ألوان" to 490000L,
        "فضي" to 510000L,
        "ذهبي" to 520000L,
        "ورنيش" to 495000L
    )
    val defaults900 = mapOf(
        "أبيض" to 385000L,
        "ألوان" to 375000L,
        "فضي" to 390000L,
        "ذهبي" to 400000L,
        "ورنيش" to 375000L
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Modern Header Card
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "بوستر الأسعار الرسمي المعتمد",
                                style = MaterialTheme.typography.titleLarge,
                                color = PmpBrandNavy,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                            Text(
                                text = "تصميم عصري مطابق للائحة أسعار PMP الرسمية",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        GlassBadge(text = "طراز عصري 2026", color = Color(0xFF6B125B))
                    }

                    // Regional Currency Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedRegionCurrency == "SDG",
                            onClick = { selectedRegionCurrency = "SDG" },
                            label = { Text("🇸🇩 مخازن السودان وحلفا (SDG)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF6B125B),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = selectedRegionCurrency == "EGP",
                            onClick = { selectedRegionCurrency = "EGP" },
                            label = { Text("🇪🇬 مخازن المصنع بمصر (EGP)", fontSize = 11.5.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1E40AF),
                                selectedLabelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Month & Discount Controls Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = selectedMonth,
                            onValueChange = { selectedMonth = it },
                            label = { Text("شهر اللائحة", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Discount Slider Control
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFEF2F2),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA)),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(56.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("نسبة الخصم", fontSize = 10.sp, color = TextMuted)
                                    Text("${discountPercent.toInt()}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                }
                                Slider(
                                    value = discountPercent,
                                    onValueChange = { discountPercent = it },
                                    valueRange = 0f..40f,
                                    steps = 40,
                                    modifier = Modifier.width(100.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFFDC2626),
                                        activeTrackColor = Color(0xFFDC2626)
                                    )
                                )
                            }
                        }
                    }

                    // Multi-selection button for choosing poster kinds
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF3E8FF),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8B4FE)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showSelectKindsDialog = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Checklist,
                                    contentDescription = null,
                                    tint = Color(0xFF6B125B),
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "تحديد الأصناف المراد ظهورها بالبوستر",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                        color = Color(0xFF4A044E)
                                    )
                                    Text(
                                        text = "محدد حالياً: (${selectedPosterKinds.size}) أصناف [${selectedPosterKinds.joinToString("، ")}]",
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF6B125B),
                                        maxLines = 1
                                    )
                                }
                            }

                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "تعديل الاختيار",
                                tint = Color(0xFF6B125B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Export & Share Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isExportingPosterImage = true
                                val result = PosterImageExporter.exportAndSharePoster(
                                    context = context,
                                    posterPrices = posterPrices,
                                    discountPercent = discountPercent,
                                    selectedKinds = selectedPosterKinds.toList(),
                                    monthTitle = selectedMonth,
                                    currency = selectedRegionCurrency
                                )
                                isExportingPosterImage = false
                                result.onSuccess {
                                    viewModel.postMessage("تم تجهيز بوستر الأسعار الرسمي لشهر $selectedMonth ومشاركته بنجاح")
                                }.onFailure { err ->
                                    viewModel.postMessage("تعذر تصدير البوستر: ${err.message}", isError = true)
                                }
                            },
                            enabled = !isExportingPosterImage && selectedPosterKinds.isNotEmpty(),
                            modifier = Modifier
                                .weight(1.3f)
                                .height(46.dp)
                                .testTag("export_poster_image_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6B125B)
                            )
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isExportingPosterImage) "جارٍ التجهيز..." else "مشاركة البوستر",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                newColorNameInput = ""
                                showAddColorDialog = true
                            },
                            modifier = Modifier
                                .weight(0.7f)
                                .height(46.dp)
                                .testTag("add_custom_color_poster_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("＋ صنف جديد", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Live In-App Modern Poster Preview matching attached image
        item {
            Text(
                text = "معاينة البوستر الحي المعتمد (طراز PMP الرسمي):",
                style = MaterialTheme.typography.titleMedium,
                color = PmpBrandNavy,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 4.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Logo & Splatters Preview
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF06B6D4))
                        )
                        Image(
                            painter = painterResource(id = R.drawable.pmp_brand_logo),
                            contentDescription = "PMP Paints Logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(42.dp)
                                .width(120.dp)
                        )
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEC4899))
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Purple Brush Banner Preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF7E1064), Color(0xFF4A044E))
                                )
                            )
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لائحة الأسعار لشهر $selectedMonth",
                            color = Color(0xFFFDE047),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Two-Column Weight Container (#5B0E4E)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF5B0E4E),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Top Weight Headers (900 on Left, 250 on Right)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "900",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(24.dp)
                                        .background(Color(0xFF831843))
                                )
                                Text(
                                    text = "250",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Subheaders Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF4A044E))
                                    .padding(vertical = 6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Text("السعر", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("الصنف", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(18.dp)
                                        .background(Color(0xFF831843))
                                )
                                Row(
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Text("السعر", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("الصنف", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Dynamic Rows
                            selectedPosterKinds.forEachIndexed { index, kind ->
                                val isLilac = index % 2 == 1
                                val rowBg = if (isLilac) Color(0xFFF5E8F3) else Color.White

                                val entity250 = posterPrices.find { it.weight == "250" && it.kind == kind }
                                val price250 = if (selectedRegionCurrency == "EGP") {
                                    if (entity250 != null && entity250.priceEgp > 0) entity250.priceEgp.toLong() else 2500L
                                } else {
                                    entity250?.price ?: defaults250[kind] ?: 500000L
                                }

                                val entity900 = posterPrices.find { it.weight == "900" && it.kind == kind }
                                val price900 = if (selectedRegionCurrency == "EGP") {
                                    if (entity900 != null && entity900.priceEgp > 0) entity900.priceEgp.toLong() else 1925L
                                } else {
                                    entity900?.price ?: defaults900[kind] ?: 385000L
                                }

                                val cleanKind = kind.replace("أ", "ا").replace("إ", "ا")

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 900 column
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.SpaceAround,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = format.format(price900),
                                            color = Color(0xFF3B0764),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = cleanKind,
                                            color = Color(0xFF3B0764),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(20.dp)
                                            .background(Color(0xFFE2E8F0))
                                    )

                                    // 250 column
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.SpaceAround,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = format.format(price250),
                                            color = Color(0xFF3B0764),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = cleanKind,
                                            color = Color(0xFF3B0764),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3D Red Splash Discount Badge
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFDC2626), Color(0xFF991B1B))
                                )
                            )
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${discountPercent.toInt()}%",
                                color = Color(0xFFFBBF24),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp
                            )
                            Text(
                                text = "خصم",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Authentic PMP Packaging Bottom Preview Image
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(14.dp))
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.pmp_reference_poster),
                            contentDescription = "PMP Packaging Boxes",
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.BottomCenter,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Pricing Matrix Editor (Allows setting actual price values for each weight & color)
        item {
            Text(
                text = "تعديل الأسعار الأساسية في قاعدة البيانات:",
                style = MaterialTheme.typography.titleMedium,
                color = PmpBrandNavy,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        for (w in weights) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "عبوة $w جم ($selectedRegionCurrency)",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF6B125B),
                        fontSize = 14.sp
                    )
                    GlassBadge(
                        text = if (selectedRegionCurrency == "EGP") "مخزن مصر EGP" else "مخزن حلفا SDG",
                        color = if (selectedRegionCurrency == "EGP") Color(0xFF1E40AF) else Color(0xFF6B125B)
                    )
                }
            }

            item {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        availablePaintColors.forEach { k ->
                            val defaultSdg = if (w == "250") defaults250[k] ?: 500000L else defaults900[k] ?: 385000L
                            val defaultEgp = if (w == "250") 2500L else 1925L
                            val entity = posterPrices.find { it.weight == w && it.kind == k }
                            val currentPrice = if (selectedRegionCurrency == "EGP") {
                                if (entity != null && entity.priceEgp > 0) entity.priceEgp.toLong() else defaultEgp
                            } else {
                                entity?.price ?: defaultSdg
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Checkbox(
                                        checked = k in selectedPosterKinds,
                                        onCheckedChange = { checked ->
                                            selectedPosterKinds = if (checked) {
                                                selectedPosterKinds + k
                                            } else {
                                                selectedPosterKinds - k
                                            }
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6B125B))
                                    )
                                    Column {
                                        Text(
                                            text = "بوهية $k ($w جم)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextDark
                                        )
                                        Text(
                                            text = "السعر الحالي: ${format.format(currentPrice)} $selectedRegionCurrency",
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                }

                                TextButton(
                                    onClick = {
                                        editingWeight = w
                                        editingKind = k
                                        newPriceText = currentPrice.toString()
                                        showEditDialog = true
                                    },
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("تعديل", color = Color(0xFF6B125B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            HorizontalDivider(color = GlassBorder)
                        }
                    }
                }
            }
        }
    }

    // Dialog for Selecting Items to Appear on the Poster (User Requirement 11)
    if (showSelectKindsDialog) {
        AlertDialog(
            onDismissRequest = { showSelectKindsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Checklist, contentDescription = null, tint = Color(0xFF6B125B))
                    Text("تحديد أصناف بوستر الأسعار", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "اختر الأصناف التي ترغب بظهورها في جدول بوستر الأسعار الرسمي:",
                        fontSize = 12.sp,
                        color = TextMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { selectedPosterKinds = availablePaintColors.toSet() },
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Text("تحديد الكل", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { selectedPosterKinds = defaultPosterKinds.toSet() },
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Text("الافتراضية فقط", fontSize = 11.sp)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(availablePaintColors) { kind ->
                            val isChecked = kind in selectedPosterKinds
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isChecked) Color(0xFFF3E8FF) else Color(0xFFF8FAFC),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (isChecked) Color(0xFFD8B4FE) else Color(0xFFE2E8F0)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedPosterKinds = if (isChecked) selectedPosterKinds - kind else selectedPosterKinds + kind
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = kind,
                                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp,
                                        color = if (isChecked) Color(0xFF4A044E) else TextDark
                                    )
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedPosterKinds = if (checked) selectedPosterKinds + kind else selectedPosterKinds - kind
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6B125B))
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSelectKindsDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B125B))
                ) {
                    Text("تم وحفظ الاختيار")
                }
            }
        )
    }

    // Dialog for Editing Price of a specific weight & kind
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(
                    text = "تعديل سعر $editingKind ($editingWeight جم)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "المنطقة المعتمدة للتعديل: ${if (selectedRegionCurrency == "EGP") "مخازن مصر (EGP)" else "مخازن السودان وحلفا (SDG)"}",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = newPriceText,
                        onValueChange = { newPriceText = it },
                        label = { Text("السعر الجديد ($selectedRegionCurrency)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = newPriceText.toDoubleOrNull() ?: 0.0
                        if (parsed > 0) {
                            viewModel.updatePosterPrice(
                                weight = editingWeight,
                                kind = editingKind,
                                price = parsed.toLong(),
                                currency = selectedRegionCurrency
                            )
                        }
                        showEditDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B125B))
                ) {
                    Text("حفظ السعر")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Dialog for Adding a new Paint Color/Kind
    if (showAddColorDialog) {
        AlertDialog(
            onDismissRequest = { showAddColorDialog = false },
            title = { Text("إضافة لون / صنف بوهية جديد للبوستر", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newColorNameInput,
                        onValueChange = { newColorNameInput = it },
                        label = { Text("اسم اللون / الصنف (مثال: برتقالي، لؤلؤي)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newColorNameInput.trim()
                        if (trimmed.isNotBlank()) {
                            viewModel.customColorsList.value = (viewModel.customColorsList.value + trimmed).distinct()
                            selectedPosterKinds = selectedPosterKinds + trimmed
                            viewModel.postMessage("تمت إضافة اللون ($trimmed) بنجاح")
                        }
                        showAddColorDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B125B))
                ) {
                    Text("إضافة")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddColorDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

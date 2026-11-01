package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.local.PosterPriceEntity
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.util.Locale

object PosterImageExporter {

    // Standard paint categories displayed on PMP official posters
    val standardKinds = listOf(
        "أبيض", "ألوان", "فضي", "ذهبي", "ورنيش"
    )

    private val defaults250 = mapOf(
        "أبيض" to 500000L,
        "ابيض" to 500000L,
        "ألوان" to 490000L,
        "الوان" to 490000L,
        "فضي" to 510000L,
        "ذهبي" to 520000L,
        "ورنيش" to 495000L,
        "كريمي" to 490000L,
        "كبدي" to 495000L,
        "بني" to 490000L,
        "أزرق" to 490000L,
        "أحمر" to 495000L,
        "أخضر" to 490000L,
        "أسود" to 490000L,
        "أصفر" to 490000L,
        "بنفسجي" to 495000L,
        "وردي" to 495000L
    )

    private val defaults900 = mapOf(
        "أبيض" to 385000L,
        "ابيض" to 385000L,
        "ألوان" to 375000L,
        "الوان" to 375000L,
        "فضي" to 390000L,
        "ذهبي" to 400000L,
        "ورنيش" to 375000L,
        "كريمي" to 375000L,
        "كبدي" to 380000L,
        "بني" to 375000L,
        "أزرق" to 375000L,
        "أحمر" to 380000L,
        "أخضر" to 375000L,
        "أسود" to 375000L,
        "أصفر" to 375000L,
        "بنفسجي" to 380000L,
        "وردي" to 380000L
    )

    /**
     * Generates a modern, authentic PMP Paints price poster matching the official flyer identity:
     * - Top colorful paint splashes with official PMP logo
     * - Purple brush ribbon banner with customizable month title (e.g. لائحة الأسعار لشهر أكتوبر)
     * - 2-column weight table (250 on right, 900 on left in RTL) inside a deep royal purple container (#5B0E4E)
     * - Subheaders "الصنف" & "السعر", alternating white and soft lilac rows with bold numbers
     * - 3D Red splash badge with golden discount text (e.g. 15% خصم)
     * - Authentic PMP packaging boxes on colorful paint-splattered floor at the bottom
     */
    fun exportAndSharePoster(
        context: Context,
        posterPrices: List<PosterPriceEntity>,
        discountPercent: Float,
        selectedKinds: List<String> = emptyList(),
        monthTitle: String = "أكتوبر",
        currency: String = "SDG"
    ): Result<File> {
        return runCatching {
            val effectiveKinds = if (selectedKinds.isNotEmpty()) {
                selectedKinds
            } else {
                standardKinds
            }

            val width = 1080
            val height = 1620
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val numberFormat = NumberFormat.getNumberInstance(Locale.US)

            // 1. Clean Canvas Background
            canvas.drawColor(Color.WHITE)

            // 2. Draw Top Paint Splashes & PMP Logo
            // Decorative colorful paint splashes
            val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            splashPaint.color = Color.parseColor("#06B6D4") // Cyan splash
            canvas.drawCircle(80f, 90f, 65f, splashPaint)
            splashPaint.color = Color.parseColor("#84CC16") // Lime green splash
            canvas.drawCircle(180f, 60f, 40f, splashPaint)
            splashPaint.color = Color.parseColor("#EC4899") // Pink splash
            canvas.drawCircle(width - 100f, 85f, 70f, splashPaint)
            splashPaint.color = Color.parseColor("#F59E0B") // Amber splash
            canvas.drawCircle(width - 200f, 65f, 45f, splashPaint)
            splashPaint.color = Color.parseColor("#8B5CF6") // Violet splash
            canvas.drawCircle(width - 70f, 180f, 50f, splashPaint)

            // Draw Official PMP Logo at Top Center
            var logoDrawn = false
            runCatching {
                val logoBmp = BitmapFactory.decodeResource(context.resources, R.drawable.pmp_brand_logo)
                if (logoBmp != null) {
                    val destRect = Rect(
                        (width / 2 - 220),
                        35,
                        (width / 2 + 220),
                        165
                    )
                    canvas.drawBitmap(logoBmp, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                    logoDrawn = true
                }
            }
            if (!logoDrawn) {
                runCatching {
                    val emblem = ContextCompat.getDrawable(context, R.drawable.ic_pmp_emblem)
                    if (emblem != null) {
                        emblem.setBounds(width / 2 - 80, 35, width / 2 + 80, 165)
                        emblem.draw(canvas)
                    }
                }
            }

            // 3. Purple Brush Ribbon Banner with Month Title
            val bannerY = 185f
            val bannerHeight = 85f
            val purpleBrushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, bannerY, (width).toFloat(), bannerY + bannerHeight,
                    Color.parseColor("#7E1064"),
                    Color.parseColor("#4A044E"),
                    Shader.TileMode.CLAMP
                )
            }
            // Brush-effect jagged rounded banner
            val bannerPath = Path().apply {
                val rect = RectF(60f, bannerY, width - 60f, bannerY + bannerHeight)
                addRoundRect(rect, 24f, 24f, Path.Direction.CW)
            }
            canvas.drawPath(bannerPath, purpleBrushPaint)

            // Banner Title: "لائحة الأسعار لشهر أكتوبر"
            val bannerTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FDE047") // Vibrant yellow
                textSize = 46f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val displayBannerTitle = if (monthTitle.startsWith("لائحة")) monthTitle else "لائحة الأسعار لشهر $monthTitle"
            canvas.drawText(displayBannerTitle, width / 2f, bannerY + 58f, bannerTitlePaint)

            // 4. Two-Column Table Container (Deep Royal Purple Box)
            val tableTop = 295f
            val tableMargin = 40f
            val tableWidth = width - (tableMargin * 2)
            val rowCount = effectiveKinds.size
            val rowHeight = (320f / rowCount.coerceAtLeast(1)).coerceIn(44f, 62f)
            val tableBodyHeight = rowHeight * rowCount
            val headerHeight = 110f // 58f for 250/900 + 52f for subheaders
            val tableHeight = headerHeight + tableBodyHeight

            val tableBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#5B0E4E") // Deep Royal Magenta/Purple
            }
            val tableRect = RectF(tableMargin, tableTop, tableMargin + tableWidth, tableTop + tableHeight)
            canvas.drawRoundRect(tableRect, 22f, 22f, tableBgPaint)

            // Divide Table into 2 Columns: Right (250) and Left (900)
            val colHalfWidth = tableWidth / 2f
            val colLeftX = tableMargin // Left column (900)
            val colRightX = tableMargin + colHalfWidth // Right column (250)

            // Top Column Weight Headers (900 on Left, 250 on Right)
            val weightTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 44f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("900", colLeftX + (colHalfWidth / 2f), tableTop + 44f, weightTextPaint)
            canvas.drawText("250", colRightX + (colHalfWidth / 2f), tableTop + 44f, weightTextPaint)

            // Vertical Center Divider in Header
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#831843")
                strokeWidth = 2.5f
            }
            canvas.drawLine(colRightX, tableTop, colRightX, tableTop + tableHeight, dividerPaint)

            // Subheaders: "السعر" and "الصنف"
            val subheaderTop = tableTop + 58f
            val subheaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#4A044E") // Darker purple strip
            }
            canvas.drawRect(tableMargin, subheaderTop, tableMargin + tableWidth, subheaderTop + 50f, subheaderPaint)

            val subheaderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 28f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            // In 900 col:
            canvas.drawText("السعر", colLeftX + (colHalfWidth * 0.28f), subheaderTop + 34f, subheaderTextPaint)
            canvas.drawText("الصنف", colLeftX + (colHalfWidth * 0.74f), subheaderTop + 34f, subheaderTextPaint)
            // In 250 col:
            canvas.drawText("السعر", colRightX + (colHalfWidth * 0.28f), subheaderTop + 34f, subheaderTextPaint)
            canvas.drawText("الصنف", colRightX + (colHalfWidth * 0.74f), subheaderTop + 34f, subheaderTextPaint)

            // Draw Rows: Alternating White & Soft Lilac
            val whiteRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            val lilacRowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#F5E8F3") // Gentle lilac
            }

            val kindTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3B0764") // Deep Purple
                textSize = (rowHeight * 0.44f).coerceIn(24f, 32f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val priceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#3B0764") // Deep Purple Bold Numbers
                textSize = (rowHeight * 0.48f).coerceIn(26f, 34f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            var currentY = subheaderTop + 50f
            effectiveKinds.forEachIndexed { index, kind ->
                val rowRect = RectF(tableMargin + 2f, currentY, tableMargin + tableWidth - 2f, currentY + rowHeight)
                val isLilac = (index % 2 == 1)
                canvas.drawRect(rowRect, if (isLilac) lilacRowPaint else whiteRowPaint)

                val textY = currentY + (rowHeight * 0.68f)

                // 250g Price calculation
                val def250 = defaults250[kind] ?: 500000L
                val entity250 = posterPrices.find { it.weight == "250" && it.kind == kind }
                val price250 = if (currency.equals("EGP", ignoreCase = true)) {
                    if (entity250 != null && entity250.priceEgp > 0) entity250.priceEgp.toLong() else 2500L
                } else {
                    entity250?.price ?: def250
                }

                // 900g Price calculation
                val def900 = defaults900[kind] ?: 385000L
                val entity900 = posterPrices.find { it.weight == "900" && it.kind == kind }
                val price900 = if (currency.equals("EGP", ignoreCase = true)) {
                    if (entity900 != null && entity900.priceEgp > 0) entity900.priceEgp.toLong() else 1925L
                } else {
                    entity900?.price ?: def900
                }

                // Clean display label (e.g. ابيض instead of أبيض if in reference)
                val displayKind = kind.replace("أ", "ا").replace("إ", "ا")

                // Right Column (250):
                canvas.drawText(displayKind, colRightX + (colHalfWidth * 0.74f), textY, kindTextPaint)
                canvas.drawText(numberFormat.format(price250), colRightX + (colHalfWidth * 0.28f), textY, priceTextPaint)

                // Left Column (900):
                canvas.drawText(displayKind, colLeftX + (colHalfWidth * 0.74f), textY, kindTextPaint)
                canvas.drawText(numberFormat.format(price900), colLeftX + (colHalfWidth * 0.28f), textY, priceTextPaint)

                currentY += rowHeight
            }

            // 5. 3D Red Splash Discount Badge
            val badgeY = tableTop + tableHeight - 20f
            val redSplashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f, badgeY, width.toFloat(), badgeY + 110f,
                    Color.parseColor("#DC2626"),
                    Color.parseColor("#991B1B"),
                    Shader.TileMode.CLAMP
                )
            }
            // Draw Dynamic Splash Ribbon Shape
            val splashRect = RectF(120f, badgeY, width - 120f, badgeY + 105f)
            canvas.drawRoundRect(splashRect, 32f, 32f, redSplashPaint)

            // Red splash splashes at edges
            val redDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#DC2626")
                style = Paint.Style.FILL
            }
            canvas.drawCircle(105f, badgeY + 45f, 22f, redDotPaint)
            canvas.drawCircle(85f, badgeY + 30f, 12f, redDotPaint)
            canvas.drawCircle(width - 105f, badgeY + 45f, 22f, redDotPaint)
            canvas.drawCircle(width - 85f, badgeY + 30f, 12f, redDotPaint)

            // Golden 3D Discount Text: "15% خصم"
            val discountValStr = "${discountPercent.toInt()}%"
            val goldTextShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#78350F")
                textSize = 80f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val goldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FBBF24")
                textSize = 80f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val whiteKhasmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 66f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.LEFT
            }

            val badgeCenterX = width / 2f
            canvas.drawText(discountValStr, badgeCenterX + 24f, badgeY + 80f, goldTextShadow)
            canvas.drawText(discountValStr, badgeCenterX + 20f, badgeY + 76f, goldTextPaint)
            canvas.drawText("خصم", badgeCenterX - 140f, badgeY + 76f, whiteKhasmPaint)

            // 6. Bottom Authentic Product Packaging & Floor Artwork
            val bottomY = badgeY + 115f
            val bottomHeight = height - bottomY

            // Try to draw authentic reference poster bottom (packaging boxes & splattered floor)
            var packagingDrawn = false
            runCatching {
                val refBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.pmp_reference_poster)
                if (refBitmap != null) {
                    // Crop bottom 42% of reference image (where boxes and floor are located)
                    val cropTop = (refBitmap.height * 0.58f).toInt()
                    val srcRect = Rect(0, cropTop, refBitmap.width, refBitmap.height)
                    val destRect = Rect(0, bottomY.toInt(), width, height)
                    canvas.drawBitmap(refBitmap, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                    packagingDrawn = true
                }
            }

            if (!packagingDrawn) {
                // Fallback elegant bottom banner
                val floorPaint = Paint().apply {
                    shader = LinearGradient(
                        0f, bottomY, 0f, height.toFloat(),
                        Color.parseColor("#CBD5E1"),
                        Color.parseColor("#94A3B8"),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, bottomY, width.toFloat(), height.toFloat(), floorPaint)

                val bottomNoticePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#0F172A")
                    textSize = 32f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("PMP PAINTS • دهانات فاخرة تصنع الفرق", width / 2f, bottomY + 80f, bottomNoticePaint)
            }

            // Save and Share Generated Image
            val cacheFolder = File(context.cacheDir, "shared_images")
            if (!cacheFolder.exists()) cacheFolder.mkdirs()
            val file = File(cacheFolder, "pmp_poster_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "بوستر أسعار PMP PAINTS لشهر $monthTitle")
                putExtra(Intent.EXTRA_TEXT, "لائحة أسعار عبوات PMP PAINTS الرسمية (250 و 900) - $monthTitle.\nألوان تصنع الفرق!")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(intent, "مشاركة بوستر أسعار PMP")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            file
        }
    }
}

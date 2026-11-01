package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.local.InvoiceEntity
import com.example.data.local.InvoiceItemEntity
import com.example.data.local.InvoicePaymentEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.NumberFormat
import java.util.Locale

object InvoicePdfExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    /**
     * Generates an official, modern enterprise PMP Paints Invoice PDF containing the PMP Identity Logo,
     * customer details, itemized product table, financial summary, and payment history.
     * Name strictly set to PMP PAINTS (replacing any outdated references).
     */
    fun exportInvoiceToDownloads(
        context: Context,
        invoice: InvoiceEntity,
        items: List<InvoiceItemEntity>,
        payments: List<InvoicePaymentEntity>,
        customOutputUri: Uri? = null
    ): Result<String> {
        return runCatching {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            drawInvoicePage(
                context = context,
                canvas = canvas,
                invoice = invoice,
                items = items,
                payments = payments
            )

            pdfDocument.finishPage(page)

            val fileName = "PMP_Invoice_${invoice.invoiceNo.replace("[^a-zA-Z0-9_-]".toRegex(), "_")}.pdf"

            if (customOutputUri != null) {
                context.contentResolver.openOutputStream(customOutputUri)?.use { out ->
                    pdfDocument.writeTo(out)
                } ?: error("تعذر فتح مسار حفظ ملف PDF")
                pdfDocument.close()
                return@runCatching fileName
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PMP_Invoices")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out: OutputStream ->
                        pdfDocument.writeTo(out)
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    pdfDocument.close()
                    return@runCatching "Downloads/PMP_Invoices/$fileName"
                }
            }

            val fallbackDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "PMP_Invoices"
            )
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val outFile = File(fallbackDir, fileName)
            FileOutputStream(outFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()
            outFile.absolutePath
        }
    }

    private fun drawInvoicePage(
        context: Context,
        canvas: Canvas,
        invoice: InvoiceEntity,
        items: List<InvoiceItemEntity>,
        payments: List<InvoicePaymentEntity>
    ) {
        val numberFormat = NumberFormat.getNumberInstance(Locale.US)
        val margin = 32f
        val contentRight = PAGE_WIDTH - margin
        val contentLeft = margin

        // Clean White Page Canvas
        val bgPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

        // Modern Enterprise Header Top Bar
        val primaryNavy = Color.parseColor("#0F172A")
        val accentBlue = Color.parseColor("#1E40AF")
        val accentRed = Color.parseColor("#DC2626")
        val accentGold = Color.parseColor("#D97706")
        val textMuted = Color.parseColor("#64748B")
        val borderLight = Color.parseColor("#E2E8F0")

        // Top Geometric Brand Stripes
        val blueBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentBlue
            style = Paint.Style.FILL
        }
        val redBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentRed
            style = Paint.Style.FILL
        }
        val goldBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentGold
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat() * 0.70f, 6f, blueBarPaint)
        canvas.drawRect(PAGE_WIDTH.toFloat() * 0.70f, 0f, PAGE_WIDTH.toFloat() * 0.90f, 6f, redBarPaint)
        canvas.drawRect(PAGE_WIDTH.toFloat() * 0.90f, 0f, PAGE_WIDTH.toFloat(), 6f, goldBarPaint)

        // Header Container Card
        val headerCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }
        val headerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderLight
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val headerRect = RectF(contentLeft, 22f, contentRight, 126f)
        canvas.drawRoundRect(headerRect, 14f, 14f, headerCardPaint)
        canvas.drawRoundRect(headerRect, 14f, 14f, headerStrokePaint)

        // Draw Official PMP Logo on the left
        var logoDrawn = false
        runCatching {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.pmp_brand_logo)
            if (bitmap != null) {
                val destRect = Rect(
                    (contentLeft + 16f).toInt(),
                    34,
                    (contentLeft + 152f).toInt(),
                    114
                )
                canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                logoDrawn = true
            }
        }
        if (!logoDrawn) {
            runCatching {
                val drawable = ContextCompat.getDrawable(context, R.drawable.ic_pmp_emblem)
                if (drawable != null) {
                    drawable.setBounds(
                        (contentLeft + 16f).toInt(),
                        36,
                        (contentLeft + 140f).toInt(),
                        112
                    )
                    drawable.draw(canvas)
                }
            }
        }

        // Official Enterprise Company Identity (Pure PMP PAINTS - no Al-Muhandis)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryNavy
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentBlue
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textMuted
            textSize = 9.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        val rightX = contentRight - 16f
        canvas.drawText("شركة PMP للدهانات والطلاءات والصناعات الكيميائية", rightX, 50f, titlePaint)
        canvas.drawText("PMP PAINTS & CHEMICAL INDUSTRIES • OFFICIAL TAX INVOICE", rightX, 68f, subtitlePaint)
        canvas.drawText("فروع ومخازن المنظومة: مصر (المصنع المركزي) | السودان (مخازن حلفا وعطبرة)", rightX, 86f, metaPaint)
        canvas.drawText("فاتورة بيع ضريبية معتمدة وموثقة برقم تسجيل إلكتروني رسمي", rightX, 102f, metaPaint)

        // Modern Invoice Ribbon Strip (Navy & Royal Gradient Look)
        val badgeRect = RectF(contentLeft, 136f, contentRight, 186f)
        val navyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(badgeRect, 10f, 10f, navyFillPaint)

        val paidAmount = payments.filter { it.status == "active" }.sumOf { it.amount }
        val remainingAmount = (invoice.total - paidAmount).coerceAtLeast(0.0)
        val (statusText, statusBgColor, statusTextColor) = when {
            invoice.status == "cancelled" -> Triple("ملغاة", "#EF4444", "#FFFFFF")
            remainingAmount <= 0.0 -> Triple("مسددة بالكامل", "#10B981", "#FFFFFF")
            paidAmount > 0.0 -> Triple("مسددة جزئياً", "#F59E0B", "#000000")
            else -> Triple("آجلة / غير مسددة", "#DC2626", "#FFFFFF")
        }

        val whiteBoldRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("فاتورة مبيعات رقم: ${invoice.invoiceNo}", rightX, 158f, whiteBoldRight)
        canvas.drawText(
            "العميل: ${invoice.customerName}   |   التاريخ: ${invoice.invoiceDate}",
            rightX,
            176f,
            Paint(whiteBoldRight).apply {
                textSize = 10.5f
                color = Color.parseColor("#CBD5E1")
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
        )

        // Status Pill Badge on the Left
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(statusBgColor)
            style = Paint.Style.FILL
        }
        val pillRect = RectF(contentLeft + 14f, 149f, contentLeft + 130f, 173f)
        canvas.drawRoundRect(pillRect, 12f, 12f, pillPaint)
        val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(statusTextColor)
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(statusText, pillRect.centerX(), pillRect.centerY() + 4f, pillTextPaint)

        // Line Items Table Header
        var currentY = 200f
        val tableHeaderRect = RectF(contentLeft, currentY, contentRight, currentY + 28f)
        val tableHeaderBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E3A8A") // Royal Navy Blue
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(tableHeaderRect, 8f, 8f, tableHeaderBg)

        val tableHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 10.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Columns Layout (RTL order: # | الصنف | المخزن | الكمية | سعر الوحدة | الإجمالي)
        val colIndexX = contentRight - 14f
        val colProductX = contentRight - 42f
        val colWarehouseX = contentRight - 210f
        val colQtyX = contentLeft + 195f
        val colUnitPriceX = contentLeft + 125f
        val colTotalX = contentLeft + 14f

        tableHeaderPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("#", colIndexX, currentY + 18f, tableHeaderPaint)
        canvas.drawText("الصنف / المنتج", colProductX, currentY + 18f, tableHeaderPaint)
        canvas.drawText("المخزن", colWarehouseX, currentY + 18f, tableHeaderPaint)
        canvas.drawText("الكمية", colQtyX, currentY + 18f, tableHeaderPaint)
        canvas.drawText("سعر الوحدة", colUnitPriceX, currentY + 18f, tableHeaderPaint)
        tableHeaderPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("الإجمالي (${invoice.currency})", colTotalX, currentY + 18f, tableHeaderPaint)

        currentY += 32f

        val rowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryNavy
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val rowBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryNavy
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val zebraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }
        val rowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F1F5F9")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        val displayItems = if (items.isNotEmpty()) {
            items
        } else {
            listOf(
                InvoiceItemEntity(
                    invoiceId = invoice.id,
                    description = "إجمالي بنود فاتورة مبيعات (${invoice.invoiceNo})",
                    quantity = 1.0,
                    unitPrice = invoice.subtotal,
                    lineTotal = invoice.subtotal
                )
            )
        }

        displayItems.forEachIndexed { index, item ->
            val rowHeight = 24f
            if (index % 2 == 1) {
                canvas.drawRect(contentLeft, currentY, contentRight, currentY + rowHeight, zebraPaint)
            }
            val baseline = currentY + 16f

            rowTextPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${index + 1}", colIndexX, baseline, rowTextPaint)
            canvas.drawText(item.description.take(28), colProductX, baseline, rowBoldPaint.apply { textAlign = Paint.Align.RIGHT })
            canvas.drawText("مخزن #${item.warehouseId ?: 1}", colWarehouseX, baseline, rowTextPaint)
            canvas.drawText(numberFormat.format(item.quantity), colQtyX, baseline, rowTextPaint)
            canvas.drawText(numberFormat.format(item.unitPrice), colUnitPriceX, baseline, rowTextPaint)

            rowBoldPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                "${numberFormat.format(item.lineTotal)} ${invoice.currency}",
                colTotalX,
                baseline,
                rowBoldPaint
            )

            currentY += rowHeight
            canvas.drawLine(contentLeft, currentY, contentRight, currentY, rowLinePaint)
        }

        currentY += 16f

        // Modern Enterprise Totals Summary Box
        val summaryRect = RectF(contentLeft, currentY, contentRight, currentY + 98f)
        canvas.drawRoundRect(summaryRect, 12f, 12f, headerCardPaint)
        canvas.drawRoundRect(summaryRect, 12f, 12f, headerStrokePaint)

        val labelRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val valueLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryNavy
            textSize = 11.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }

        canvas.drawText("إجمالي البنود قبل الخصم:", rightX, currentY + 22f, labelRightPaint)
        canvas.drawText("${numberFormat.format(invoice.subtotal)} ${invoice.currency}", contentLeft + 16f, currentY + 22f, valueLeftPaint)

        canvas.drawText("قيمة الخصم الممنوح:", rightX, currentY + 42f, labelRightPaint)
        canvas.drawText("${numberFormat.format(invoice.discount)} ${invoice.currency}", contentLeft + 16f, currentY + 42f, valueLeftPaint)

        // Total Final Box Highlight
        val finalBoxRect = RectF(contentLeft + 8f, currentY + 49f, contentRight - 8f, currentY + 74f)
        val finalBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EFF6FF")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(finalBoxRect, 6f, 6f, finalBoxPaint)

        canvas.drawText("صافي الفاتورة النهائي:", rightX, currentY + 66f, Paint(labelRightPaint).apply {
            color = accentBlue
            textSize = 12f
        })
        canvas.drawText(
            "${numberFormat.format(invoice.total)} ${invoice.currency}",
            contentLeft + 16f,
            currentY + 66f,
            Paint(valueLeftPaint).apply {
                color = accentBlue
                textSize = 13f
            }
        )

        canvas.drawText(
            "المبلغ المدفوع: ${numberFormat.format(paidAmount)} ${invoice.currency}   •   المتبقي المستحق: ${numberFormat.format(remainingAmount)} ${invoice.currency}",
            rightX,
            currentY + 89f,
            Paint(labelRightPaint).apply {
                color = if (remainingAmount <= 0.0) Color.parseColor("#059669") else Color.parseColor("#DC2626")
                textSize = 10.5f
            }
        )

        currentY += 114f

        // Payment History Section
        if (payments.isNotEmpty()) {
            canvas.drawText("سجل الدفعات والتحصيل المرتبط بالفاتورة:", rightX, currentY, subtitlePaint)
            currentY += 8f
            payments.filter { it.status == "active" }.take(5).forEach { p ->
                currentY += 18f
                canvas.drawText(
                    "• ${p.payDate} - الخزنة: ${p.cashbox} - المبلغ: ${numberFormat.format(p.amount)} ${invoice.currency} (${p.note})",
                    rightX,
                    currentY,
                    metaPaint
                )
            }
            currentY += 16f
        }

        if (invoice.notes.isNotBlank()) {
            canvas.drawText("ملاحظات الفاتورة: ${invoice.notes}", rightX, currentY + 12f, metaPaint)
        }

        // Modern Enterprise Footer with PMP Emblem & Authenticity Seal
        val footerTop = PAGE_HEIGHT - 75f
        canvas.drawLine(contentLeft, footerTop, contentRight, footerTop, headerStrokePaint)

        runCatching {
            val emblem = ContextCompat.getDrawable(context, R.drawable.ic_pmp_emblem)
            if (emblem != null) {
                emblem.setBounds(
                    (contentLeft + 12f).toInt(),
                    (footerTop + 10f).toInt(),
                    (contentLeft + 72f).toInt(),
                    (footerTop + 54f).toInt()
                )
                emblem.draw(canvas)
            }
        }

        canvas.drawText(
            "شركة PMP للدهانات والطلاءات (PMP PAINTS) • مستند إلكتروني رسمي وموثق في السحابة",
            rightX,
            footerTop + 24f,
            Paint(metaPaint).apply {
                color = primaryNavy
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 10f
            }
        )
        canvas.drawText(
            "توقيع المحاسب المعتمد / ختم إدارة المبيعات • جميع الحقوق محفوظة لشركة PMP Paints",
            rightX,
            footerTop + 44f,
            metaPaint
        )
    }
}

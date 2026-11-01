package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.data.local.CustomerEntity
import com.example.data.local.InvoiceEntity
import com.example.data.local.InvoicePaymentEntity
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class StatementRow(
    val date: String,
    val kind: String, // فاتورة بيع, سداد دفعة
    val reference: String,
    val note: String,
    val currency: String,
    val debit: Double,  // مدين (فاتورة مسحوبة)
    val credit: Double, // دائن (دفعة مسددة)
    val balance: Double // رصيد تراكمي
)

object StatementPdfAndExcelExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    private fun buildStatementRows(
        invoices: List<InvoiceEntity>,
        payments: List<InvoicePaymentEntity>
    ): List<StatementRow> {
        val events = mutableListOf<Triple<String, Long, StatementRow>>()

        for (inv in invoices) {
            events.add(
                Triple(
                    inv.invoiceDate,
                    inv.createdAt,
                    StatementRow(
                        date = inv.invoiceDate,
                        kind = "فاتورة بيع",
                        reference = inv.invoiceNo,
                        note = inv.notes.ifBlank { "فاتورة مبيعات بضاعة" },
                        currency = inv.currency,
                        debit = inv.total,
                        credit = 0.0,
                        balance = 0.0
                    )
                )
            )
        }

        for (pay in payments) {
            events.add(
                Triple(
                    pay.payDate,
                    pay.createdAt,
                    StatementRow(
                        date = pay.payDate,
                        kind = "سداد دفعة",
                        reference = "إيصال سداد",
                        note = "${pay.note} [خزنة: ${pay.cashbox}]".trim(),
                        currency = "SDG",
                        debit = 0.0,
                        credit = pay.amount,
                        balance = 0.0
                    )
                )
            )
        }

        val sorted = events.sortedWith(compareBy({ it.first }, { it.second }))
        var runningBalance = 0.0
        return sorted.map { triple ->
            val row = triple.third
            runningBalance += (row.debit - row.credit)
            row.copy(balance = runningBalance)
        }
    }

    /**
     * Exports a pristine Account Statement to PDF with pure white background,
     * PMP logo, clear financial summary, and itemized ledger table.
     */
    fun exportStatementToPdf(
        context: Context,
        customer: CustomerEntity,
        invoices: List<InvoiceEntity>,
        payments: List<InvoicePaymentEntity>,
        customOutputUri: Uri? = null
    ): Result<String> {
        return runCatching {
            val rows = buildStatementRows(invoices, payments)
            val totalDebit = rows.sumOf { it.debit }
            val totalCredit = rows.sumOf { it.credit }
            val netBalance = totalDebit - totalCredit

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            // 1. Pure White Background for clean printing
            canvas.drawColor(Color.WHITE)

            val contentLeft = 32f
            val contentRight = PAGE_WIDTH - 32f
            val numFormat = NumberFormat.getNumberInstance(Locale.US)
            val printDate = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())

            // 2. Top Header Box
            val headerRect = RectF(contentLeft, 28f, contentRight, 120f)
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E2E8F0")
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
            }
            val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F8FAFC")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(headerRect, 10f, 10f, headerBgPaint)
            canvas.drawRoundRect(headerRect, 10f, 10f, borderPaint)

            // Draw Logo on the left
            var logoDrawn = false
            runCatching {
                val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.pmp_brand_logo)
                if (bitmap != null) {
                    val destRect = Rect((contentLeft + 12f).toInt(), 36, (contentLeft + 130f).toInt(), 112)
                    canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                    logoDrawn = true
                }
            }
            if (!logoDrawn) {
                runCatching {
                    val drawable = ContextCompat.getDrawable(context, R.drawable.ic_pmp_emblem)
                    if (drawable != null) {
                        drawable.setBounds((contentLeft + 14f).toInt(), 36, (contentLeft + 110f).toInt(), 112)
                        drawable.draw(canvas)
                    }
                }
            }

            // Right-aligned Header Title
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1B1464")
                textSize = 17f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#D9232E")
                textSize = 11.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#475569")
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }

            val rightX = contentRight - 14f
            canvas.drawText("شركة PMP للدهانات والطلاءات والصناعات الكيميائية (PMP PAINTS)", rightX, 52f, titlePaint)
            canvas.drawText("PMP PAINTS • كشف حساب عميل معتمد وموثق", rightX, 70f, subtitlePaint)
            canvas.drawText("الفروع والمصنع: السودان • مصر | طباعة بتاريخ: $printDate", rightX, 88f, metaPaint)
            canvas.drawText("منظومة إدارة المبيعات والفواتير والتحصيلات", rightX, 104f, metaPaint)

            // 3. Customer Info Box
            val custBoxRect = RectF(contentLeft, 130f, contentRight, 185f)
            val custBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFFFFF")
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(custBoxRect, 8f, 8f, custBgPaint)
            canvas.drawRoundRect(custBoxRect, 8f, 8f, borderPaint)

            val boldDarkRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 12f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            val darkNormalRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#334155")
                textSize = 11f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textAlign = Paint.Align.RIGHT
            }

            canvas.drawText("اسم العميل: ${customer.name}", rightX, 150f, boldDarkRight)
            canvas.drawText("الهاتف: ${customer.phone.ifBlank { "غير مسجل" }}  |  العنوان: ${customer.address.ifBlank { "غير محدد" }}", rightX, 170f, darkNormalRight)

            // 4. Financial Summary Cards
            val cardTop = 195f
            val cardBottom = 245f
            val cardWidth = (contentRight - contentLeft - 20f) / 3f

            fun drawSummaryBox(left: Float, label: String, amount: String, colorHex: String) {
                val r = RectF(left, cardTop, left + cardWidth, cardBottom)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#F8FAFC")
                    style = Paint.Style.FILL
                }
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(colorHex)
                    style = Paint.Style.STROKE
                    strokeWidth = 1.2f
                }
                canvas.drawRoundRect(r, 8f, 8f, fill)
                canvas.drawRoundRect(r, 8f, 8f, border)

                val lPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#64748B")
                    textSize = 9.5f
                    textAlign = Paint.Align.CENTER
                }
                val vPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(colorHex)
                    textSize = 12.5f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }
                val midX = left + (cardWidth / 2f)
                canvas.drawText(label, midX, cardTop + 18f, lPaint)
                canvas.drawText(amount, midX, cardTop + 38f, vPaint)
            }

            drawSummaryBox(contentLeft, "إجمالي المسحوبات (مدين)", "${numFormat.format(totalDebit)} SDG", "#1B1464")
            drawSummaryBox(contentLeft + cardWidth + 10f, "إجمالي السدادات (دائن)", "${numFormat.format(totalCredit)} SDG", "#059669")
            val balanceColor = if (netBalance > 0) "#DC2626" else "#059669"
            val balanceLabel = if (netBalance > 0) "الرصيد المستحق (مطلوب سداد)" else "صافي الحساب"
            drawSummaryBox(contentLeft + (cardWidth + 10f) * 2f, balanceLabel, "${numFormat.format(kotlin.math.abs(netBalance))} SDG", balanceColor)

            // 5. Itemized Statement Table
            val tableTop = 258f
            val tableHeaderHeight = 24f
            val tableHeaderRect = RectF(contentLeft, tableTop, contentRight, tableTop + tableHeaderHeight)
            val navyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1B1464")
                style = Paint.Style.FILL
            }
            canvas.drawRect(tableHeaderRect, navyPaint)

            val tableHeaderWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 9.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }

            // Columns (Right to Left):
            // Date (75) | Type (65) | Ref & Notes (150) | Currency (45) | Debit (65) | Credit (65) | Balance (65)
            val colDateX = contentRight - 40f
            val colKindX = contentRight - 110f
            val colNoteX = contentRight - 220f
            val colCurX = contentRight - 325f
            val colDebitX = contentRight - 380f
            val colCreditX = contentRight - 445f
            val colBalanceX = contentRight - 500f

            canvas.drawText("التاريخ", colDateX, tableTop + 16f, tableHeaderWhite)
            canvas.drawText("نوع الحركة", colKindX, tableTop + 16f, tableHeaderWhite)
            canvas.drawText("البيان والملاحظات", colNoteX, tableTop + 16f, tableHeaderWhite)
            canvas.drawText("العملة", colCurX, tableTop + 16f, tableHeaderWhite)
            canvas.drawText("مدين (+)", colDebitX, tableTop + 16f, tableHeaderWhite)
            canvas.drawText("دائن (-)", colCreditX, tableTop + 16f, tableHeaderWhite)
            canvas.drawText("الرصيد", colBalanceX, tableTop + 16f, tableHeaderWhite)

            var currentY = tableTop + tableHeaderHeight
            val rowHeight = 22f

            val rowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1E293B")
                textSize = 9f
                textAlign = Paint.Align.CENTER
            }
            val rowBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val zebraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F8FAFC")
                style = Paint.Style.FILL
            }

            val maxRowsPerPage = 22
            val displayedRows = rows.take(maxRowsPerPage)

            displayedRows.forEachIndexed { idx, row ->
                if (idx % 2 == 1) {
                    canvas.drawRect(RectF(contentLeft, currentY, contentRight, currentY + rowHeight), zebraPaint)
                }
                canvas.drawRect(RectF(contentLeft, currentY, contentRight, currentY + rowHeight), borderPaint)

                val textY = currentY + 15f
                canvas.drawText(row.date, colDateX, textY, rowTextPaint)
                canvas.drawText(row.kind, colKindX, textY, rowTextPaint)

                val noteDisplay = if (row.note.length > 28) row.note.take(26) + ".." else row.note
                canvas.drawText(noteDisplay, colNoteX, textY, rowTextPaint)

                canvas.drawText(row.currency, colCurX, textY, rowTextPaint)
                canvas.drawText(if (row.debit > 0) numFormat.format(row.debit) else "-", colDebitX, textY, if (row.debit > 0) rowBoldPaint else rowTextPaint)
                canvas.drawText(if (row.credit > 0) numFormat.format(row.credit) else "-", colCreditX, textY, if (row.credit > 0) rowBoldPaint else rowTextPaint)
                canvas.drawText(numFormat.format(row.balance), colBalanceX, textY, rowBoldPaint)

                currentY += rowHeight
            }

            // Bottom Footer
            val footerY = PAGE_HEIGHT - 35f
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                textSize = 9f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(
                "شركة PMP للدهانات والطلاءات (PMP PAINTS) • ألوان تصنع الفرق • كشف حساب رسمي مستخرج إلكترونياً",
                PAGE_WIDTH / 2f,
                footerY,
                footerPaint
            )

            pdfDocument.finishPage(page)

            val safeCustomerName = customer.name.replace("[^a-zA-Z0-9_\\u0600-\\u06FF]".toRegex(), "_")
            val fileName = "PMP_Statement_${safeCustomerName}_${System.currentTimeMillis() % 10000}.pdf"

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
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PMP_Statements")
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
                    return@runCatching "Downloads/PMP_Statements/$fileName"
                }
            }

            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "PMP_Statements")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val outFile = File(fallbackDir, fileName)
            FileOutputStream(outFile).use { out ->
                pdfDocument.writeTo(out)
            }
            pdfDocument.close()
            outFile.absolutePath
        }
    }

    /**
     * Exports the account statement to an Excel-compatible CSV file with UTF-8 BOM
     * so Arabic characters render perfectly in Microsoft Excel on Windows, Mobile, and Web.
     */
    fun exportStatementToExcel(
        context: Context,
        customer: CustomerEntity,
        invoices: List<InvoiceEntity>,
        payments: List<InvoicePaymentEntity>,
        customOutputUri: Uri? = null
    ): Result<String> {
        return runCatching {
            val rows = buildStatementRows(invoices, payments)
            val totalDebit = rows.sumOf { it.debit }
            val totalCredit = rows.sumOf { it.credit }
            val netBalance = totalDebit - totalCredit
            val numFormat = NumberFormat.getNumberInstance(Locale.US)
            val printDate = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())

            val safeCustomerName = customer.name.replace("[^a-zA-Z0-9_\\u0600-\\u06FF]".toRegex(), "_")
            val fileName = "PMP_Statement_${safeCustomerName}_${System.currentTimeMillis() % 10000}.csv"

            val csvContent = buildString {
                // UTF-8 BOM is required by Microsoft Excel on Windows & Android to display Arabic properly
                append('\uFEFF')
                appendLine("شركة PMP للدهانات والطلاءات والصناعات الكيميائية (PMP PAINTS);كشف حساب عميل")
                appendLine("اسم العميل;${customer.name};الهاتف;${customer.phone};العنوان;${customer.address}")
                appendLine("تاريخ استخراج الكشف;$printDate")
                appendLine("إجمالي المسحوبات (مدين);${numFormat.format(totalDebit)} SDG;إجمالي السدادات (دائن);${numFormat.format(totalCredit)} SDG;صافي الرصيد المستحق;${numFormat.format(netBalance)} SDG")
                appendLine()
                appendLine("التاريخ;نوع الحركة;رقم المستند / المرجع;البيان والملاحظات;العملة;مدين (+);دائن (-);الرصيد التراكمي")

                for (row in rows) {
                    appendLine(
                        "${row.date};${row.kind};${row.reference};\"${row.note.replace("\"", "\"\"")}\";${row.currency};${if (row.debit > 0) row.debit else 0.0};${if (row.credit > 0) row.credit else 0.0};${row.balance}"
                    )
                }

                appendLine()
                appendLine("الإجمالي العام;;;;;${totalDebit};${totalCredit};${netBalance}")
            }

            if (customOutputUri != null) {
                context.contentResolver.openOutputStream(customOutputUri)?.use { out ->
                    OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                        writer.write(csvContent)
                    }
                } ?: error("تعذر كتابة ملف Excel")
                return@runCatching fileName
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PMP_Statements")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                            writer.write(csvContent)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                    return@runCatching "Downloads/PMP_Statements/$fileName"
                }
            }

            val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "PMP_Statements")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val outFile = File(fallbackDir, fileName)
            FileOutputStream(outFile).use { out ->
                OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                    writer.write(csvContent)
                }
            }
            outFile.absolutePath
        }
    }
}

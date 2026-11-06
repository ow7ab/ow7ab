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
import com.example.data.local.MovementEntity
import com.example.data.local.resolvePaymentCurrency
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
    val balance: Double, // رصيد تراكمي
    val createdBy: String = "المدير"
)

object StatementPdfAndExcelExporter {

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842

    private fun buildStatementRows(
        invoices: List<InvoiceEntity>,
        payments: List<InvoicePaymentEntity>
    ): List<StatementRow> {
        val events = mutableListOf<Triple<String, Long, StatementRow>>()

        val invoiceMap = invoices.associateBy { it.id }

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
                        balance = 0.0,
                        createdBy = inv.createdBy
                    )
                )
            )
        }

        for (pay in payments) {
            val parentInv = invoiceMap[pay.invoiceId]
            val payCurrency = pay.resolvePaymentCurrency(parentInv?.currency ?: "SDG")
            events.add(
                Triple(
                    pay.payDate,
                    pay.createdAt,
                    StatementRow(
                        date = pay.payDate,
                        kind = "سداد دفعة",
                        reference = parentInv?.invoiceNo?.let { "إيصال ($it)" } ?: "إيصال سداد",
                        note = "${pay.note} [خزنة: ${pay.cashbox}]".trim(),
                        currency = payCurrency,
                        debit = 0.0,
                        credit = pay.amount,
                        balance = 0.0,
                        createdBy = pay.createdBy
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

            val primaryCur = invoices.firstOrNull()?.currency ?: rows.firstOrNull()?.currency ?: "SDG"
            val csvContent = buildString {
                // UTF-8 BOM is required by Microsoft Excel on Windows & Android to display Arabic properly
                append('\uFEFF')
                appendLine("شركة PMP للدهانات والطلاءات والصناعات الكيميائية (PMP PAINTS);كشف حساب عميل")
                appendLine("اسم العميل;${customer.name};الهاتف;${customer.phone};العنوان;${customer.address}")
                appendLine("تاريخ استخراج الكشف;$printDate")
                appendLine("إجمالي المسحوبات (مدين);${numFormat.format(totalDebit)} $primaryCur;إجمالي السدادات (دائن);${numFormat.format(totalCredit)} $primaryCur;صافي الرصيد المستحق;${numFormat.format(netBalance)} $primaryCur")
                appendLine()
                appendLine("التاريخ;نوع الحركة;رقم المستند / المرجع;البيان والملاحظات;اسم موظف الحساب;العملة;مدين (+);دائن (-);الرصيد التراكمي")

                for (row in rows) {
                    appendLine(
                        "${row.date};${row.kind};${row.reference};\"${row.note.replace("\"", "\"\"")}\";\"${row.createdBy.replace("\"", "\"\"")}\";${row.currency};${if (row.debit > 0) row.debit else 0.0};${if (row.credit > 0) row.credit else 0.0};${row.balance}"
                    )
                }

                appendLine()
                appendLine("الإجمالي العام;;;;;;${totalDebit};${totalCredit};${netBalance}")
            }

            writeCsvAndShareIfRequested(context, fileName, csvContent, customOutputUri)
        }
    }

    /**
     * Exports the Treasury / Cashbox Account Statement (كشف حساب الخزنة) to an Excel-compatible
     * CSV file with UTF-8 BOM so Arabic characters and numbers open cleanly in Microsoft Excel.
     */
    fun exportTreasuryStatementToExcel(
        context: Context,
        cashboxName: String,
        currency: String,
        movements: List<MovementEntity>,
        closingBalance: Double,
        customOutputUri: Uri? = null
    ): Result<String> {
        return runCatching {
            val numFormat = NumberFormat.getNumberInstance(Locale.US)
            val printDate = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date())
            val safeBoxName = cashboxName.replace("[^a-zA-Z0-9_\\u0600-\\u06FF]".toRegex(), "_")
            val fileName = "PMP_Treasury_Statement_${safeBoxName}_${currency}_${System.currentTimeMillis() % 10000}.csv"

            val ordered = movements.sortedWith(compareBy({ it.txDate }, { it.id }))
            val totalIn = ordered.filter { it.direction == "وارد" }.sumOf { it.amount }
            val totalOut = ordered.filter { it.direction != "وارد" }.sumOf { it.amount }

            var runningBal = 0.0
            val csvContent = buildString {
                append('\uFEFF')
                appendLine("شركة PMP للدهانات والطلاءات والصناعات الكيميائية (PMP PAINTS);كشف حساب الخزنة التفصيلي")
                appendLine("اسم الخزنة;$cashboxName;العملة;$currency;تاريخ استخراج الكشف;$printDate")
                appendLine("إجمالي الوارد (+);${numFormat.format(totalIn)} $currency;إجمالي المنصرف (-);${numFormat.format(totalOut)} $currency;الرصيد الختامي;${numFormat.format(closingBalance)} $currency")
                appendLine()
                appendLine("رقم المعاملة;التاريخ;رقم المستند / المرجع;نوع الحركة;البيان والتفاصيل;اسم موظف الحساب المنفذ;العملة;وارد (+);منصرف (-);الرصيد التراكمي")

                for (m in ordered) {
                    val inAmt = if (m.direction == "وارد") m.amount else 0.0
                    val outAmt = if (m.direction != "وارد") m.amount else 0.0
                    runningBal += (inAmt - outAmt)
                    val cleanDesc = m.description.ifBlank { "حركة مالية (${m.direction})" }.replace("\"", "\"\"")
                    val cleanEmp = m.createdBy.ifBlank { "المدير" }.replace("\"", "\"\"")
                    appendLine(
                        "#${m.id};${m.txDate};${m.reference};${m.direction};\"$cleanDesc\";\"$cleanEmp\";${m.currency};$inAmt;$outAmt;$runningBal"
                    )
                }

                appendLine()
                appendLine("الإجمالي العام;;;;;;;${totalIn};${totalOut};${closingBalance}")
            }

            writeCsvAndShareIfRequested(context, fileName, csvContent, customOutputUri)
        }
    }

    private fun writeCsvAndShareIfRequested(
        context: Context,
        fileName: String,
        csvContent: String,
        customOutputUri: Uri?
    ): String {
        if (customOutputUri != null) {
            context.contentResolver.openOutputStream(customOutputUri)?.use { out ->
                OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                    writer.write(csvContent)
                }
            } ?: error("تعذر كتابة ملف Excel")
            return fileName
        }

        // Always write a local copy in cache/files so we can also trigger Android Share/Open sheet if needed
        val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "PMP_Statements")
        if (!fallbackDir.exists()) fallbackDir.mkdirs()
        val outFile = File(fallbackDir, fileName)
        FileOutputStream(outFile).use { out ->
            OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                writer.write(csvContent)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
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
                    return "Downloads/PMP_Statements/$fileName"
                }
            }
        }

        return outFile.absolutePath
    }

    private fun escapeXml(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch.code >= 0x20 || ch == '\n' || ch == '\r' || ch == '\t') {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun columnLetter(colIndexZeroBased: Int): String {
        var n = colIndexZeroBased
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A'.code + (n % 26)).toChar())
            n = (n / 26) - 1
        }
        return sb.toString()
    }

    /**
     * Generates a genuine OpenXML (.xlsx) workbook in memory with Arabic RTL direction (`rightToLeft="1"`),
     * structured headers, numeric cells, and summary rows compatible with Microsoft Excel, Google Sheets, and WPS Office.
     */
    fun buildXlsxBytes(
        reportTitle: String,
        subtitle: String,
        headers: List<String>,
        rows: List<List<String>>,
        summaryRows: List<List<String>> = emptyList()
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            fun putXmlEntry(path: String, xml: String) {
                zos.putNextEntry(java.util.zip.ZipEntry(path))
                zos.write(xml.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            putXmlEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
            )

            putXmlEntry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )

            val safeSheetName = escapeXml(
                reportTitle.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(28).ifBlank { "تقرير PMP" }
            )

            putXmlEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="$safeSheetName" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""
            )

            putXmlEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )

            putXmlEntry(
                "xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="3">
    <font><sz val="11"/><name val="Arial"/></font>
    <font><b/><sz val="12"/><color rgb="FFFFFFFF"/><name val="Arial"/></font>
    <font><b/><sz val="11"/><color rgb="FF1B1464"/><name val="Arial"/></font>
  </fonts>
  <fills count="4">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1B1464"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF1F5F9"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="4">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
    <xf numFmtId="4" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
  </cellXfs>
</styleSheet>"""
            )

            val allLogicalRows = mutableListOf<Pair<Int, List<String>>>()
            allLogicalRows.add(2 to listOf("شركة PMP للدهانات والطلاءات والصناعات الكيميائية (PMP PAINTS)", reportTitle))
            if (subtitle.isNotBlank()) {
                allLogicalRows.add(2 to listOf("بيانات التقرير", subtitle))
            }
            allLogicalRows.add(1 to headers)
            rows.forEach { r -> allLogicalRows.add(0 to r) }
            if (summaryRows.isNotEmpty()) {
                summaryRows.forEach { sr -> allLogicalRows.add(2 to sr) }
            }

            val maxCols = (headers.size.coerceAtLeast(6))
            val colsXml = buildString {
                append("<cols>")
                for (c in 1..maxCols) {
                    append("""<col min="$c" max="$c" width="22" customWidth="1"/>""")
                }
                append("</cols>")
            }

            val sheetDataXml = buildString {
                append("<sheetData>")
                allLogicalRows.forEachIndexed { rowIdx, (styleIndex, cellValues) ->
                    val rNum = rowIdx + 1
                    append("""<row r="$rNum" ht="20" customHeight="1">""")
                    cellValues.forEachIndexed { colIdx, rawVal ->
                        val cellRef = "${columnLetter(colIdx)}$rNum"
                        val trimmed = rawVal.trim()
                        val numericCandidate = if (styleIndex == 0 &&
                            trimmed.isNotEmpty() &&
                            !trimmed.startsWith("0") &&
                            !trimmed.startsWith("#") &&
                            trimmed.matches(Regex("-?\\d+(\\.\\d+)?"))
                        ) {
                            trimmed.toDoubleOrNull()
                        } else {
                            null
                        }

                        if (numericCandidate != null) {
                            append("""<c r="$cellRef" s="3"><v>$numericCandidate</v></c>""")
                        } else {
                            val escaped = escapeXml(rawVal)
                            append("""<c r="$cellRef" s="$styleIndex" t="inlineStr"><is><t>$escaped</t></is></c>""")
                        }
                    }
                    append("</row>")
                }
                append("</sheetData>")
            }

            putXmlEntry(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetViews>
    <sheetView rightToLeft="1" tabSelected="1" workbookViewId="0"/>
  </sheetViews>
  $colsXml
  $sheetDataXml
</worksheet>"""
            )
        }
        return baos.toByteArray()
    }

    /**
     * Exports any financial or inventory report to a genuine .xlsx Excel file, saves it to device storage
     * (or customOutputUri), and optionally opens the Android Share Sheet (WhatsApp, Email, Telegram, etc.).
     */
    fun exportReportToExcel(
        context: Context,
        reportTitle: String,
        subtitle: String,
        headers: List<String>,
        rows: List<List<String>>,
        summaryRows: List<List<String>> = emptyList(),
        customOutputUri: Uri? = null,
        shareAfterExport: Boolean = false
    ): Result<String> {
        return runCatching {
            val xlsxBytes = buildXlsxBytes(
                reportTitle = reportTitle,
                subtitle = subtitle,
                headers = headers,
                rows = rows,
                summaryRows = summaryRows
            )
            val safeTitle = reportTitle.replace("[^a-zA-Z0-9_\\u0600-\\u06FF]".toRegex(), "_").trim('_')
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val fileName = "PMP_${safeTitle}_$timestamp.xlsx"
            val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

            if (customOutputUri != null) {
                context.contentResolver.openOutputStream(customOutputUri)?.use { out ->
                    out.write(xlsxBytes)
                    out.flush()
                } ?: error("تعذر فتح مسار حفظ ملف Excel")
                return@runCatching fileName
            }

            // Always write a shareable copy inside cacheDir/filesDir so FileProvider can share it cleanly
            val shareDir = File(context.cacheDir, "PMP_Reports")
            if (!shareDir.exists()) shareDir.mkdirs()
            val shareFile = File(shareDir, fileName)
            FileOutputStream(shareFile).use { out ->
                out.write(xlsxBytes)
                out.flush()
            }

            var savedDisplayPath = shareFile.absolutePath
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, xlsxMime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PMP_Reports")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val itemUri = resolver.insert(collection, values)
                    if (itemUri != null) {
                        resolver.openOutputStream(itemUri)?.use { out ->
                            out.write(xlsxBytes)
                            out.flush()
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(itemUri, values, null, null)
                        savedDisplayPath = "Downloads/PMP_Reports/$fileName"
                    }
                }
            } else {
                runCatching {
                    val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "PMP_Reports")
                    if (!fallbackDir.exists()) fallbackDir.mkdirs()
                    val outFile = File(fallbackDir, fileName)
                    FileOutputStream(outFile).use { out ->
                        out.write(xlsxBytes)
                        out.flush()
                    }
                    savedDisplayPath = outFile.absolutePath
                }
            }

            if (shareAfterExport) {
                shareExcelFile(context, shareFile, reportTitle)
            }

            savedDisplayPath
        }
    }

    fun shareExcelFile(context: Context, file: File, reportTitle: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = xlsxMime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PMP PAINTS - $reportTitle")
            putExtra(Intent.EXTRA_TEXT, "مرفق ملف إكسيل ($reportTitle) الصادر من نظام PMP PAINTS.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "مشاركة ملف Excel ($reportTitle)").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}

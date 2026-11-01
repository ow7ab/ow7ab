package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Generates an official PMP Science & Chemistry Lab Technical Formulation Sheet (PDF).
 */
object FormulationPdfExporter {

    fun exportAndShareFormulation(
        context: Context,
        formulation: FormulationEntity,
        ingredients: List<FormulationIngredient>
    ): Result<File> = runCatching {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 Portrait
        val page = document.startPage(pageInfo)
        val canvas = page.canvas

        val numberFormat = NumberFormat.getNumberInstance(Locale.US)
        numberFormat.maximumFractionDigits = 2

        // Header Background
        val headerPaint = Paint().apply {
            color = Color.parseColor("#0F172A") // Deep Navy
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, 595f, 100f, headerPaint)

        // Accent Line
        val accentPaint = Paint().apply {
            color = Color.parseColor("#2563EB") // Royal Blue
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        canvas.drawLine(0f, 100f, 595f, 100f, accentPaint)

        // Title Texts
        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("PMP PAINTS — مختبر العلوم الكيميائية وتركيبات الدهانات", 297f, 45f, titlePaint)

        val subTitlePaint = Paint().apply {
            color = Color.parseColor("#94A3B8")
            textSize = 12f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("PMP Science & Chemistry Lab • بطاقة مواصفات التركيبة المخبرية المعتمدة", 297f, 75f, subTitlePaint)

        var y = 130f

        // Info Box
        val boxPaint = Paint().apply {
            color = Color.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(25f, y, 570f, y + 80f, 8f, 8f, boxPaint)
        canvas.drawRoundRect(25f, y, 570f, y + 80f, 8f, 8f, borderPaint)

        val labelPaint = Paint().apply {
            color = Color.parseColor("#475569")
            textSize = 11f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }
        val valuePaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        canvas.drawText("اسم المنتج:", 555f, y + 25f, labelPaint)
        canvas.drawText(formulation.productName, 495f, y + 25f, valuePaint)

        canvas.drawText("كود التركيبة:", 280f, y + 25f, labelPaint)
        canvas.drawText(formulation.formulaNo, 215f, y + 25f, valuePaint)

        val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale("ar")).format(Date(formulation.createdAt))
        canvas.drawText("تاريخ الاعتماد:", 555f, y + 55f, labelPaint)
        canvas.drawText(dateStr, 475f, y + 55f, valuePaint)

        canvas.drawText("نوع الطلاء:", 280f, y + 55f, labelPaint)
        canvas.drawText(formulation.paintType, 215f, y + 55f, valuePaint)

        y += 105f

        // KPI Summary Cards
        val kpiBg = Paint().apply {
            color = Color.parseColor("#EFF6FF")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(25f, y, 570f, y + 60f, 8f, 8f, kpiBg)
        canvas.drawRoundRect(25f, y, 570f, y + 60f, 8f, 8f, borderPaint)

        val kpiTitle = Paint().apply {
            color = Color.parseColor("#1E40AF")
            textSize = 10f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val kpiValue = Paint().apply {
            color = Color.parseColor("#1E3A8A")
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        val colW = 545f / 5f
        val metrics = listOf(
            "PVC %" to "${formulation.calculatedPvc}%",
            "المواد الصلبة وزن" to "${formulation.totalSolidsWeight}%",
            "الكثافة النظرية" to "${formulation.calculatedDensity} kg/L",
            "إجمالي الخلطة" to "${formulation.totalBatchWeight} kg",
            "تكلفة الكيلو" to "${formulation.costPerKg} SDG"
        )

        for (i in metrics.indices) {
            val cx = 25f + (i * colW) + (colW / 2f)
            canvas.drawText(metrics[i].first, cx, y + 25f, kpiTitle)
            canvas.drawText(metrics[i].second, cx, y + 47f, kpiValue)
        }

        y += 85f

        // Ingredients Table Header
        val thPaint = Paint().apply {
            color = Color.parseColor("#1E293B")
            style = Paint.Style.FILL
        }
        canvas.drawRect(25f, y, 570f, y + 24f, thPaint)

        val thText = Paint().apply {
            color = Color.WHITE
            textSize = 10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        canvas.drawText("م", 555f, y + 16f, thText)
        canvas.drawText("المادة الخام", 530f, y + 16f, thText)
        canvas.drawText("النوع", 320f, y + 16f, thText)
        canvas.drawText("الوزن (kg)", 210f, y + 16f, thText)
        canvas.drawText("الكثافة", 140f, y + 16f, thText)
        canvas.drawText("صلابة %", 80f, y + 16f, thText)

        y += 24f

        val trPaint1 = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        val trPaint2 = Paint().apply { color = Color.parseColor("#F1F5F9"); style = Paint.Style.FILL }
        val trText = Paint().apply {
            color = Color.parseColor("#1E293B")
            textSize = 9.5f
            isAntiAlias = true
            textAlign = Paint.Align.RIGHT
        }

        for (idx in ingredients.indices) {
            val item = ingredients[idx]
            val bg = if (idx % 2 == 0) trPaint1 else trPaint2
            canvas.drawRect(25f, y, 570f, y + 20f, bg)

            canvas.drawText("${idx + 1}", 555f, y + 14f, trText)
            canvas.drawText(item.name.take(30), 530f, y + 14f, trText)
            canvas.drawText(item.type.take(16), 320f, y + 14f, trText)
            canvas.drawText(numberFormat.format(item.weight), 210f, y + 14f, trText)
            canvas.drawText(numberFormat.format(item.density), 140f, y + 14f, trText)
            canvas.drawText("${numberFormat.format(item.solidsPercent)}%", 80f, y + 14f, trText)

            y += 20f
            if (y > 700f) break
        }

        y += 20f

        // Technical Analysis Notes Box
        if (formulation.technicalAnalysis.isNotBlank()) {
            canvas.drawRoundRect(25f, y, 570f, y + 55f, 6f, 6f, boxPaint)
            canvas.drawRoundRect(25f, y, 570f, y + 55f, 6f, 6f, borderPaint)

            val notesTitle = Paint().apply {
                color = Color.parseColor("#2563EB")
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
            canvas.drawText("التحليل الفني للتركيبة والتوصيات المخبرية:", 555f, y + 20f, notesTitle)

            val notesContent = Paint().apply {
                color = Color.parseColor("#334155")
                textSize = 9f
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }
            canvas.drawText(formulation.technicalAnalysis.take(95), 555f, y + 38f, notesContent)

            y += 75f
        }

        // Signatures Section
        val signLine = Paint().apply {
            color = Color.parseColor("#94A3B8")
            strokeWidth = 1f
        }
        canvas.drawLine(50f, y + 35f, 200f, y + 35f, signLine)
        canvas.drawLine(395f, y + 35f, 545f, y + 35f, signLine)

        val signText = Paint().apply {
            color = Color.parseColor("#475569")
            textSize = 10f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("اعتماد مسؤول المختبر والجودة", 125f, y + 50f, signText)
        canvas.drawText("اعتماد مدير الإنتاج والتصنيع", 470f, y + 50f, signText)

        // Footer
        val footerPaint = Paint().apply {
            color = Color.parseColor("#94A3B8")
            textSize = 8.5f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText(
            "شركة PMP للدهانات والطلاءات المحدودة • مختبر العلوم الكيميائية والأبحاث (R&D Lab)",
            297f,
            820f,
            footerPaint
        )

        document.finishPage(page)

        val cacheFolder = File(context.cacheDir, "lab_reports")
        if (!cacheFolder.exists()) {
            cacheFolder.mkdirs()
        }
        val file = File(cacheFolder, "PMP_Formula_${formulation.formulaNo}.pdf")
        FileOutputStream(file).use { out ->
            document.writeTo(out)
        }
        document.close()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "بطاقة مواصفات تركيبة دهان معملية: ${formulation.productName}")
            putExtra(Intent.EXTRA_TEXT, "مرفق بطاقة المواصفات الكيميائية لتركيبة (${formulation.productName}) - كود: ${formulation.formulaNo}\nمختبر PMP PAINTS العلمي.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, "مشاركة تقرير التركيبة الكيميائية")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)

        file
    }
}

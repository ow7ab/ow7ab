package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.example.data.local.FactoryItemEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Zero-bloat OOXML (.xlsx / .xlsm) Workbook Analyzer & Staged Formula Importer.
 * محلل ملفات التركيبات الكيميائية (Excel .xlsx) بدون مكتبات ثقيلة خارجية مع التحقق الحسابي والمراجعة قبل الاستيراد.
 *
 * Strictly adheres to PMP confidentiality & IP rules:
 * - Preserves original formulas (<f>) and fixed values (<v>) verbatim.
 * - Detects cross-sheet links, external workbook references ([1]...), and broken refs (#REF!, #NAME?, etc.).
 * - Verifies formula arithmetic vs cached values prior to preview/import.
 * - Never writes confidential formulation contents to logs, errors, or notifications.
 * - Never auto-approves or auto-overwrites existing approved formulations: every imported item enters as NEEDS_REVIEW.
 */
object LabExcelAnalyzer {

    data class ExcelCell(
        val ref: String,       // e.g. "B4"
        val colIndex: Int,     // 0-based
        val rowIndex: Int,     // 1-based
        val rawType: String,   // s, n, b, e, str, inlineStr
        val cachedValue: String,
        val formula: String?,
        val evaluatedValue: Double? = null,
        val verificationMismatch: Boolean = false,
        val isExternalRef: Boolean = false,
        val isBrokenRef: Boolean = false,
        val crossSheetTargets: List<String> = emptyList()
    ) {
        val isFormulaCell: Boolean get() = !formula.isNullOrBlank()
    }

    data class ParsedWorksheet(
        val name: String,
        val sheetIndex: Int,
        val rows: Map<Int, Map<Int, ExcelCell>>,
        val formulaCellCount: Int,
        val fixedCellCount: Int,
        val crossSheetLinks: List<String>,
        val externalLinkWarnings: List<String>,
        val brokenRefWarnings: List<String>,
        val calculationMismatches: List<String>
    )

    data class MaterialMatchCandidate(
        val rawName: String,
        val normalizedCode: String,
        val matchedExistingItemId: Long?,
        val matchedExistingName: String?,
        val matchedExistingPrice: Double?,
        val importedPrice: Double,
        val unit: String,
        val isNewMaterial: Boolean
    )

    data class ImportedFormulaDraft(
        val sourceSheetName: String,
        val suggestedFormulaNo: String,
        val productName: String,
        val category: String,
        val ingredients: List<FormulationIngredient>,
        val declaredTotalWeight: Double?,
        val declaredTotalCost: Double?,
        val calculatedPvcResult: PvcEngine.PvcCalculationResult,
        val formulaCountInSheet: Int,
        val fixedValueCountInSheet: Int,
        val matchingExistingFormulaId: Long?,
        val matchingExistingFormulaVersion: Int?,
        val validationWarnings: List<String>,
        val sourceSnapshotJson: String
    )

    data class WorkbookAnalysisResult(
        val fileName: String,
        val fileSizeBytes: Int,
        val sha256Hex: String,
        val worksheetCount: Int,
        val worksheets: List<ParsedWorksheet>,
        val externalWorkbookLinks: List<String>,
        val crossSheetRelationships: List<String>,
        val brokenReferenceIssues: List<String>,
        val formulaVerificationMismatches: List<String>,
        val deduplicatedMaterials: List<MaterialMatchCandidate>,
        val candidateFormulas: List<ImportedFormulaDraft>,
        val totalFormulaCells: Int,
        val totalFixedCells: Int
    )

    private val ERROR_TOKENS = listOf("#REF!", "#NAME?", "#VALUE!", "#N/A", "#DIV/0!", "#NUM!", "#NULL!")
    private val SHEET_REF_REGEX = Regex("""(?:'([^']+)'|([A-Za-z0-9_\u0600-\u06FF ]+))!\$?[A-Z]{1,3}\$?\d+(?::\$?[A-Z]{1,3}\$?\d+)?""")
    private val CELL_REF_REGEX = Regex("""\$?([A-Z]{1,3})\$?(\d+)""")

    fun analyzeUri(
        context: Context,
        uri: Uri,
        displayFileName: String,
        existingRawMaterials: List<FactoryItemEntity>,
        existingFormulations: List<FormulationEntity>
    ): Result<WorkbookAnalysisResult> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { readAllBytesLimited(it) }
            ?: throw IllegalStateException("تعذر فتح ملف الإكسل المحدد.")
        analyzeBytes(bytes, displayFileName, existingRawMaterials, existingFormulations).getOrThrow()
    }

    /**
     * Stores an encrypted or private local archive copy in app internal storage (never accessible externally).
     */
    fun archiveOriginalWorkbookPrivately(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        sha256: String
    ): String {
        val vaultDir = File(context.filesDir, "lab_excel_vault")
        if (!vaultDir.exists()) vaultDir.mkdirs()
        val safeName = fileName.replace(Regex("""[^A-Za-z0-9._\-\u0600-\u06FF]"""), "_")
        val archiveFile = File(vaultDir, "${sha256.take(12)}_$safeName")
        archiveFile.writeBytes(bytes)
        return archiveFile.absolutePath
    }

    fun analyzeBytes(
        bytes: ByteArray,
        fileName: String,
        existingRawMaterials: List<FactoryItemEntity>,
        existingFormulations: List<FormulationEntity>
    ): Result<WorkbookAnalysisResult> = runCatching {
        require(bytes.isNotEmpty()) { "الملف المحدد فارغ." }
        val sha256 = sha256Hex(bytes)

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normName = entry.name.trimStart('/')
                    entries[normName] = readAllBytesLimited(zis)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val workbookBytes = entries["xl/workbook.xml"]
            ?: throw IllegalArgumentException("الملف ليس بصيغة Excel (.xlsx) صالحة (ملف workbook.xml مفقود).")

        val sharedStrings = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val relsMap = entries["xl/_rels/workbook.xml.rels"]?.let { parseRelationships(it) } ?: emptyMap()
        val sheetDefs = parseWorkbookSheets(workbookBytes, relsMap)

        val externalLinksFromRels = mutableListOf<String>()
        entries.filterKeys { it.startsWith("xl/externalLinks/") && it.endsWith(".xml") }
            .forEach { (path, _) -> externalLinksFromRels.add("مرجع خارجي مضمن: $path") }
        relsMap.values.filter { it.type.contains("externalLink", ignoreCase = true) }.forEach {
            externalLinksFromRels.add("ارتباط خارجي (${it.target})")
        }

        // First pass: parse raw cells per sheet
        val rawSheets = sheetDefs.mapIndexed { idx, def ->
            val xmlBytes = entries[def.path]
                ?: entries["xl/${def.path.removePrefix("xl/")}"]
            val parsedRows = if (xmlBytes != null) {
                parseWorksheetCells(xmlBytes, sharedStrings)
            } else {
                emptyMap()
            }
            def.name to parsedRows
        }

        val sheetCellMap = rawSheets.associate { (name, rows) ->
            val byRef = mutableMapOf<String, ExcelCell>()
            rows.values.forEach { rowCols ->
                rowCols.values.forEach { cell -> byRef[cell.ref] = cell }
            }
            name to byRef
        }

        // Second pass: evaluate formulas safely & detect cross-sheet, external, broken refs
        val crossSheetSet = LinkedHashSet<String>()
        val externalSet = LinkedHashSet<String>(externalLinksFromRels)
        val brokenList = mutableListOf<String>()
        val mismatchList = mutableListOf<String>()

        var totalFormulaCells = 0
        var totalFixedCells = 0

        val analyzedSheets = rawSheets.mapIndexed { sheetIdx, (sheetName, rawRows) ->
            var sheetFormulaCount = 0
            var sheetFixedCount = 0
            val sheetCross = LinkedHashSet<String>()
            val sheetExt = mutableListOf<String>()
            val sheetBroken = mutableListOf<String>()
            val sheetMismatches = mutableListOf<String>()

            val updatedRows = linkedMapOf<Int, MutableMap<Int, ExcelCell>>()
            for ((rIdx, cols) in rawRows) {
                val updatedCols = linkedMapOf<Int, ExcelCell>()
                for ((cIdx, cell) in cols) {
                    val f = cell.formula
                    val cachedNum = cell.cachedValue.toDoubleOrNull()
                    val isBrokenErrorVal = ERROR_TOKENS.any { cell.cachedValue.contains(it, ignoreCase = true) }
                    val isExtFormula = f != null && (
                        f.contains("[") && f.contains("]") ||
                            f.contains("http://", ignoreCase = true) ||
                            f.contains("https://", ignoreCase = true) ||
                            f.contains("file://", ignoreCase = true)
                        )
                    val isBrokenFormula = isBrokenErrorVal ||
                        (f != null && ERROR_TOKENS.any { f.contains(it, ignoreCase = true) })

                    val crossTargets = if (f != null) {
                        SHEET_REF_REGEX.findAll(f).mapNotNull { m ->
                            val targetSheet = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
                            if (targetSheet.isNotEmpty() && !targetSheet.equals(sheetName, ignoreCase = true)) {
                                targetSheet
                            } else null
                        }.toList().distinct()
                    } else emptyList()

                    crossTargets.forEach { target ->
                        val linkLabel = "$sheetName -> $target"
                        sheetCross.add(linkLabel)
                        crossSheetSet.add(linkLabel)
                    }
                    if (isExtFormula) {
                        val extWarn = "ورقة '$sheetName' خلية ${cell.ref}: تحتوي على رابط خارجي في المعادلة."
                        sheetExt.add(extWarn)
                        externalSet.add(extWarn)
                    }
                    if (isBrokenFormula) {
                        val brWarn = "ورقة '$sheetName' خلية ${cell.ref}: خطأ مرجعي (${cell.cachedValue.ifBlank { "#REF!" }})."
                        sheetBroken.add(brWarn)
                        brokenList.add(brWarn)
                    }

                    var evaluated: Double? = null
                    var hasMismatch = false
                    if (!f.isNullOrBlank()) {
                        sheetFormulaCount++
                        totalFormulaCells++
                        if (!isExtFormula && !isBrokenFormula) {
                            evaluated = evaluateFormula(
                                formula = f,
                                currentSheet = sheetName,
                                allSheets = sheetCellMap,
                                visited = mutableSetOf("$sheetName!${cell.ref}")
                            )
                            if (evaluated != null && cachedNum != null) {
                                val diff = abs(evaluated - cachedNum)
                                val tol = max(0.01, abs(cachedNum) * 0.002)
                                if (diff > tol) {
                                    hasMismatch = true
                                    val msg = "ورقة '$sheetName' خلية ${cell.ref}: القيمة المحسوبة ($evaluated) تختلف عن المخزنة ($cachedNum)."
                                    sheetMismatches.add(msg)
                                    mismatchList.add(msg)
                                }
                            }
                        }
                    } else if (cell.cachedValue.isNotBlank()) {
                        sheetFixedCount++
                        totalFixedCells++
                    }

                    updatedCols[cIdx] = cell.copy(
                        evaluatedValue = evaluated,
                        verificationMismatch = hasMismatch,
                        isExternalRef = isExtFormula,
                        isBrokenRef = isBrokenFormula,
                        crossSheetTargets = crossTargets
                    )
                }
                updatedRows[rIdx] = updatedCols
            }

            ParsedWorksheet(
                name = sheetName,
                sheetIndex = sheetIdx,
                rows = updatedRows,
                formulaCellCount = sheetFormulaCount,
                fixedCellCount = sheetFixedCount,
                crossSheetLinks = sheetCross.toList(),
                externalLinkWarnings = sheetExt,
                brokenRefWarnings = sheetBroken,
                calculationMismatches = sheetMismatches
            )
        }

        // Third pass: extract formulations & deduplicate raw materials
        val materialCandidatesMap = linkedMapOf<String, MaterialMatchCandidate>()
        val drafts = mutableListOf<ImportedFormulaDraft>()

        analyzedSheets.forEachIndexed { idx, sheet ->
            val extracted = extractFormulaFromWorksheet(
                sheet = sheet,
                sheetOrder = idx + 1,
                existingRawMaterials = existingRawMaterials,
                existingFormulations = existingFormulations,
                materialDedupMap = materialCandidatesMap
            )
            if (extracted != null) {
                drafts.add(extracted)
            }
        }

        WorkbookAnalysisResult(
            fileName = fileName,
            fileSizeBytes = bytes.size,
            sha256Hex = sha256,
            worksheetCount = analyzedSheets.size,
            worksheets = analyzedSheets,
            externalWorkbookLinks = externalSet.toList(),
            crossSheetRelationships = crossSheetSet.toList(),
            brokenReferenceIssues = brokenList,
            formulaVerificationMismatches = mismatchList,
            deduplicatedMaterials = materialCandidatesMap.values.toList(),
            candidateFormulas = drafts,
            totalFormulaCells = totalFormulaCells,
            totalFixedCells = totalFixedCells
        )
    }

    private fun extractFormulaFromWorksheet(
        sheet: ParsedWorksheet,
        sheetOrder: Int,
        existingRawMaterials: List<FactoryItemEntity>,
        existingFormulations: List<FormulationEntity>,
        materialDedupMap: MutableMap<String, MaterialMatchCandidate>
    ): ImportedFormulaDraft? {
        val sortedRowNums = sheet.rows.keys.sorted()
        if (sortedRowNums.isEmpty()) return null

        var headerRowIdx: Int? = null
        var nameCol = -1
        var codeCol = -1
        var weightCol = -1
        var priceCol = -1
        var densityCol = -1
        var solidsCol = -1
        var typeCol = -1
        var unitCol = -1

        var discoveredProductName = sheet.name
        var discoveredFormulaCode = ""
        var discoveredCategory = "بوهيات"

        for (rNum in sortedRowNums.take(25)) {
            val row = sheet.rows[rNum] ?: continue
            val rowTexts = row.entries.sortedBy { it.key }.map { it.key to it.value.cachedValue.trim() }
            val joined = rowTexts.joinToString(" ") { it.second }.lowercase(Locale.ROOT)

            if (headerRowIdx == null) {
                // Scan top metadata lines before table header
                for ((colIdx, txt) in rowTexts) {
                    val lower = txt.lowercase(Locale.ROOT)
                    val nextVal = row[colIdx + 1]?.cachedValue?.trim().orEmpty()
                    if ((lower.contains("اسم المنتج") || lower.contains("product") || lower.contains("المنتج")) && nextVal.isNotBlank()) {
                        discoveredProductName = nextVal
                    }
                    if ((lower.contains("كود") || lower.contains("code") || lower.contains("formula")) && nextVal.isNotBlank()) {
                        discoveredFormulaCode = nextVal
                    }
                    if ((lower.contains("تصنيف") || lower.contains("فئة") || lower.contains("category")) && nextVal.isNotBlank()) {
                        discoveredCategory = nextVal
                    }
                }
            }

            val looksLikeHeader = (joined.contains("مادة") || joined.contains("صنف") || joined.contains("خام") ||
                joined.contains("material") || joined.contains("ingredient") || joined.contains("item")) &&
                (joined.contains("كمية") || joined.contains("وزن") || joined.contains("نسبة") ||
                    joined.contains("qty") || joined.contains("weight") || joined.contains("سعر") || joined.contains("price"))

            if (looksLikeHeader) {
                headerRowIdx = rNum
                for ((colIdx, txt) in rowTexts) {
                    val h = txt.lowercase(Locale.ROOT)
                    when {
                        nameCol == -1 && (h.contains("مادة") || h.contains("صنف") || h.contains("اسم") || h.contains("material") || h.contains("ingredient")) ->
                            nameCol = colIdx
                        codeCol == -1 && (h.contains("كود") || h.contains("رمز") || h.contains("code") || h.contains("sku")) ->
                            codeCol = colIdx
                        weightCol == -1 && (h.contains("وزن") || h.contains("كمية") || h.contains("نسبة") || h.contains("weight") || h.contains("qty") || h.contains("%")) ->
                            weightCol = colIdx
                        priceCol == -1 && (h.contains("سعر") || h.contains("تكلفة") || h.contains("price") || h.contains("cost")) ->
                            priceCol = colIdx
                        densityCol == -1 && (h.contains("كثافة") || h.contains("density") || h.contains("s.g")) ->
                            densityCol = colIdx
                        solidsCol == -1 && (h.contains("صلب") || h.contains("صلابة") || h.contains("solids")) ->
                            solidsCol = colIdx
                        typeCol == -1 && (h.contains("نوع") || h.contains("وظيفة") || h.contains("type") || h.contains("role")) ->
                            typeCol = colIdx
                        unitCol == -1 && (h.contains("وحدة") || h.contains("unit")) ->
                            unitCol = colIdx
                    }
                }
                break
            }
        }

        // Heuristic fallback if no explicit header row found: detect numeric + text rows
        if (headerRowIdx != null && weightCol == -1) {
            // Sheet is a material price/reference table (no weight/qty/% column) -> do not treat as a formula
            return null
        }
        val startRow = headerRowIdx?.let { it + 1 } ?: 1
        if (nameCol == -1) {
            nameCol = 0
            weightCol = 1
            priceCol = 2
        }

        val ingredients = mutableListOf<FormulationIngredient>()
        val warnings = mutableListOf<String>()
        var declaredTotalWeight: Double? = null
        var declaredTotalCost: Double? = null
        val snapshotCells = JSONArray()

        for (rNum in sortedRowNums) {
            if (rNum < startRow) continue
            val row = sheet.rows[rNum] ?: continue
            val rawName = row[nameCol]?.cachedValue?.trim().orEmpty()
            if (rawName.isBlank()) continue

            val lowerName = rawName.lowercase(Locale.ROOT)
            val weightCell = row[weightCol]
            val parsedWeight = weightCell?.evaluatedValue ?: weightCell?.cachedValue?.toDoubleOrNull()
            val priceCell = if (priceCol >= 0) row[priceCol] else null
            val parsedPrice = priceCell?.evaluatedValue ?: priceCell?.cachedValue?.toDoubleOrNull()

            if (lowerName.startsWith("الإجمالي") || lowerName.startsWith("المجموع") ||
                lowerName.startsWith("total") || lowerName.startsWith("sum")
            ) {
                if (parsedWeight != null) declaredTotalWeight = parsedWeight
                if (parsedPrice != null) declaredTotalCost = parsedPrice
                continue
            }

            if (parsedWeight == null && parsedPrice == null) continue
            val weight = (parsedWeight ?: 0.0)
            if (weight < 0.0) {
                warnings.add("السطر $rNum: كمية سالبة تم ضبطها للصفر للمراجعة.")
            }
            val safeWeight = weight.coerceAtLeast(0.0)
            val safePrice = (parsedPrice ?: 0.0).coerceAtLeast(0.0)
            val codeVal = if (codeCol >= 0) row[codeCol]?.cachedValue?.trim().orEmpty() else ""
            val unitVal = if (unitCol >= 0) row[unitCol]?.cachedValue?.trim()?.ifBlank { "كجم" } ?: "كجم" else "كجم"
            val densityVal = if (densityCol >= 0) {
                (row[densityCol]?.evaluatedValue ?: row[densityCol]?.cachedValue?.toDoubleOrNull()) ?: 1.0
            } else 1.0
            val solidsVal = if (solidsCol >= 0) {
                (row[solidsCol]?.evaluatedValue ?: row[solidsCol]?.cachedValue?.toDoubleOrNull()) ?: 100.0
            } else 100.0
            val typeVal = if (typeCol >= 0) {
                row[typeCol]?.cachedValue?.trim()?.ifBlank { inferMaterialType(rawName) } ?: inferMaterialType(rawName)
            } else inferMaterialType(rawName)

            val matchedExisting = matchRawMaterial(rawName, codeVal, existingRawMaterials)
            val effectiveUnitCost = if (safePrice > 0.0) safePrice else (matchedExisting?.lastPurchasePrice?.takeIf { it > 0.0 } ?: matchedExisting?.unitCostEgp ?: 0.0)

            val dedupKey = (codeVal.ifBlank { rawName }).trim().lowercase(Locale.ROOT)
            materialDedupMap.putIfAbsent(
                dedupKey,
                MaterialMatchCandidate(
                    rawName = rawName,
                    normalizedCode = codeVal,
                    matchedExistingItemId = matchedExisting?.id,
                    matchedExistingName = matchedExisting?.name,
                    matchedExistingPrice = matchedExisting?.lastPurchasePrice?.takeIf { it > 0.0 } ?: matchedExisting?.unitCostEgp,
                    importedPrice = safePrice,
                    unit = unitVal,
                    isNewMaterial = matchedExisting == null
                )
            )

            ingredients.add(
                FormulationIngredient(
                    id = UUID.randomUUID().toString(),
                    materialId = matchedExisting?.id,
                    itemCode = codeVal,
                    name = rawName,
                    type = typeVal,
                    unit = unitVal,
                    weight = safeWeight,
                    density = if (densityVal > 0.01) densityVal else 1.0,
                    solidsPercent = solidsVal.coerceIn(0.0, 100.0),
                    unitCost = effectiveUnitCost,
                    supplierName = matchedExisting?.supplierName.orEmpty(),
                    notes = if (weightCell?.isFormulaCell == true) "خلية معادلة أصلية محفوظة" else "قيمة ثابتة من الإكسل"
                )
            )

            snapshotCells.put(
                JSONObject().apply {
                    put("row", rNum)
                    put("name", rawName)
                    put("weightValue", safeWeight)
                    put("weightFormula", weightCell?.formula ?: "")
                    put("priceValue", safePrice)
                    put("priceFormula", priceCell?.formula ?: "")
                }
            )
        }

        if (ingredients.isEmpty() || ingredients.all { it.weight <= 0.0 }) return null

        val calc = PvcEngine.calculate(ingredients)
        if (declaredTotalWeight != null && abs(declaredTotalWeight - calc.totalBatchWeight) > 0.5) {
            warnings.add("اختلاف مجموع الأوزان: المعلن في الورقة ($declaredTotalWeight) مقابل المحسوب (${calc.totalBatchWeight}).")
        }
        if (declaredTotalCost != null && abs(declaredTotalCost - calc.totalCost) > 1.0) {
            warnings.add("اختلاف التكلفة الإجمالية: المعلن في الورقة ($declaredTotalCost) مقابل المحسوب (${calc.totalCost}).")
        }
        if (sheet.brokenRefWarnings.isNotEmpty()) {
            warnings.add("تحذير: الورقة تحتوي على ${sheet.brokenRefWarnings.size} مرجع معطل.")
        }
        if (sheet.externalLinkWarnings.isNotEmpty()) {
            warnings.add("تحذير: الورقة تحتوي على روابط خارجية قد تكون غير متصلة.")
        }
        warnings.addAll(calc.warnings)

        val suggestedNo = discoveredFormulaCode.ifBlank { "XLS-${sheetOrder.toString().padStart(2, '0')}" }
        val existingMatch = existingFormulations.firstOrNull {
            it.formulaNo.equals(suggestedNo, ignoreCase = true) ||
                it.productName.equals(discoveredProductName, ignoreCase = true)
        }

        return ImportedFormulaDraft(
            sourceSheetName = sheet.name,
            suggestedFormulaNo = suggestedNo,
            productName = discoveredProductName,
            category = discoveredCategory,
            ingredients = ingredients,
            declaredTotalWeight = declaredTotalWeight,
            declaredTotalCost = declaredTotalCost,
            calculatedPvcResult = calc,
            formulaCountInSheet = sheet.formulaCellCount,
            fixedValueCountInSheet = sheet.fixedCellCount,
            matchingExistingFormulaId = existingMatch?.id,
            matchingExistingFormulaVersion = existingMatch?.versionNumber,
            validationWarnings = warnings.distinct(),
            sourceSnapshotJson = JSONObject().apply {
                put("sheet", sheet.name)
                put("formulaCells", sheet.formulaCellCount)
                put("fixedCells", sheet.fixedCellCount)
                put("rows", snapshotCells)
            }.toString()
        )
    }

    private fun matchRawMaterial(
        name: String,
        code: String,
        existing: List<FactoryItemEntity>
    ): FactoryItemEntity? {
        val cleanName = normalizeText(name)
        val cleanCode = code.trim().lowercase(Locale.ROOT)
        if (cleanCode.isNotEmpty()) {
            existing.firstOrNull {
                it.syncUuid.equals(cleanCode, ignoreCase = true) ||
                    it.id.toString() == cleanCode
            }?.let { return it }
        }
        return existing.firstOrNull {
            val candidate = normalizeText(it.name)
            candidate == cleanName ||
                (cleanName.length >= 4 && (candidate.contains(cleanName) || cleanName.contains(candidate)))
        }
    }

    private fun normalizeText(s: String): String =
        s.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    private fun inferMaterialType(name: String): String {
        val l = name.lowercase(Locale.ROOT)
        return when {
            l.contains("tio2") || l.contains("تيتانيوم") || l.contains("أكسيد") || l.contains("صبغ") || l.contains("كربون") -> "صبغة / ملون"
            l.contains("كربونات") || l.contains("تالك") || l.contains("بارايت") || l.contains("بودر") || l.contains("caco3") -> "مالئ وحبيبات"
            l.contains("راتنج") || l.contains("أكريليك") || l.contains("ستايرين") || l.contains("resin") || l.contains("acrylic") || l.contains("vam") -> "راتنج / بوليمر رابط"
            l.contains("ماء") || l.contains("مذيب") || l.contains("water") || l.contains("solvent") -> "مذيب / ماء"
            else -> "إضافات ومساعدات"
        }
    }

    // ==================== OOXML XML Parsers (ZipInputStream + XmlPullParser) ====================

    private data class WorkbookRel(val id: String, val target: String, val type: String)
    private data class SheetDef(val name: String, val path: String)

    private fun createXmlParser(): XmlPullParser =
        runCatching { Xml.newPullParser() }.getOrElse {
            XmlPullParserFactory.newInstance().newPullParser()
        }

    private fun parseSharedStrings(xmlBytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = createXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(String(xmlBytes, Charsets.UTF_8)))
        var inSi = false
        var inText = false
        val sb = StringBuilder()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "si" -> {
                            inSi = true
                            sb.setLength(0)
                        }
                        "t" -> if (inSi) inText = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi && inText) sb.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "t" -> inText = false
                        "si" -> {
                            inSi = false
                            list.add(sb.toString())
                        }
                    }
                }
            }
            ev = parser.next()
        }
        return list
    }

    private fun parseRelationships(xmlBytes: ByteArray): Map<String, WorkbookRel> {
        val map = mutableMapOf<String, WorkbookRel>()
        val parser = createXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(String(xmlBytes, Charsets.UTF_8)))
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id").orEmpty()
                val target = parser.getAttributeValue(null, "Target").orEmpty()
                val type = parser.getAttributeValue(null, "Type").orEmpty()
                if (id.isNotEmpty()) {
                    val normTarget = target.trimStart('/').let {
                        if (it.startsWith("xl/")) it else "xl/$it"
                    }
                    map[id] = WorkbookRel(id, normTarget, type)
                }
            }
            ev = parser.next()
        }
        return map
    }

    private fun parseWorkbookSheets(xmlBytes: ByteArray, rels: Map<String, WorkbookRel>): List<SheetDef> {
        val list = mutableListOf<SheetDef>()
        val parser = createXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(String(xmlBytes, Charsets.UTF_8)))
        var ev = parser.eventType
        var idx = 1
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && parser.name == "sheet") {
                val name = parser.getAttributeValue(null, "name").orEmpty().ifBlank { "Sheet$idx" }
                val rId = parser.getAttributeValue(null, "r:id")
                    ?: parser.getAttributeValue(null, "id")
                    ?: ""
                val path = rels[rId]?.target ?: "xl/worksheets/sheet$idx.xml"
                list.add(SheetDef(name, path))
                idx++
            }
            ev = parser.next()
        }
        return list
    }

    private fun parseWorksheetCells(
        xmlBytes: ByteArray,
        sharedStrings: List<String>
    ): Map<Int, MutableMap<Int, ExcelCell>> {
        val rows = linkedMapOf<Int, MutableMap<Int, ExcelCell>>()
        val parser = createXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(String(xmlBytes, Charsets.UTF_8)))

        var curRef = ""
        var curType = ""
        var curFormula: String? = null
        var curValue = ""
        var inCell = false
        var tagMode = ""
        val sb = StringBuilder()

        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "c" -> {
                            inCell = true
                            curRef = parser.getAttributeValue(null, "r").orEmpty().uppercase(Locale.US)
                            curType = parser.getAttributeValue(null, "t").orEmpty()
                            curFormula = null
                            curValue = ""
                            tagMode = ""
                        }
                        "f" -> if (inCell) {
                            tagMode = "f"
                            sb.setLength(0)
                        }
                        "v" -> if (inCell) {
                            tagMode = "v"
                            sb.setLength(0)
                        }
                        "t" -> if (inCell && curType == "inlineStr") {
                            tagMode = "t"
                            sb.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inCell && tagMode.isNotEmpty()) sb.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "f" -> {
                            curFormula = sb.toString().trim()
                            tagMode = ""
                        }
                        "v" -> {
                            val rawV = sb.toString().trim()
                            curValue = if (curType == "s") {
                                val ssIdx = rawV.toIntOrNull()
                                if (ssIdx != null && ssIdx in sharedStrings.indices) sharedStrings[ssIdx] else rawV
                            } else rawV
                            tagMode = ""
                        }
                        "t" -> {
                            if (curType == "inlineStr") {
                                curValue = sb.toString().trim()
                                tagMode = ""
                            }
                        }
                        "c" -> {
                            inCell = false
                            val (colIdx, rowIdx) = parseCellCoordinates(curRef)
                            if (rowIdx > 0 && colIdx >= 0) {
                                val rowMap = rows.getOrPut(rowIdx) { linkedMapOf() }
                                rowMap[colIdx] = ExcelCell(
                                    ref = curRef,
                                    colIndex = colIdx,
                                    rowIndex = rowIdx,
                                    rawType = curType,
                                    cachedValue = curValue,
                                    formula = curFormula
                                )
                            }
                        }
                    }
                }
            }
            ev = parser.next()
        }
        return rows
    }

    private fun parseCellCoordinates(ref: String): Pair<Int, Int> {
        val m = CELL_REF_REGEX.matchEntire(ref.trim().uppercase(Locale.US)) ?: return -1 to -1
        val colStr = m.groupValues[1]
        val rowNum = m.groupValues[2].toIntOrNull() ?: return -1 to -1
        var col = 0
        for (ch in colStr) {
            col = col * 26 + (ch - 'A' + 1)
        }
        return (col - 1) to rowNum
    }

    // ==================== Safe Recursive-Descent Spreadsheet Evaluator ====================

    private fun evaluateFormula(
        formula: String,
        currentSheet: String,
        allSheets: Map<String, Map<String, ExcelCell>>,
        visited: MutableSet<String>
    ): Double? {
        if (visited.size > 40) return null
        val expr = formula.trim().removePrefix("=").trim()
        if (expr.isEmpty()) return null
        return runCatching {
            FormulaEvaluator(expr, currentSheet, allSheets, visited).parseExpression()
        }.getOrNull()
    }

    private class FormulaEvaluator(
        private val input: String,
        private val currentSheet: String,
        private val allSheets: Map<String, Map<String, ExcelCell>>,
        private val visited: MutableSet<String>
    ) {
        private var pos = 0

        fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                skipWs()
                left = when {
                    match('+') -> left + parseTerm()
                    match('-') -> left - parseTerm()
                    else -> return left
                }
            }
        }

        private fun parseTerm(): Double {
            var left = parsePower()
            while (true) {
                skipWs()
                left = when {
                    match('*') -> left * parsePower()
                    match('/') -> {
                        val right = parsePower()
                        if (abs(right) < 1e-12) throw ArithmeticException("Division by zero")
                        left / right
                    }
                    else -> return left
                }
            }
        }

        private fun parsePower(): Double {
            var base = parseUnary()
            skipWs()
            if (match('^')) {
                val exp = parsePower()
                base = base.pow(exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWs()
            return when {
                match('+') -> parseUnary()
                match('-') -> -parseUnary()
                else -> parsePostfixPercent()
            }
        }

        private fun parsePostfixPercent(): Double {
            var v = parsePrimary()
            skipWs()
            while (match('%')) v /= 100.0
            return v
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (match('(')) {
                val inner = parseExpression()
                skipWs()
                match(')')
                return inner
            }

            // Quoted sheet reference: 'Sheet Name'!A1
            if (pos < input.length && input[pos] == '\'') {
                val endQuote = input.indexOf('\'', pos + 1)
                if (endQuote > pos && endQuote + 1 < input.length && input[endQuote + 1] == '!') {
                    val sheetName = input.substring(pos + 1, endQuote)
                    pos = endQuote + 2
                    val refToken = readCellRefToken()
                    return resolveCellValue(sheetName, refToken)
                }
            }

            // Function or CellRef or Unquoted Sheet!Ref
            val start = pos
            if (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
                return input.substring(start, pos).toDouble()
            }

            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] in setOf('_', '$', '.', '!'))) {
                pos++
            }
            val token = input.substring(start, pos).trim()
            skipWs()
            if (match('(')) {
                val args = parseFunctionArgs(token.uppercase(Locale.US))
                return evalFunction(token.uppercase(Locale.US), args)
            }

            if (token.contains('!')) {
                val parts = token.split('!', limit = 2)
                return resolveCellValue(parts[0], parts[1])
            }
            return resolveCellValue(currentSheet, token)
        }

        private fun parseFunctionArgs(fnName: String): List<List<Double>> {
            val result = mutableListOf<List<Double>>()
            skipWs()
            if (match(')')) return result
            while (pos < input.length) {
                skipWs()
                val checkpoint = pos
                val rangeTry = tryReadRangeOrCondition(fnName, result.size)
                if (rangeTry != null) {
                    result.add(rangeTry)
                } else {
                    pos = checkpoint
                    result.add(listOf(parseExpression()))
                }
                skipWs()
                if (match(',')) continue
                if (match(';')) continue
                match(')')
                break
            }
            return result
        }

        private fun tryReadRangeOrCondition(fnName: String, argIndex: Int): List<Double>? {
            if (fnName == "IF" && argIndex == 0) {
                val lhs = parseExpression()
                skipWs()
                val op = when {
                    input.startsWith(">=", pos) -> { pos += 2; ">=" }
                    input.startsWith("<=", pos) -> { pos += 2; "<=" }
                    input.startsWith("<>", pos) -> { pos += 2; "<>" }
                    match('>') -> ">"
                    match('<') -> "<"
                    match('=') -> "="
                    else -> return listOf(if (abs(lhs) > 1e-9) 1.0 else 0.0)
                }
                val rhs = parseExpression()
                val cond = when (op) {
                    ">=" -> lhs >= rhs
                    "<=" -> lhs <= rhs
                    "<>" -> abs(lhs - rhs) > 1e-9
                    ">" -> lhs > rhs
                    "<" -> lhs < rhs
                    else -> abs(lhs - rhs) < 1e-9
                }
                return listOf(if (cond) 1.0 else 0.0)
            }

            val saved = pos
            val rangeEndIdx = findTopLevelSeparatorOrClose()
            val slice = input.substring(saved, rangeEndIdx).trim()
            if (slice.contains(':')) {
                val sheetParts = if (slice.contains('!')) slice.split('!', limit = 2) else listOf(currentSheet, slice)
                val targetSheet = sheetParts[0].trim('\'', ' ')
                val bounds = sheetParts[1].split(':', limit = 2)
                if (bounds.size == 2) {
                    val (c1, r1) = parseCellCoordinates(bounds[0])
                    val (c2, r2) = parseCellCoordinates(bounds[1])
                    if (c1 >= 0 && r1 > 0 && c2 >= 0 && r2 > 0) {
                        pos = rangeEndIdx
                        val vals = mutableListOf<Double>()
                        for (r in min(r1, r2)..max(r1, r2)) {
                            for (c in min(c1, c2)..max(c1, c2)) {
                                val ref = "${colIndexToLetters(c)}$r"
                                val v = resolveCellValueNullable(targetSheet, ref)
                                if (v != null) vals.add(v)
                            }
                        }
                        return vals
                    }
                }
            }
            pos = saved
            return null
        }

        private fun findTopLevelSeparatorOrClose(): Int {
            var depth = 0
            var i = pos
            while (i < input.length) {
                when (input[i]) {
                    '(' -> depth++
                    ')' -> if (depth == 0) return i else depth--
                    ',', ';' -> if (depth == 0) return i
                }
                i++
            }
            return i
        }

        private fun evalFunction(name: String, args: List<List<Double>>): Double {
            val flat = args.flatten()
            return when (name) {
                "SUM" -> flat.sum()
                "AVERAGE", "AVG" -> if (flat.isEmpty()) 0.0 else flat.sum() / flat.size
                "MIN" -> flat.minOrNull() ?: 0.0
                "MAX" -> flat.maxOrNull() ?: 0.0
                "COUNT" -> flat.size.toDouble()
                "ABS" -> abs(flat.firstOrNull() ?: 0.0)
                "ROUND" -> {
                    val v = args.getOrNull(0)?.firstOrNull() ?: 0.0
                    val digits = (args.getOrNull(1)?.firstOrNull() ?: 0.0).toInt().coerceIn(0, 6)
                    val factor = 10.0.pow(digits)
                    round(v * factor) / factor
                }
                "IF" -> {
                    val cond = (args.getOrNull(0)?.firstOrNull() ?: 0.0) != 0.0
                    if (cond) (args.getOrNull(1)?.firstOrNull() ?: 0.0)
                    else (args.getOrNull(2)?.firstOrNull() ?: 0.0)
                }
                else -> throw IllegalArgumentException("Unsupported function: $name")
            }
        }

        private fun readCellRefToken(): String {
            val start = pos
            while (pos < input.length && (input[pos].isLetterOrDigit() || input[pos] == '$')) pos++
            return input.substring(start, pos)
        }

        private fun resolveCellValue(sheetName: String, refToken: String): Double {
            return resolveCellValueNullable(sheetName, refToken) ?: 0.0
        }

        private fun resolveCellValueNullable(sheetName: String, refToken: String): Double? {
            val cleanRef = refToken.replace("$", "").uppercase(Locale.US)
            val sheet = allSheets[sheetName]
                ?: allSheets.entries.firstOrNull { it.key.equals(sheetName, ignoreCase = true) }?.value
                ?: return null
            val cell = sheet[cleanRef] ?: return null
            if (!cell.formula.isNullOrBlank()) {
                val key = "$sheetName!$cleanRef"
                if (key in visited) return cell.cachedValue.toDoubleOrNull()
                visited.add(key)
                val computed = evaluateFormula(cell.formula, sheetName, allSheets, visited)
                visited.remove(key)
                return computed ?: cell.cachedValue.toDoubleOrNull()
            }
            return cell.cachedValue.toDoubleOrNull()
        }

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun match(ch: Char): Boolean {
            if (pos < input.length && input[pos] == ch) {
                pos++
                return true
            }
            return false
        }
    }

    private fun colIndexToLetters(colIdx: Int): String {
        var n = colIdx + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun readAllBytesLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n = input.read(buf)
        while (n >= 0) {
            out.write(buf, 0, n)
            n = input.read(buf)
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

package com.example.util

import com.example.data.local.FactoryItemEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale

/**
 * PMP Science & Chemistry Lab — Paint Engineering Calculation Engine
 * المحرك الهندسي عالي الدقة لحسابات تركيبات الدهانات، النسب، التكاليف الثلاثية، وتكبير التشغيلات
 */
object PvcEngine {

    private val MC = MathContext(12, RoundingMode.HALF_UP)
    private val BD_100 = BigDecimal("100")

    data class IngredientCostBreakdown(
        val ingredientId: String,
        val materialId: Long?,
        val itemCode: String,
        val name: String,
        val type: String,
        val unit: String,
        val weightKg: Double,
        val weightPercent: Double,
        val density: Double,
        val solidsPercent: Double,
        val unitCost: Double,
        val lineCost: Double,
        val costContributionPercent: Double,
        val wetVolumeLiters: Double,
        val dryWeightKg: Double,
        val dryVolumeLiters: Double
    )

    data class PvcCalculationResult(
        val calculatedPvc: Double,
        val cpvcEstimated: Double,
        val totalSolidsWeight: Double,
        val totalSolidsVolume: Double,
        val totalBatchWeight: Double,
        val totalBatchVolume: Double,
        val calculatedDensity: Double,
        val totalCost: Double,
        val costPerKg: Double,
        val pigmentVolume: Double,
        val binderDryVolume: Double,
        val paintClassification: String,
        val technicalAnalysisText: String,
        val warnings: List<String>,
        val ingredientBreakdowns: List<IngredientCostBreakdown> = emptyList(),
        val totalPercentageCheck: Double = 100.0
    )

    data class ScaledFormulaResult(
        val targetBatchKg: Double,
        val scaleFactor: Double,
        val scaledIngredients: List<FormulationIngredient>,
        val scaledTotalCost: Double,
        val scaledCostPerKg: Double
    )

    data class MaterialPriceImpactLine(
        val materialName: String,
        val formulaUnitCost: Double,
        val currentMarketUnitCost: Double,
        val weightKg: Double,
        val deltaPerKgMaterial: Double,
        val deltaTotalBatchCost: Double,
        val deltaPercent: Double
    )

    data class ThreeCostComparisonReport(
        val formulaId: Long,
        val formulaNo: String,
        val versionNumber: Int,
        val approvalStatus: String,
        val historicalApprovedTotalCost: Double,
        val historicalApprovedCostPerKg: Double,
        val currentEstimatedTotalCost: Double,
        val currentEstimatedCostPerKg: Double,
        val actualProductionTotalCost: Double?,
        val actualProductionCostPerKg: Double?,
        val currentVsHistoricalDeltaTotal: Double,
        val currentVsHistoricalDeltaPct: Double,
        val actualVsHistoricalDeltaTotal: Double?,
        val actualVsHistoricalDeltaPct: Double?,
        val actualVsCurrentDeltaTotal: Double?,
        val priceImpacts: List<MaterialPriceImpactLine>
    )

    data class PresetMaterial(
        val name: String,
        val type: String,
        val defaultDensity: Double,
        val defaultSolidsPercent: Double,
        val defaultUnitCost: Double,
        val notes: String
    )

    val PRESET_MATERIALS = listOf(
        PresetMaterial("ثاني أكسيد التيتانيوم (Rutile TiO2)", "صبغة / ملون", 4.20, 100.0, 4.50, "صبغة بياض وتغطية أساسية"),
        PresetMaterial("كربونات كالسيوم ميكرونايزد (CaCO3 5 Micron)", "مالئ وحبيبات", 2.70, 100.0, 0.40, "بودرة تعبئة وإطفاء لمعة"),
        PresetMaterial("كربونات كالسيوم كالسيت (CaCO3 15 Micron)", "مالئ وحبيبات", 2.71, 100.0, 0.28, "مالئ اقتصادي"),
        PresetMaterial("تالك مائي (Talc Magnesium Silicate)", "مالئ وحبيبات", 2.75, 100.0, 0.65, "مقاومة الترسيب والتشقق"),
        PresetMaterial("بارايت كبريتات باريوم (Barium Sulfate)", "مالئ وحبيبات", 4.45, 100.0, 1.20, "كثافة ومقاومة كيميائية عالية"),
        PresetMaterial("أكسيد حديد أحمر (Red Iron Oxide Fe2O3)", "صبغة / ملون", 5.10, 100.0, 3.20, "تلوين أحمر قوي ومقاوم للشمس"),
        PresetMaterial("أكسيد حديد أصفر (Yellow Iron Oxide)", "صبغة / ملون", 4.05, 100.0, 3.40, "تلوين أصفر ثابت"),
        PresetMaterial("كربون بلاك أسود (Carbon Black)", "صبغة / ملون", 1.80, 100.0, 4.80, "صبغة سواد داكن عالي التركيز"),
        PresetMaterial("راتنج أكريليك نقي 50% (Pure Acrylic Emulsion)", "راتنج / بوليمر رابط", 1.06, 50.0, 2.80, "أعلى مقاومة جوية ولمعان"),
        PresetMaterial("راتنج ستايرين أكريليك 50% (Styrene Acrylic)", "راتنج / بوليمر رابط", 1.04, 50.0, 2.10, "رابط مطفي اقتصادي متوازن"),
        PresetMaterial("راتنج فاينيل أكريليك (Vinyl Acrylic VAM)", "راتنج / بوليمر رابط", 1.05, 52.0, 1.85, "دهانات داخلية ومعجون"),
        PresetMaterial("ماء التشغيل (Process Water)", "مذيب / ماء", 1.00, 0.0, 0.05, "وسط التشتيت والمذيب الأساسي"),
        PresetMaterial("تيكسانول (Texanol Coalescent)", "إضافات ومساعدات", 0.95, 0.0, 5.20, "مساعد تكوين الغشاء (MFFT)"),
        PresetMaterial("مغلظ سليلوز (HEC Cellulose)", "إضافات ومساعدات", 1.00, 25.0, 6.50, "ضبط القوام واللزوجة"),
        PresetMaterial("مشتت بيجمنت (Dispersant Agent)", "إضافات ومساعدات", 1.15, 40.0, 4.20, "تفتيت وتثبيت الأكاسيد"),
        PresetMaterial("مانع رغوة (Defoamer PD-01)", "إضافات ومساعدات", 0.92, 100.0, 7.00, "كسر الفقاعات أثناء الخلط"),
        PresetMaterial("مبيد حفظ العبوات (In-can Biocide)", "إضافات ومساعدات", 1.05, 20.0, 8.00, "حفظ مضاد للبكتيريا والفطريات")
    )

    private fun bd(v: Double): BigDecimal =
        if (v.isNaN() || v.isInfinite()) BigDecimal.ZERO else BigDecimal.valueOf(v)

    /**
     * Calculates full PVC, solids, density, and ingredient-level cost/weight contribution
     * using BigDecimal accumulation before final presentation rounding.
     */
    fun calculate(ingredients: List<FormulationIngredient>): PvcCalculationResult {
        val warnings = mutableListOf<String>()

        var totalBatchWeight = BigDecimal.ZERO
        var totalSolidsWeight = BigDecimal.ZERO
        var totalWetVolume = BigDecimal.ZERO
        var totalDryVolume = BigDecimal.ZERO
        var pigmentVolume = BigDecimal.ZERO
        var binderDryVolume = BigDecimal.ZERO
        var totalCost = BigDecimal.ZERO

        data class RawLineCalc(
            val item: FormulationIngredient,
            val weight: BigDecimal,
            val density: BigDecimal,
            val solidsPct: BigDecimal,
            val unitCost: BigDecimal,
            val dryWeight: BigDecimal,
            val wetVol: BigDecimal,
            val dryVol: BigDecimal,
            val lineCost: BigDecimal
        )

        val lineCalcs = ArrayList<RawLineCalc>(ingredients.size)

        for (item in ingredients) {
            val weightVal = item.weight.coerceAtLeast(0.0)
            if (item.weight < 0.0) {
                warnings.add("تم تجاهل وزن سالب للمادة '${item.name}'.")
            }
            val densityVal = if (item.density > 0.01) item.density else 1.0
            if (item.density <= 0.01 && weightVal > 0.0) {
                warnings.add("المادة '${item.name}' تم إدخال كثافتها بصفر أو أقل؛ تم اعتبار الكثافة 1.0 افتراضياً.")
            }
            val solidsVal = item.solidsPercent.coerceIn(0.0, 100.0)
            val unitCostVal = item.unitCost.coerceAtLeast(0.0)
            if (item.unitCost < 0.0) {
                warnings.add("المادة '${item.name}' تحتوي على تكلفة سالبة تم تصحيحها إلى صفر.")
            }

            val w = bd(weightVal)
            val d = bd(densityVal)
            val s = bd(solidsVal)
            val uc = bd(unitCostVal)

            val dryW = w.multiply(s.divide(BD_100, MC), MC)
            val wetV = if (d.compareTo(BigDecimal.ZERO) > 0) w.divide(d, MC) else BigDecimal.ZERO
            val dryV = if (d.compareTo(BigDecimal.ZERO) > 0) dryW.divide(d, MC) else BigDecimal.ZERO
            val lineC = w.multiply(uc, MC)

            totalBatchWeight = totalBatchWeight.add(w, MC)
            totalSolidsWeight = totalSolidsWeight.add(dryW, MC)
            totalWetVolume = totalWetVolume.add(wetV, MC)
            totalDryVolume = totalDryVolume.add(dryV, MC)
            totalCost = totalCost.add(lineC, MC)

            val typeLower = item.type.trim().lowercase(Locale.ROOT)
            val isPigmentOrExtender = typeLower.contains("صبغة") ||
                typeLower.contains("ملون") ||
                typeLower.contains("pigment") ||
                typeLower.contains("مالئ") ||
                typeLower.contains("حبيبات") ||
                typeLower.contains("extender")

            val isBinder = typeLower.contains("راتنج") ||
                typeLower.contains("بوليمر") ||
                typeLower.contains("رابط") ||
                typeLower.contains("binder") ||
                typeLower.contains("resin") ||
                typeLower.contains("emulsion")

            if (isPigmentOrExtender) {
                pigmentVolume = pigmentVolume.add(dryV, MC)
            } else if (isBinder) {
                binderDryVolume = binderDryVolume.add(dryV, MC)
            }

            lineCalcs.add(RawLineCalc(item, w, d, s, uc, dryW, wetV, dryV, lineC))
        }

        val totalPAndBVol = pigmentVolume.add(binderDryVolume, MC)
        val calculatedPvc = if (totalPAndBVol > BigDecimal("0.0001")) {
            pigmentVolume.divide(totalPAndBVol, MC).multiply(BD_100, MC).toDouble()
        } else 0.0

        val solidsWeightPercent = if (totalBatchWeight > BigDecimal("0.0001")) {
            totalSolidsWeight.divide(totalBatchWeight, MC).multiply(BD_100, MC).toDouble()
        } else 0.0

        val solidsVolumePercent = if (totalWetVolume > BigDecimal("0.0001")) {
            totalDryVolume.divide(totalWetVolume, MC).multiply(BD_100, MC).toDouble()
        } else 0.0

        val calculatedDensity = if (totalWetVolume > BigDecimal("0.0001")) {
            totalBatchWeight.divide(totalWetVolume, MC).toDouble()
        } else 1.0

        val costPerKg = if (totalBatchWeight > BigDecimal("0.0001")) {
            totalCost.divide(totalBatchWeight, MC).toDouble()
        } else 0.0

        val breakdowns = lineCalcs.map { line ->
            val wPct = if (totalBatchWeight > BigDecimal("0.0001")) {
                line.weight.divide(totalBatchWeight, MC).multiply(BD_100, MC).toDouble()
            } else 0.0
            val cPct = if (totalCost > BigDecimal("0.0001")) {
                line.lineCost.divide(totalCost, MC).multiply(BD_100, MC).toDouble()
            } else 0.0
            IngredientCostBreakdown(
                ingredientId = line.item.id,
                materialId = line.item.materialId,
                itemCode = line.item.itemCode,
                name = line.item.name,
                type = line.item.type,
                unit = line.item.unit.ifBlank { "كجم" },
                weightKg = round3(line.weight.toDouble()),
                weightPercent = round3(wPct),
                density = round3(line.density.toDouble()),
                solidsPercent = round2(line.solidsPct.toDouble()),
                unitCost = round3(line.unitCost.toDouble()),
                lineCost = round2(line.lineCost.toDouble()),
                costContributionPercent = round2(cPct),
                wetVolumeLiters = round3(line.wetVol.toDouble()),
                dryWeightKg = round3(line.dryWeight.toDouble()),
                dryVolumeLiters = round3(line.dryVol.toDouble())
            )
        }

        val cpvcEstimated = 52.0
        val roundedPvc = round2(calculatedPvc)
        val (classification, analysis) = evaluatePaintProperties(roundedPvc, cpvcEstimated, solidsWeightPercent)

        return PvcCalculationResult(
            calculatedPvc = roundedPvc,
            cpvcEstimated = cpvcEstimated,
            totalSolidsWeight = round2(solidsWeightPercent),
            totalSolidsVolume = round2(solidsVolumePercent),
            totalBatchWeight = round2(totalBatchWeight.toDouble()),
            totalBatchVolume = round2(totalWetVolume.toDouble()),
            calculatedDensity = round3(calculatedDensity),
            totalCost = round2(totalCost.toDouble()),
            costPerKg = round3(costPerKg),
            pigmentVolume = round3(pigmentVolume.toDouble()),
            binderDryVolume = round3(binderDryVolume.toDouble()),
            paintClassification = classification,
            technicalAnalysisText = analysis,
            warnings = warnings,
            ingredientBreakdowns = breakdowns,
            totalPercentageCheck = round2(breakdowns.sumOf { it.weightPercent })
        )
    }

    /**
     * Scales a base formulation linearly to any target batch size (e.g., 100 kg, 500 kg, 1000 kg)
     * without rounding intermediate line ratios before scaling.
     */
    fun scaleFormulation(
        ingredients: List<FormulationIngredient>,
        targetBatchKg: Double
    ): ScaledFormulaResult {
        require(targetBatchKg > 0.0) { "حجم التشغيلة المستهدف يجب أن يكون أكبر من صفر." }
        val baseTotal = ingredients.fold(BigDecimal.ZERO) { acc, it ->
            acc.add(bd(it.weight.coerceAtLeast(0.0)), MC)
        }
        if (baseTotal <= BigDecimal("0.0001")) {
            return ScaledFormulaResult(
                targetBatchKg = round3(targetBatchKg),
                scaleFactor = 0.0,
                scaledIngredients = ingredients,
                scaledTotalCost = 0.0,
                scaledCostPerKg = 0.0
            )
        }
        val targetBd = bd(targetBatchKg)
        val factorBd = targetBd.divide(baseTotal, MC)
        var totalCostBd = BigDecimal.ZERO
        val scaled = ingredients.map { item ->
            val rawW = bd(item.weight.coerceAtLeast(0.0)).multiply(factorBd, MC)
            val uc = bd(item.unitCost.coerceAtLeast(0.0))
            totalCostBd = totalCostBd.add(rawW.multiply(uc, MC), MC)
            item.copy(weight = round3(rawW.toDouble()))
        }
        val scaledTotal = round2(totalCostBd.toDouble())
        val costPerKg = round3(totalCostBd.divide(targetBd, MC).toDouble())
        return ScaledFormulaResult(
            targetBatchKg = round3(targetBatchKg),
            scaleFactor = round4(factorBd.toDouble()),
            scaledIngredients = scaled,
            scaledTotalCost = scaledTotal,
            scaledCostPerKg = costPerKg
        )
    }

    /**
     * Reprices ingredients using current raw material registry prices (without mutating the
     * locked historical approved snapshot) and builds the 3-cost variance report:
     * 1) Historical Approved Cost (at approval time)
     * 2) Current Recalculated Cost (live raw material prices)
     * 3) Actual Production Cost (from completed production batch if available)
     */
    fun compareThreeCosts(
        formulation: FormulationEntity,
        ingredients: List<FormulationIngredient>,
        rawMaterials: List<FactoryItemEntity>,
        actualProductionTotalCost: Double? = null,
        actualProducedWeightKg: Double? = null
    ): ThreeCostComparisonReport {
        val byId = rawMaterials.associateBy { it.id }
        val byName = rawMaterials.associateBy { it.name.trim().lowercase(Locale.ROOT) }

        val historicalTotal = if (formulation.historicalTotalCost > 0.0) {
            formulation.historicalTotalCost
        } else {
            formulation.totalCost
        }
        val historicalPerKg = if (formulation.historicalCostPerKg > 0.0) {
            formulation.historicalCostPerKg
        } else {
            formulation.costPerKg
        }

        var currentTotalBd = BigDecimal.ZERO
        var totalWeightBd = BigDecimal.ZERO
        val impacts = mutableListOf<MaterialPriceImpactLine>()

        for (ing in ingredients) {
            val wBd = bd(ing.weight.coerceAtLeast(0.0))
            totalWeightBd = totalWeightBd.add(wBd, MC)
            val matched = ing.materialId?.let { byId[it] } ?: byName[ing.name.trim().lowercase(Locale.ROOT)]
            val marketPrice = when {
                matched != null && matched.lastPurchasePrice > 0.0 -> matched.lastPurchasePrice
                matched != null && matched.unitCostEgp > 0.0 -> matched.unitCostEgp
                else -> ing.unitCost
            }.coerceAtLeast(0.0)

            val formulaPrice = ing.unitCost.coerceAtLeast(0.0)
            currentTotalBd = currentTotalBd.add(wBd.multiply(bd(marketPrice), MC), MC)

            val deltaPerUnit = marketPrice - formulaPrice
            val deltaBatch = wBd.multiply(bd(deltaPerUnit), MC).toDouble()
            val deltaPct = if (formulaPrice > 0.0001) (deltaPerUnit / formulaPrice) * 100.0 else 0.0
            if (kotlin.math.abs(deltaPerUnit) > 0.001) {
                impacts.add(
                    MaterialPriceImpactLine(
                        materialName = ing.name,
                        formulaUnitCost = round3(formulaPrice),
                        currentMarketUnitCost = round3(marketPrice),
                        weightKg = round3(ing.weight),
                        deltaPerKgMaterial = round3(deltaPerUnit),
                        deltaTotalBatchCost = round2(deltaBatch),
                        deltaPercent = round2(deltaPct)
                    )
                )
            }
        }

        val currentTotal = round2(currentTotalBd.toDouble())
        val currentPerKg = if (totalWeightBd > BigDecimal("0.0001")) {
            round3(currentTotalBd.divide(totalWeightBd, MC).toDouble())
        } else 0.0

        val curVsHistTotal = round2(currentTotal - historicalTotal)
        val curVsHistPct = if (historicalTotal > 0.0001) round2((curVsHistTotal / historicalTotal) * 100.0) else 0.0

        val actTotal = actualProductionTotalCost?.let { round2(it) }
        val actPerKg = if (actTotal != null && (actualProducedWeightKg ?: 0.0) > 0.0001) {
            round3(actTotal / actualProducedWeightKg!!)
        } else null

        val actVsHistTotal = actTotal?.let { round2(it - historicalTotal) }
        val actVsHistPct = if (actVsHistTotal != null && historicalTotal > 0.0001) {
            round2((actVsHistTotal / historicalTotal) * 100.0)
        } else null
        val actVsCurTotal = actTotal?.let { round2(it - currentTotal) }

        return ThreeCostComparisonReport(
            formulaId = formulation.id,
            formulaNo = formulation.formulaNo,
            versionNumber = formulation.versionNumber,
            approvalStatus = formulation.approvalStatus,
            historicalApprovedTotalCost = round2(historicalTotal),
            historicalApprovedCostPerKg = round3(historicalPerKg),
            currentEstimatedTotalCost = currentTotal,
            currentEstimatedCostPerKg = currentPerKg,
            actualProductionTotalCost = actTotal,
            actualProductionCostPerKg = actPerKg,
            currentVsHistoricalDeltaTotal = curVsHistTotal,
            currentVsHistoricalDeltaPct = curVsHistPct,
            actualVsHistoricalDeltaTotal = actVsHistTotal,
            actualVsHistoricalDeltaPct = actVsHistPct,
            actualVsCurrentDeltaTotal = actVsCurTotal,
            priceImpacts = impacts.sortedByDescending { kotlin.math.abs(it.deltaTotalBatchCost) }
        )
    }

    /**
     * Generates a safe non-secret change diff summary between two ingredient lists.
     * NEVER prints exact confidential ingredient weights or secret ratios—only counts & changed material flags
     * suitable for version audit summaries when read broadly, while Lab-privileged callers inspect full diff locally.
     */
    fun summarizeIngredientChanges(
        previous: List<FormulationIngredient>,
        next: List<FormulationIngredient>,
        includeSensitiveNumbers: Boolean = false
    ): String {
        val prevMap = previous.associateBy { it.name.trim().lowercase(Locale.ROOT) }
        val nextMap = next.associateBy { it.name.trim().lowercase(Locale.ROOT) }
        val added = (nextMap.keys - prevMap.keys).size
        val removed = (prevMap.keys - nextMap.keys).size
        var modified = 0
        for (key in prevMap.keys.intersect(nextMap.keys)) {
            val a = prevMap[key]!!
            val b = nextMap[key]!!
            if (kotlin.math.abs(a.weight - b.weight) > 0.0001 || kotlin.math.abs(a.unitCost - b.unitCost) > 0.0001) {
                modified++
            }
        }
        val base = "إضافة $added مكون، تعديل $modified، حذف $removed"
        if (!includeSensitiveNumbers) return base
        val prevTotalW = round2(previous.sumOf { it.weight })
        val nextTotalW = round2(next.sumOf { it.weight })
        return "$base | الوزن الإجمالي: $prevTotalW -> $nextTotalW كجم"
    }

    private fun evaluatePaintProperties(pvc: Double, cpvc: Double, solids: Double): Pair<String, String> {
        return when {
            pvc < 1.0 -> {
                "ورنيش شفاف خالي من الصبغات (Clear Varnish)" to
                    "الخلطة لا تحتوي على أصباغ أو ملوئات كافية. ستعطي فيلماً شفافاً عالي اللمعان والحماية، لكن بدون تغطية لونية."
            }
            pvc < 25.0 -> {
                "دهان مائي لامع عالي الجودة (High Gloss Paint)" to
                    "نسبة الـ PVC منخفضة ($pvc%) تضمن تغليفاً كاملاً للأصباغ بواسطة الراتنج، مما يمنح فيلماً مستمراً عالي اللمعان ومقاومة ممتازة للماء، وسهولة تامة في الغسيل ومسح الأوساخ (مواد صلبة ${round2(solids)}%)."
            }
            pvc < 38.0 -> {
                "دهان نصف لامع / حريري (Semi-Gloss / Satin)" to
                    "توازن مثالي بين اللمعان والصلابة وقوة التغطية (مواد صلبة ${round2(solids)}%). مناسب جداً للمطابخ والممرات والمناطق المعرضة للرطوبة والاحتكاك."
            }
            pvc <= cpvc -> {
                "دهان معماري مطفي (Architectural Matt Paint)" to
                    "نسبة الـ PVC ($pvc%) قريبة من الحد الحرج ($cpvc%). تغطية عالية ممتازة لإخفاء عيوب الجدران، مع مظهر مخملي ناعم غير عاكس، واحتفاظ كافٍ بقوة التماسك ومقاومة الفرك."
            }
            else -> {
                "دهان فوق الحرج (Above CPVC / Primer & Undercoat)" to
                    "تجاوزت التركيبة تركيز الحجم الحرج للصبغة (PVC $pvc% > CPVC $cpvc%). الغشاء الناتج سيكون مسامياً وذا لمعان منعدم، مع امتصاص عالي وقوة تغطية جافة فورية. مثالي كطبقة أساس أولى (Primer) أو دهان أسقف اقتصادي."
            }
        }
    }

    fun ingredientsToJson(list: List<FormulationIngredient>): String {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            if (item.materialId != null) obj.put("materialId", item.materialId)
            obj.put("itemCode", item.itemCode)
            obj.put("name", item.name)
            obj.put("type", item.type)
            obj.put("unit", item.unit)
            obj.put("weight", item.weight)
            obj.put("density", item.density)
            obj.put("solidsPercent", item.solidsPercent)
            obj.put("unitCost", item.unitCost)
            obj.put("supplierName", item.supplierName)
            obj.put("notes", item.notes)
            array.put(obj)
        }
        return array.toString()
    }

    fun jsonToIngredients(json: String?): List<FormulationIngredient> {
        if (json.isNullOrBlank()) return emptyList()
        val list = mutableListOf<FormulationIngredient>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val matId = if (obj.has("materialId") && !obj.isNull("materialId")) {
                    obj.optLong("materialId", 0L).takeIf { it > 0L }
                } else null
                list.add(
                    FormulationIngredient(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        materialId = matId,
                        itemCode = obj.optString("itemCode", ""),
                        name = obj.optString("name", "مادة خام"),
                        type = obj.optString("type", "صبغة / ملون"),
                        unit = obj.optString("unit", "كجم"),
                        weight = obj.optDouble("weight", 0.0),
                        density = obj.optDouble("density", 1.0),
                        solidsPercent = obj.optDouble("solidsPercent", 100.0),
                        unitCost = obj.optDouble("unitCost", 0.0),
                        supplierName = obj.optString("supplierName", ""),
                        notes = obj.optString("notes", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    private fun round4(v: Double): Double = Math.round(v * 10000.0) / 10000.0
}

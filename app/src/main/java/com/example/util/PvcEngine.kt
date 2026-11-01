package com.example.util

import com.example.data.local.FormulationIngredient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * PMP Science & Chemistry Lab — Paint Engineering Calculation Engine
 * المحرك الهندسي لحسابات تركيبات الدهانات والطلاءات المعمارية والصناعية
 */
object PvcEngine {

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
        val warnings: List<String>
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

    /**
     * Calculates the full PVC engineering specifications for a formulation.
     */
    fun calculate(ingredients: List<FormulationIngredient>): PvcCalculationResult {
        val warnings = mutableListOf<String>()

        var totalBatchWeight = 0.0
        var totalSolidsWeight = 0.0
        var totalWetVolume = 0.0
        var totalDryVolume = 0.0
        var pigmentVolume = 0.0
        var binderDryVolume = 0.0
        var totalCost = 0.0

        for (item in ingredients) {
            val weight = item.weight.coerceAtLeast(0.0)
            val density = if (item.density > 0.01) item.density else 1.0
            val solidsPct = item.solidsPercent.coerceIn(0.0, 100.0)

            if (item.density <= 0.01 && weight > 0) {
                warnings.add("المادة '${item.name}' تم إدخال كثافتها بصفر أو فارغة؛ تم اعتبار الكثافة 1.0 افتراضياً.")
            }

            totalBatchWeight += weight
            val dryWeight = weight * (solidsPct / 100.0)
            totalSolidsWeight += dryWeight

            val wetVol = if (density > 0) weight / density else 0.0
            totalWetVolume += wetVol

            val dryVol = if (density > 0) dryWeight / density else 0.0
            totalDryVolume += dryVol

            totalCost += (weight * item.unitCost)

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
                pigmentVolume += dryVol
            } else if (isBinder) {
                binderDryVolume += dryVol
            }
        }

        val totalPigmentAndBinderVol = pigmentVolume + binderDryVolume
        val calculatedPvc = if (totalPigmentAndBinderVol > 0.0001) {
            (pigmentVolume / totalPigmentAndBinderVol) * 100.0
        } else {
            0.0
        }

        val solidsWeightPercent = if (totalBatchWeight > 0.0001) {
            (totalSolidsWeight / totalBatchWeight) * 100.0
        } else {
            0.0
        }

        val solidsVolumePercent = if (totalWetVolume > 0.0001) {
            (totalDryVolume / totalWetVolume) * 100.0
        } else {
            0.0
        }

        val calculatedDensity = if (totalWetVolume > 0.0001) {
            totalBatchWeight / totalWetVolume
        } else {
            1.0
        }

        val costPerKg = if (totalBatchWeight > 0.0001) {
            totalCost / totalBatchWeight
        } else {
            0.0
        }

        // Estimated CPVC based on average pigment/extender packing
        val cpvcEstimated = 52.0

        val (classification, analysis) = evaluatePaintProperties(calculatedPvc, cpvcEstimated, solidsWeightPercent)

        return PvcCalculationResult(
            calculatedPvc = round2(calculatedPvc),
            cpvcEstimated = cpvcEstimated,
            totalSolidsWeight = round2(solidsWeightPercent),
            totalSolidsVolume = round2(solidsVolumePercent),
            totalBatchWeight = round2(totalBatchWeight),
            totalBatchVolume = round2(totalWetVolume),
            calculatedDensity = round3(calculatedDensity),
            totalCost = round2(totalCost),
            costPerKg = round3(costPerKg),
            pigmentVolume = round3(pigmentVolume),
            binderDryVolume = round3(binderDryVolume),
            paintClassification = classification,
            technicalAnalysisText = analysis,
            warnings = warnings
        )
    }

    private fun evaluatePaintProperties(pvc: Double, cpvc: Double, solids: Double): Pair<String, String> {
        return when {
            pvc < 1.0 -> {
                "ورنيش شفاف خالي من الصبغات (Clear Varnish)" to
                    "الخلطة لا تحتوي على أصباغ أو ملوئات كافية. ستعطي فيلماً شفافاً عالي اللمعان والحماية، لكن بدون تغطية لونية."
            }
            pvc < 25.0 -> {
                "دهان مائي لامع عالي الجودة (High Gloss Paint)" to
                    "نسبة الـ PVC منخفضة ($pvc%) تضمن تغليفاً كاملاً للأصباغ بواسطة الراتنج، مما يمنح فيلماً مستمراً عالي اللمعان ومقاومة ممتازة للماء، وسهولة تامة في الغسيل ومسح الأوساخ."
            }
            pvc < 38.0 -> {
                "دهان نصف لامع / حريري (Semi-Gloss / Satin)" to
                    "توازن مثالي بين اللمعان والصلابة وقوة التغطية. مناسب جداً للمطابخ والممرات والمناطق المعرضة للرطوبة والاحتكاك."
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
            obj.put("name", item.name)
            obj.put("type", item.type)
            obj.put("weight", item.weight)
            obj.put("density", item.density)
            obj.put("solidsPercent", item.solidsPercent)
            obj.put("unitCost", item.unitCost)
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
                list.add(
                    FormulationIngredient(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        name = obj.optString("name", "مادة خام"),
                        type = obj.optString("type", "صبغة / ملون"),
                        weight = obj.optDouble("weight", 0.0),
                        density = obj.optDouble("density", 1.0),
                        solidsPercent = obj.optDouble("solidsPercent", 100.0),
                        unitCost = obj.optDouble("unitCost", 0.0),
                        notes = obj.optString("notes", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
}

package com.example.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * نموذج بيانات الوزن المسجل في منظومة المخزون
 */
data class PackageWeightSpec(
    val value: String,          // القيمة القياسية الموحدة بالجرام (مثال: "250", "500", "1000")
    val unit: String = "جم",    // وحدة القياس الموحدة
    val active: Boolean = true, // نشط يظهر في قائمة اختيار الوزن أو معطل
    val isDefault: Boolean = false
) {
    val displayLabel: String
        get() = InventoryWeightManager.formatWeightLabel(value, unit)

    fun displayLabel(): String = displayLabel
}

/**
 * مدير الأوزان الموحد لتطبيق PMP PAINTS
 * يدير:
 * 1. إضافة وزن جديد مع توحيد وحدات القياس ومنع التكرار.
 * 2. تعديل وزن مسجل مع الحفاظ الكامل على الأرصدة والحركات السابقة.
 * 3. تعطيل وزن غير مستخدم وإعادة تفعيله.
 * 4. اختيار الوزن الافتراضي لعرض جدول المخزون.
 * 5. المزامنة مع Firebase Firestore عبر الكلاستر السحابي.
 */
class InventoryWeightManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _weights = MutableStateFlow<List<PackageWeightSpec>>(emptyList())
    val weights: StateFlow<List<PackageWeightSpec>> = _weights.asStateFlow()

    private val _selectedWeight = MutableStateFlow(DEFAULT_INITIAL_WEIGHT)
    val selectedWeight: StateFlow<String> = _selectedWeight.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val rawJson = prefs.getString(KEY_WEIGHTS_JSON, null)
        val parsed = if (!rawJson.isNullOrBlank()) {
            parseWeightsJson(rawJson)
        } else {
            emptyList()
        }
        val initialList = ensureValidWeightsList(parsed.ifEmpty { buildDefaultWeightSpecs() })
        _weights.value = initialList
        val defaultSpec = initialList.firstOrNull { it.isDefault && it.active }
            ?: initialList.firstOrNull { it.active }
            ?: initialList.first()
        _selectedWeight.value = defaultSpec.value
    }

    private fun saveToPrefs(list: List<PackageWeightSpec>) {
        val normalized = ensureValidWeightsList(list)
        _weights.value = normalized
        prefs.edit().putString(KEY_WEIGHTS_JSON, serializeWeightsToJson(normalized)).apply()
        val currentSelected = _selectedWeight.value
        if (normalized.none { it.active && it.value == currentSelected }) {
            val fallback = normalized.firstOrNull { it.isDefault && it.active }
                ?: normalized.firstOrNull { it.active }
                ?: normalized.first()
            _selectedWeight.value = fallback.value
        }
    }

    fun selectWeight(weightValue: String) {
        val clean = normalizeWeightValue(weightValue)
        if (clean.isBlank()) return
        _selectedWeight.value = clean
    }

    /**
     * دمج الأوزان المكتشفة في قاعدة البيانات (الأصناف أو الأسعار الحالية) دون حذف أو تكرار
     */
    fun syncDiscoveredProductWeights(discoveredRawWeights: Collection<String>) {
        val current = _weights.value.toMutableList()
        var changed = false
        for (raw in discoveredRawWeights) {
            val clean = normalizeWeightValue(raw)
            if (clean.isNotBlank() && current.none { it.value.equals(clean, ignoreCase = true) }) {
                current.add(
                    PackageWeightSpec(
                        value = clean,
                        unit = "جم",
                        active = true,
                        isDefault = false
                    )
                )
                changed = true
            }
        }
        if (changed) {
            saveToPrefs(sortWeightSpecs(current))
        }
    }

    /**
     * 1. إضافة وزن جديد مع منع التكرار وتوحيد وحدة القياس
     */
    fun addWeight(rawInput: String, setAsDefault: Boolean = false): Result<PackageWeightSpec> {
        val cleanValue = normalizeWeightValue(rawInput)
        if (cleanValue.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال قيمة وزن صحيحة (مثال: 400 جم)."))
        }
        val numVal = cleanValue.toDoubleOrNull()
        if (numVal != null && numVal <= 0.0) {
            return Result.failure(IllegalArgumentException("يجب أن تكون قيمة الوزن أكبر من صفر."))
        }

        val current = _weights.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.value.equals(cleanValue, ignoreCase = true) }
        if (existingIdx >= 0) {
            val existing = current[existingIdx]
            return if (!existing.active) {
                // إذا كان الوزن موجوداً مسبقاً ولكنه معطل، نعيد تفعيله تلقائياً
                val reactivated = existing.copy(active = true, isDefault = setAsDefault || existing.isDefault)
                val updatedList = current.mapIndexed { index, spec ->
                    when {
                        index == existingIdx -> reactivated
                        setAsDefault -> spec.copy(isDefault = false)
                        else -> spec
                    }
                }
                saveToPrefs(sortWeightSpecs(updatedList))
                _selectedWeight.value = reactivated.value
                Result.success(reactivated)
            } else {
                Result.failure(IllegalArgumentException("الوزن (${existing.displayLabel}) مسجل بالفعل في النظام."))
            }
        }

        val newSpec = PackageWeightSpec(
            value = cleanValue,
            unit = "جم",
            active = true,
            isDefault = setAsDefault
        )
        val nextList = if (setAsDefault) {
            current.map { it.copy(isDefault = false) } + newSpec
        } else {
            current + newSpec
        }
        saveToPrefs(sortWeightSpecs(nextList))
        _selectedWeight.value = newSpec.value
        return Result.success(newSpec)
    }

    /**
     * 2. تعديل وزن مسجل مع منع التكرار والحفاظ على الارتباط
     */
    fun updateWeightValue(oldWeightValue: String, newRawInput: String): Result<PackageWeightSpec> {
        val cleanOld = normalizeWeightValue(oldWeightValue)
        val cleanNew = normalizeWeightValue(newRawInput)
        if (cleanNew.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال قيمة الوزن الجديدة بصورة صحيحة."))
        }
        val numVal = cleanNew.toDoubleOrNull()
        if (numVal != null && numVal <= 0.0) {
            return Result.failure(IllegalArgumentException("يجب أن تكون قيمة الوزن أكبر من صفر."))
        }

        val current = _weights.value.toMutableList()
        val targetIdx = current.indexOfFirst { it.value.equals(cleanOld, ignoreCase = true) }
        if (targetIdx < 0) {
            return Result.failure(IllegalArgumentException("الوزن المراد تعديله غير موجود."))
        }

        if (!cleanOld.equals(cleanNew, ignoreCase = true) &&
            current.any { it.value.equals(cleanNew, ignoreCase = true) }
        ) {
            return Result.failure(IllegalArgumentException("الوزن (${formatWeightLabel(cleanNew)}) مسجل بالفعل."))
        }

        val updatedSpec = current[targetIdx].copy(value = cleanNew)
        current[targetIdx] = updatedSpec
        saveToPrefs(sortWeightSpecs(current))
        if (_selectedWeight.value.equals(cleanOld, ignoreCase = true)) {
            _selectedWeight.value = cleanNew
        }
        return Result.success(updatedSpec)
    }

    /**
     * 3 & 4. تعطيل وزن غير مستخدم أو إعادة تفعيل وزن معطل
     */
    fun setWeightActive(weightValue: String, active: Boolean): Result<PackageWeightSpec> {
        val clean = normalizeWeightValue(weightValue)
        val current = _weights.value.toMutableList()
        val idx = current.indexOfFirst { it.value.equals(clean, ignoreCase = true) }
        if (idx < 0) {
            return Result.failure(IllegalArgumentException("الوزن غير موجود."))
        }

        if (!active && current.count { it.active } <= 1 && current[idx].active) {
            return Result.failure(IllegalStateException("لا يمكن تعطيل جميع الأوزان؛ يجب بقاء وزن واحد نشط على الأقل."))
        }

        val updated = current[idx].copy(
            active = active,
            isDefault = if (!active) false else current[idx].isDefault
        )
        current[idx] = updated
        saveToPrefs(sortWeightSpecs(current))
        return Result.success(updated)
    }

    /**
     * 5. اختيار الوزن الافتراضي
     */
    fun setDefaultWeight(weightValue: String): Result<PackageWeightSpec> {
        val clean = normalizeWeightValue(weightValue)
        val current = _weights.value
        if (current.none { it.value.equals(clean, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("الوزن غير مسجل في النظام."))
        }
        val updatedList = current.map { spec ->
            if (spec.value.equals(clean, ignoreCase = true)) {
                spec.copy(active = true, isDefault = true)
            } else {
                spec.copy(isDefault = false)
            }
        }
        saveToPrefs(sortWeightSpecs(updatedList))
        _selectedWeight.value = clean
        val target = _weights.value.first { it.value.equals(clean, ignoreCase = true) }
        return Result.success(target)
    }

    /**
     * مزامنة الأوزان القادمة من Firebase Firestore دون فقدان الأوزان المحلية
     */
    fun mergeCloudWeights(cloudSpecs: List<PackageWeightSpec>, cloudDefaultWeight: String?) {
        if (cloudSpecs.isEmpty()) return
        val localMap = _weights.value.associateBy { it.value }.toMutableMap()
        for (remote in cloudSpecs) {
            val cleanVal = normalizeWeightValue(remote.value)
            if (cleanVal.isNotBlank()) {
                localMap[cleanVal] = remote.copy(value = cleanVal)
            }
        }
        val resolvedDefault = cloudDefaultWeight?.let { normalizeWeightValue(it) }?.takeIf { it.isNotBlank() }
        val mergedList = localMap.values.map { spec ->
            if (resolvedDefault != null) {
                spec.copy(isDefault = spec.value.equals(resolvedDefault, ignoreCase = true))
            } else {
                spec
            }
        }
        saveToPrefs(sortWeightSpecs(mergedList))
    }

    fun getDefaultWeight(): String {
        return _weights.value.firstOrNull { it.isDefault }?.value ?: DEFAULT_INITIAL_WEIGHT
    }

    fun mergeCloudWeightsFromFirestoreDoc(
        weightsJson: String?,
        rawWeightsList: List<Map<String, Any?>>?,
        cloudDefaultWeight: String?
    ) {
        val parsedFromJson = if (!weightsJson.isNullOrBlank()) {
            parseWeightsJson(weightsJson)
        } else {
            emptyList()
        }
        val parsedFromMaps = rawWeightsList?.mapNotNull { map ->
            val v = normalizeWeightValue(map["value"]?.toString())
            if (v.isBlank()) null else PackageWeightSpec(
                value = v,
                unit = (map["unit"]?.toString() ?: "جم").ifBlank { "جم" },
                active = (map["active"] as? Boolean) ?: true,
                isDefault = (map["isDefault"] as? Boolean) ?: false
            )
        }.orEmpty()
        val combined = (parsedFromJson + parsedFromMaps).distinctBy { it.value }
        if (combined.isNotEmpty()) {
            mergeCloudWeights(combined, cloudDefaultWeight)
        }
    }

    fun exportForCloudSync(): Map<String, Any> {
        val list = _weights.value
        val defaultVal = list.firstOrNull { it.isDefault }?.value ?: DEFAULT_INITIAL_WEIGHT
        val mapList = list.map { spec ->
            mapOf(
                "value" to spec.value,
                "unit" to spec.unit,
                "active" to spec.active,
                "isDefault" to spec.isDefault
            )
        }
        return mapOf(
            "defaultWeight" to defaultVal,
            "weights" to mapList,
            "weightsJson" to serializeWeightsToJson(list),
            "updatedAt" to System.currentTimeMillis()
        )
    }

    companion object {
        private const val PREFS_NAME = "pmp_inventory_weights_prefs"
        private const val KEY_WEIGHTS_JSON = "registered_package_weights_json"
        const val DEFAULT_INITIAL_WEIGHT = "250"

        @Volatile
        private var instance: InventoryWeightManager? = null

        fun getInstance(context: Context): InventoryWeightManager {
            return instance ?: synchronized(this) {
                instance ?: InventoryWeightManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * الأوزان القياسية المسجلة افتراضياً في النظام مع إبقاء 250 جم كوزن افتراضي
         */
        fun buildDefaultWeightSpecs(): List<PackageWeightSpec> {
            return listOf(
                PackageWeightSpec(value = "100", unit = "جم", active = true, isDefault = false),
                PackageWeightSpec(value = "250", unit = "جم", active = true, isDefault = true),
                PackageWeightSpec(value = "500", unit = "جم", active = true, isDefault = false),
                PackageWeightSpec(value = "750", unit = "جم", active = true, isDefault = false),
                PackageWeightSpec(value = "900", unit = "جم", active = true, isDefault = false),
                PackageWeightSpec(value = "1000", unit = "جم", active = true, isDefault = false),
                PackageWeightSpec(value = "2000", unit = "جم", active = true, isDefault = false),
                PackageWeightSpec(value = "4000", unit = "جم", active = true, isDefault = false)
            )
        }

        /**
         * توحيد قيمة الوزن وإزالة اللواحق وتحويل الأرقام العربية والكيلوجرام إلى الجرام الموحد
         */
        fun normalizeWeightValue(rawInput: String?): String {
            if (rawInput == null) return ""
            var text = rawInput.trim()
            if (text.isEmpty()) return ""

            // تحويل الأرقام العربية الشرقية والفارسية إلى أرقام قياسية
            val normalizedChars = StringBuilder(text.length)
            for (ch in text) {
                when (ch) {
                    in '٠'..'٩' -> normalizedChars.append((ch - '٠'))
                    in '۰'..'۹' -> normalizedChars.append((ch - '۰'))
                    '٫', ',' -> normalizedChars.append('.')
                    else -> normalizedChars.append(ch)
                }
            }
            text = normalizedChars.toString().trim()

            val lower = text.lowercase(Locale.ROOT)
            val isKilogram = lower.contains("كجم") ||
                lower.contains("كيلو") ||
                lower.contains("kg") ||
                lower.contains("kilo")

            val numberRegex = Regex("""([0-9]+(?:\.[0-9]+)?)""")
            val match = numberRegex.find(lower)
            if (match != null) {
                val number = match.groupValues[1].toDoubleOrNull()
                if (number != null && number > 0.0) {
                    val grams = if (isKilogram) number * 1000.0 else number
                    val roundedLong = Math.round(grams)
                    return if (Math.abs(grams - roundedLong) < 0.001) {
                        roundedLong.toString()
                    } else {
                        grams.toString()
                    }
                }
            }

            // للأوزان الوصفية مثل "جالون" أو "ربع" إن وجدت في بيانات سابقة
            return text
                .replace("جرام", "")
                .replace("غرام", "")
                .replace("جم", "")
                .replace("غ", "")
                .replace(Regex("""(?i)\b(gm|gram|grams|g)\b"""), "")
                .trim()
        }

        /**
         * تنسيق عرض الوزن للمستخدم (مثال: "250" -> "250 جم")
         */
        fun formatWeightLabel(rawWeight: String, unit: String = "جم"): String {
            val clean = normalizeWeightValue(rawWeight)
            if (clean.isBlank()) return ""
            val isNumeric = clean.all { it.isDigit() || it == '.' }
            return if (isNumeric) {
                "$clean $unit"
            } else if (clean.contains(unit)) {
                clean
            } else {
                "$clean $unit"
            }
        }

        internal fun sortWeightSpecs(list: List<PackageWeightSpec>): List<PackageWeightSpec> {
            return list
                .distinctBy { it.value.lowercase(Locale.ROOT) }
                .sortedWith(
                    compareBy<PackageWeightSpec> { it.value.toDoubleOrNull() ?: Double.MAX_VALUE }
                        .thenBy { it.value }
                )
        }

        internal fun ensureValidWeightsList(list: List<PackageWeightSpec>): List<PackageWeightSpec> {
            val base = if (list.isEmpty()) buildDefaultWeightSpecs() else sortWeightSpecs(list)
            val hasActive = base.any { it.active }
            val withActive = if (!hasActive) {
                base.mapIndexed { index, spec -> if (index == 0) spec.copy(active = true) else spec }
            } else {
                base
            }
            val hasDefaultActive = withActive.any { it.isDefault && it.active }
            return if (!hasDefaultActive) {
                val preferredIdx = withActive.indexOfFirst { it.active && it.value == DEFAULT_INITIAL_WEIGHT }
                    .takeIf { it >= 0 }
                    ?: withActive.indexOfFirst { it.active }.coerceAtLeast(0)
                withActive.mapIndexed { index, spec ->
                    spec.copy(isDefault = (index == preferredIdx))
                }
            } else {
                var defaultAssigned = false
                withActive.map { spec ->
                    if (spec.isDefault && spec.active && !defaultAssigned) {
                        defaultAssigned = true
                        spec
                    } else {
                        spec.copy(isDefault = false)
                    }
                }
            }
        }

        internal fun serializeWeightsToJson(list: List<PackageWeightSpec>): String {
            val arr = JSONArray()
            for (spec in list) {
                val obj = JSONObject().apply {
                    put("value", spec.value)
                    put("unit", spec.unit)
                    put("active", spec.active)
                    put("isDefault", spec.isDefault)
                }
                arr.put(obj)
            }
            return arr.toString()
        }

        internal fun parseWeightsJson(rawJson: String): List<PackageWeightSpec> {
            return runCatching {
                val arr = JSONArray(rawJson)
                val result = mutableListOf<PackageWeightSpec>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val value = normalizeWeightValue(obj.optString("value", ""))
                    if (value.isNotBlank()) {
                        result.add(
                            PackageWeightSpec(
                                value = value,
                                unit = obj.optString("unit", "جم").ifBlank { "جم" },
                                active = obj.optBoolean("active", true),
                                isDefault = obj.optBoolean("isDefault", false)
                            )
                        )
                    }
                }
                ensureValidWeightsList(result)
            }.getOrDefault(emptyList())
        }
    }
}

package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Live PubChem REST API integration for PMP Science & Chemistry Lab.
 * Connects directly to NCBI PubChem PUG REST API.
 */
object PubChemService {

    data class PubChemCompound(
        val cid: Long,
        val name: String,
        val iupacName: String,
        val molecularFormula: String,
        val molecularWeight: Double,
        val casNumber: String,
        val canonicalSmiles: String,
        val estimatedDensity: Double,
        val description: String,
        val pubChemUrl: String
    )

    // Standard paint chemical offline catalog for instant reference and offline resiliency
    private val OFFLINE_PAINT_CHEMICALS = listOf(
        PubChemCompound(
            cid = 26042,
            name = "Titanium Dioxide (ثاني أكسيد التيتانيوم)",
            iupacName = "dioxotitanium",
            molecularFormula = "TiO2",
            molecularWeight = 79.87,
            casNumber = "13463-67-7",
            canonicalSmiles = "O=[Ti]=O",
            estimatedDensity = 4.23,
            description = "أهم صبغة بيضاء في صناعة الدهانات، تمنح بياضاً ناصعاً وتغطية فائقة للضوء ومعامل انكسار عالي (2.71).",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/26042"
        ),
        PubChemCompound(
            cid = 10112,
            name = "Calcium Carbonate (كربونات الكالسيوم)",
            iupacName = "calcium carbonic acid",
            molecularFormula = "CaCO3",
            molecularWeight = 100.09,
            casNumber = "471-34-1",
            canonicalSmiles = "[Ca+2].[O-]C(=O)[O-]",
            estimatedDensity = 2.71,
            description = "مادة مالئة معدنية بيضاء واسعة الانتشار، تستخدم لضبط اللمعان، تقليل التكلفة، ودعم قوام الطلاء.",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/10112"
        ),
        PubChemCompound(
            cid = 6581,
            name = "Acrylic Acid (حمض الأكريليك)",
            iupacName = "prop-2-enoic acid",
            molecularFormula = "C3H4O2",
            molecularWeight = 72.06,
            casNumber = "79-10-7",
            canonicalSmiles = "C=CC(=O)O",
            estimatedDensity = 1.05,
            description = "المونومر الأساسي لتصنيع مستحلبات وبوليمرات راتنجات الدهانات الأكريليكية النقية والستايرين أكريليك.",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/6581"
        ),
        PubChemCompound(
            cid = 1030,
            name = "Propylene Glycol (بروبيلين جليكول)",
            iupacName = "propane-1,2-diol",
            molecularFormula = "C3H8O2",
            molecularWeight = 76.09,
            casNumber = "57-55-6",
            canonicalSmiles = "CC(CO)O",
            estimatedDensity = 1.04,
            description = "مذيب ومرطب يحسن زمن التشغيل (Open Time) ومقاومة التجمد والذوبان (Freeze-Thaw Resistance).",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/1030"
        ),
        PubChemCompound(
            cid = 31393,
            name = "Texanol / 2,2,4-Trimethyl-1,3-pentanediol monoisobutyrate",
            iupacName = "3-hydroxy-2,2,4-trimethylpentyl 2-methylpropanoate",
            molecularFormula = "C12H24O3",
            molecularWeight = 216.32,
            casNumber = "25265-77-4",
            canonicalSmiles = "CC(C)C(=O)OCC(C)(C)C(O)C(C)C",
            estimatedDensity = 0.95,
            description = "أشهر مساعد اندماج وتشكيل فيلم (Coalescent) لخفض درجة حرارة تشكل الغشاء الصغرى (MFFT).",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/31393"
        ),
        PubChemCompound(
            cid = 222,
            name = "Ammonia (أمونيا / هيدروكسيد أمونيوم)",
            iupacName = "azane",
            molecularFormula = "H3N",
            molecularWeight = 17.03,
            casNumber = "7664-41-7",
            canonicalSmiles = "N",
            estimatedDensity = 0.91,
            description = "يستخدم لضبط درجة الحموضة (pH 8.5 - 9.2) لتثبيت الراتنج وتفعيل مغلظات الأكريليك المنتفخة بالقلوية.",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/222"
        ),
        PubChemCompound(
            cid = 24414,
            name = "Barium Sulfate (كبريتات الباريوم / بارايت)",
            iupacName = "barium sulfate",
            molecularFormula = "BaSO4",
            molecularWeight = 233.39,
            casNumber = "7727-43-7",
            canonicalSmiles = "[Ba+2].[O-]S(=O)(=O)[O-]",
            estimatedDensity = 4.49,
            description = "مالئ ثقيل وخامل كيميائياً، يمنح مقاومة عالية للأحماض والقلويات ومقاومة ممتازة للاحتكاك في الدهانات الصناعية.",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/24414"
        ),
        PubChemCompound(
            cid = 14812,
            name = "Zinc Oxide (أكسيد الزنك)",
            iupacName = "oxozinc",
            molecularFormula = "ZnO",
            molecularWeight = 81.38,
            casNumber = "1314-13-2",
            canonicalSmiles = "O=[Zn]",
            estimatedDensity = 5.61,
            description = "صبغة بيضاء وظيفية تمنح مقاومة للعفن والفطريات وحماية عالية من الأشعة فوق البنفسجية (UV Blocker).",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/14812"
        ),
        PubChemCompound(
            cid = 516883,
            name = "Iron Oxide Red (أكسيد الحديد الأحمر)",
            iupacName = "diiron;oxygen(2-)",
            molecularFormula = "Fe2O3",
            molecularWeight = 159.69,
            casNumber = "1309-37-1",
            canonicalSmiles = "[O-2].[O-2].[O-2].[Fe+3].[Fe+3]",
            estimatedDensity = 5.24,
            description = "صبغة تلوين حمراء غير عضوية فائقة الثبات الضوئي والمقاومة للعوامل الجوية والحرارة.",
            pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/516883"
        )
    )

    /**
     * Searches NCBI PubChem by name or CAS number with offline cache fallback.
     */
    suspend fun searchCompound(query: String): Result<List<PubChemCompound>> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return@withContext Result.success(OFFLINE_PAINT_CHEMICALS)
        }

        // Check local matching first
        val localMatches = OFFLINE_PAINT_CHEMICALS.filter {
            it.name.contains(cleanQuery, ignoreCase = true) ||
                it.casNumber.contains(cleanQuery, ignoreCase = true) ||
                it.molecularFormula.equals(cleanQuery, ignoreCase = true) ||
                it.iupacName.contains(cleanQuery, ignoreCase = true)
        }

        // Try live HTTP lookup to PubChem NCBI
        runCatching {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = URL("https://pubchem.ncbi.nlm.nih.gov/rest/pug/compound/name/$encoded/JSON")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PMP-Paints-Android-Lab/1.0")
            }

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val root = JSONObject(sb.toString())
                val pcCompounds = root.optJSONObject("PC_Compounds")
                    ?: root.optJSONArray("PC_Compounds")?.optJSONObject(0)

                val parsedList = mutableListOf<PubChemCompound>()
                if (pcCompounds != null) {
                    val cidObj = pcCompounds.optJSONObject("id")?.optJSONObject("id")
                    val cid = cidObj?.optLong("cid") ?: 0L

                    var formula = ""
                    var mw = 0.0
                    var iupac = ""
                    var smiles = ""

                    val props = pcCompounds.optJSONArray("props")
                    if (props != null) {
                        for (i in 0 until props.length()) {
                            val p = props.getJSONObject(i)
                            val urn = p.optJSONObject("urn")
                            val label = urn?.optString("label") ?: ""
                            val name = urn?.optString("name") ?: ""
                            val valueObj = p.optJSONObject("value")

                            when {
                                label == "Molecular Formula" -> {
                                    formula = valueObj?.optString("sval") ?: ""
                                }
                                label == "Molecular Weight" -> {
                                    mw = valueObj?.optDouble("fval") ?: valueObj?.optDouble("sval") ?: 0.0
                                }
                                label == "IUPAC Name" && (name == "Preferred" || iupac.isEmpty()) -> {
                                    iupac = valueObj?.optString("sval") ?: ""
                                }
                                label == "SMILES" && (name == "Canonical" || smiles.isEmpty()) -> {
                                    smiles = valueObj?.optString("sval") ?: ""
                                }
                            }
                        }
                    }

                    if (cid > 0) {
                        parsedList.add(
                            PubChemCompound(
                                cid = cid,
                                name = cleanQuery,
                                iupacName = iupac.ifEmpty { cleanQuery },
                                molecularFormula = formula,
                                molecularWeight = mw,
                                casNumber = if (cleanQuery.matches(Regex("\\d+-\\d+-\\d+"))) cleanQuery else "",
                                canonicalSmiles = smiles,
                                estimatedDensity = if (formula.contains("Ti")) 4.2 else if (formula.contains("Ca")) 2.7 else 1.05,
                                description = "مركب كيميائي مسجل في NCBI PubChem برقم CID $cid وصيغة $formula.",
                                pubChemUrl = "https://pubchem.ncbi.nlm.nih.gov/compound/$cid"
                            )
                        )
                    }
                }

                if (parsedList.isNotEmpty()) {
                    return@withContext Result.success(parsedList)
                }
            }
            conn.disconnect()
            // If HTTP had no results or error, return local matches or empty
            Result.success(localMatches)
        }.getOrElse {
            // Offline fallback
            Result.success(localMatches)
        }
    }
}

package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DeletedRecordsRegistry
import com.example.data.local.FactoryItemEntity
import com.example.data.local.FormulationEntity
import com.example.data.local.FormulationIngredient
import com.example.data.local.LabExperimentEntity
import com.example.data.local.PmpDatabase
import com.example.data.local.UserAccountEntity
import com.example.data.repository.PmpRepository
import com.example.domain.inventory.InventoryMovementEngine
import com.example.domain.inventory.ProductionAndProcurementEngine
import com.example.domain.inventory.ProductionOrderCreateInput
import com.example.util.LabExcelAnalyzer
import com.example.util.LabSecurityManager
import com.example.util.PvcEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChemicalLabEnterpriseTest {

    private lateinit var context: Context
    private lateinit var db: PmpDatabase
    private lateinit var repository: PmpRepository
    private lateinit var prodEngine: ProductionAndProcurementEngine

    private val adminUser = UserAccountEntity(
        id = 1L,
        userCode = "USR-ADM",
        fullName = "المدير العام",
        username = "admin",
        role = "مدير النظام",
        branch = "الإدارة العامة",
        permissions = "ALL",
        serverSyncToken = "tok-adm",
        active = true,
        isCurrentSession = true
    )
    private val labUser = UserAccountEntity(
        id = 2L,
        userCode = "USR-LAB",
        fullName = "كيميائي المختبر",
        username = "chemist",
        role = "مهندس مختبر كيميائي",
        branch = "المصنع",
        permissions = "المختبر الكيميائي,VIEW_SECRET_FORMULAS,EXPORT_FORMULAS",
        serverSyncToken = "tok-lab",
        active = true
    )
    private val qcUser = UserAccountEntity(
        id = 3L,
        userCode = "USR-QC",
        fullName = "مهندس الجودة",
        username = "qc_lead",
        role = "مسؤول الجودة QC",
        branch = "المصنع",
        permissions = "الجودة",
        serverSyncToken = "tok-qc",
        active = true
    )
    private val productionUser = UserAccountEntity(
        id = 4L,
        userCode = "USR-PRD",
        fullName = "مهندس الإنتاج",
        username = "prod_eng",
        role = "مهندس الإنتاج",
        branch = "المصنع",
        permissions = "المصنع,الإنتاج",
        serverSyncToken = "tok-prd",
        active = true
    )
    private val accountantUser = UserAccountEntity(
        id = 5L,
        userCode = "USR-ACC",
        fullName = "المحاسب",
        username = "acc1",
        role = "محاسب",
        branch = "الإدارة",
        permissions = "المحاسبة",
        serverSyncToken = "tok-acc",
        active = true
    )
    private val warehouseUser = UserAccountEntity(
        id = 6L,
        userCode = "USR-WH",
        fullName = "أمين المخزن",
        username = "wh1",
        role = "أمين مخزن الخامات",
        branch = "المصنع",
        permissions = "المخازن",
        serverSyncToken = "tok-wh",
        active = true
    )
    private val salesUser = UserAccountEntity(
        id = 7L,
        userCode = "USR-SLS",
        fullName = "مندوب مبيعات",
        username = "sales1",
        role = "مسؤول مبيعات",
        branch = "مصر",
        permissions = "المبيعات",
        serverSyncToken = "tok-sls",
        active = true
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        DeletedRecordsRegistry.resetForTesting()
        db = Room.inMemoryDatabaseBuilder(context, PmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PmpRepository(db.pmpDao(), db)
        val invEngine = InventoryMovementEngine(db)
        prodEngine = ProductionAndProcurementEngine(db, invEngine)
    }

    @After
    fun tearDown() {
        db.close()
        DeletedRecordsRegistry.resetForTesting()
    }

    @Test
    fun `PvcEngine calculates precision totals, scaling, and 3-cost variance without mutating baseline`() {
        val ingredients = listOf(
            FormulationIngredient(
                id = "1",
                materialId = 10L,
                itemCode = "RM-TIO2",
                name = "ثاني أكسيد التيتانيوم",
                type = "صبغة / ملون",
                weight = 180.0,
                density = 4.2,
                solidsPercent = 100.0,
                unitCost = 4.50
            ),
            FormulationIngredient(
                id = "2",
                materialId = 11L,
                itemCode = "RM-RES",
                name = "راتنج أكريليك",
                type = "راتنج / بوليمر رابط",
                weight = 420.0,
                density = 1.06,
                solidsPercent = 50.0,
                unitCost = 2.80
            ),
            FormulationIngredient(
                id = "3",
                materialId = 12L,
                itemCode = "RM-WAT",
                name = "ماء تشغيل",
                type = "مذيب / ماء",
                weight = 400.0,
                density = 1.0,
                solidsPercent = 0.0,
                unitCost = 0.05
            )
        )

        val calc = PvcEngine.calculate(ingredients)
        assertEquals(1000.0, calc.totalBatchWeight, 0.01)
        // 180*4.50 + 420*2.80 + 400*0.05 = 810 + 1176 + 20 = 2006.0
        assertEquals(2006.0, calc.totalCost, 0.01)
        assertEquals(2.006, calc.costPerKg, 0.001)
        assertEquals(100.0, calc.totalPercentageCheck, 0.1)
        assertTrue(calc.calculatedPvc > 0.0)

        val scaled = PvcEngine.scaleFormulation(ingredients, 250.0)
        assertEquals(0.25, scaled.scaleFactor, 0.0001)
        assertEquals(501.5, scaled.scaledTotalCost, 0.01)

        val approvedFormula = FormulationEntity(
            id = 5L,
            formulaNo = "FRM-2026-05",
            productName = "أبيض لامع 500",
            versionNumber = 1,
            approvalStatus = "APPROVED",
            approvedForProduction = true,
            totalCost = 2006.0,
            costPerKg = 2.006,
            historicalTotalCost = 2006.0,
            historicalCostPerKg = 2.006
        )
        val liveRegistry = listOf(
            FactoryItemEntity(id = 10L, name = "ثاني أكسيد التيتانيوم", unit = "كجم", category = "raw", unitCostEgp = 5.50, lastPurchasePrice = 5.50),
            FactoryItemEntity(id = 11L, name = "راتنج أكريليك", unit = "كجم", category = "raw", unitCostEgp = 2.80, lastPurchasePrice = 2.80),
            FactoryItemEntity(id = 12L, name = "ماء تشغيل", unit = "كجم", category = "raw", unitCostEgp = 0.05, lastPurchasePrice = 0.05)
        )
        val report = PvcEngine.compareThreeCosts(
            formulation = approvedFormula,
            ingredients = ingredients,
            rawMaterials = liveRegistry,
            actualProductionTotalCost = 2250.0,
            actualProducedWeightKg = 1000.0
        )
        // TiO2 rose +1.00 * 180 kg = +180 => current 2186.0
        assertEquals(2006.0, report.historicalApprovedTotalCost, 0.01)
        assertEquals(2186.0, report.currentEstimatedTotalCost, 0.01)
        assertEquals(180.0, report.currentVsHistoricalDeltaTotal, 0.01)
        assertEquals(2250.0, report.actualProductionTotalCost!!, 0.01)
        assertEquals(1, report.priceImpacts.size)
        assertEquals(180.0, report.priceImpacts.first().deltaTotalBatchCost, 0.01)
    }

    @Test
    fun `versioning locks historical cost on edit, updates price history cleanly, and blocks unapproved production`(): Unit = runBlocking {
        val matId = db.pmpDao().insertFactoryItem(
            FactoryItemEntity(
                itemCode = "RM-101",
                name = "كربونات كالسيوم",
                unit = "كجم",
                category = "raw",
                unitCostEgp = 0.50,
                lastPurchasePrice = 0.50
            )
        )
        val ingV1 = listOf(
            FormulationIngredient(
                id = "ing1",
                materialId = matId,
                itemCode = "RM-101",
                name = "كربونات كالسيوم",
                type = "مالئ وحبيبات",
                weight = 500.0,
                density = 2.7,
                solidsPercent = 100.0,
                unitCost = 0.50
            )
        )
        val draft = FormulationEntity(
            formulaNo = "PMP-MAT-01",
            productName = "معجون أكريليك خارجي",
            totalBatchWeight = 500.0,
            totalCost = 250.0,
            costPerKg = 0.50,
            ingredientsJson = PvcEngine.ingredientsToJson(ingV1),
            createdBy = labUser.fullName
        )

        val fId = repository.saveFormulationWithVersioning(
            formulation = draft,
            actorName = labUser.fullName,
            actorAccount = labUser,
            changeReason = "إنشاء مبدئي"
        ).getOrThrow()

        // Must be blocked from production before approval!
        val blockedProd = repository.createFactoryOrderFromFormulation(fId, 1.0, productionUser.fullName, productionUser)
        assertTrue("Draft formulation must be blocked from factory order", blockedProd.isFailure)

        val p3Blocked = prodEngine.createProductionOrder(
            ProductionOrderCreateInput(
                productName = "معجون أكريليك خارجي",
                formulaId = fId,
                packageWeightGrams = 900,
                targetCartons = 20,
                rawWarehouseId = 1L,
                fgWarehouseId = 2L,
                productionEngineer = productionUser.fullName
            )
        )
        assertTrue("Draft formulation must be blocked from Phase 3 production order", p3Blocked.isFailure)

        // Admin approves v1 -> locks historicalTotalCost = 250.0
        val approvedV1 = repository.decideFormulationApproval(
            formulaId = fId,
            decisionStatus = "APPROVED",
            decisionNote = "اعتماد الإنتاج الأول",
            actorAccount = adminUser,
            actorName = adminUser.fullName
        ).getOrThrow()
        assertEquals("APPROVED", approvedV1.approvalStatus)
        assertTrue(approvedV1.approvedForProduction)
        assertEquals(250.0, approvedV1.historicalTotalCost, 0.01)

        // Approved formulation succeeds in production order!
        val okProd = repository.createFactoryOrderFromFormulation(fId, 1.0, productionUser.fullName, productionUser)
        assertTrue("Approved formulation must allow production order", okProd.isSuccess)

        // Raw material registry price spike to 0.80 EGP must update FactoryItem + history, NOT mutate locked v1 cost
        val updatedMat = repository.updateRawMaterialRegistryPrice(
            materialId = matId,
            newPrice = 0.80,
            actorName = adminUser.fullName,
            actorAccount = adminUser
        ).getOrThrow()
        assertEquals(0.80, updatedMat.lastPurchasePrice, 0.001)
        assertEquals(250.0, db.pmpDao().getFormulationById(fId)!!.historicalTotalCost, 0.01)
        assertEquals(1, db.labDao().getAllRawMaterialPriceHistoryDirect().size)

        // Lab engineer edits approved formula -> becomes v2 NEEDS_REVIEW, v1 becomes ARCHIVED, production blocked again
        val ingV2 = listOf(ingV1.first().copy(weight = 600.0, unitCost = 0.80))
        repository.saveFormulationWithVersioning(
            formulation = approvedV1.copy(
                totalBatchWeight = 600.0,
                totalCost = 480.0,
                costPerKg = 0.80,
                ingredientsJson = PvcEngine.ingredientsToJson(ingV2)
            ),
            actorName = labUser.fullName,
            actorAccount = labUser,
            changeReason = "تعديل لزيادة التغطية"
        ).getOrThrow()

        val afterEdit = db.pmpDao().getFormulationById(fId)!!
        assertEquals(2, afterEdit.versionNumber)
        assertEquals("NEEDS_REVIEW", afterEdit.approvalStatus)
        assertFalse(afterEdit.approvedForProduction)
        assertEquals("Historical baseline cost must remain locked at v1 value", 250.0, afterEdit.historicalTotalCost, 0.01)

        val versions = db.labDao().getVersionsForFormulaDirect(fId)
        assertTrue(versions.any { it.versionNumber == 1 && it.status == "ARCHIVED" })
        assertTrue(versions.any { it.versionNumber == 2 && it.status == "NEEDS_REVIEW" })

        val blockedV2 = repository.createFactoryOrderFromFormulation(fId, 1.0, productionUser.fullName, productionUser)
        assertTrue("Edited NEEDS_REVIEW v2 must be blocked until re-approved", blockedV2.isFailure)

        // Lab experiment pinned to v2 + QC decision flow
        val expId = repository.recordLabExperiment(
            LabExperimentEntity(
                syncUuid = "EXP-SYN-V2-01",
                experimentNo = "EXP-V2-01",
                formulaId = fId,
                formulaNo = afterEdit.formulaNo,
                versionNumber = 2,
                productName = afterEdit.productName,
                experimentDate = "2026-11-18",
                technicianName = labUser.fullName,
                notes = "فحص اللزوجة للنسخة v2"
            ),
            actorAccount = labUser
        ).getOrThrow()
        val qcPassed = repository.decideLabExperimentQc(
            experimentId = expId,
            decisionStatus = "PASSED",
            decisionNote = "مطابق لمواصفات الكثافة والنعومة",
            actorAccount = qcUser,
            actorName = qcUser.fullName
        ).getOrThrow()
        assertEquals("PASSED", qcPassed.status)
    }

    @Test
    fun `LabExcelAnalyzer parses multi-sheet xlsx, detects external & broken refs, and stages NEEDS_REVIEW without overwriting approved`(): Unit = runBlocking {
        // Pre-existing approved formula sharing name -> must NOT get auto-approved on Excel import!
        val existingId = repository.saveFormulationWithVersioning(
            formulation = FormulationEntity(
                formulaNo = "F-XLS-01",
                productName = "دهان سيلر مائي",
                totalBatchWeight = 100.0,
                totalCost = 300.0,
                costPerKg = 3.0,
                ingredientsJson = "[]",
                createdBy = adminUser.fullName
            ),
            actorName = adminUser.fullName,
            actorAccount = adminUser,
            initialStatus = "APPROVED"
        ).getOrThrow()
        repository.decideFormulationApproval(existingId, "APPROVED", "معتمد مسبقاً", adminUser, adminUser.fullName)

        val xlsxBytes = buildSyntheticFormulaWorkbookXlsx()
        val analysis = LabExcelAnalyzer.analyzeBytes(
            bytes = xlsxBytes,
            fileName = "factory_formulas_test.xlsx",
            existingRawMaterials = emptyList(),
            existingFormulations = db.pmpDao().getFormulationsDirect()
        ).getOrThrow()

        assertEquals(2, analysis.worksheetCount)
        assertTrue("Expected cross-sheet relationship detected", analysis.crossSheetRelationships.any { it.contains("Prices") })
        assertTrue("Expected external workbook link warning", analysis.externalWorkbookLinks.isNotEmpty())
        assertTrue("Expected broken #REF! issue detected", analysis.brokenReferenceIssues.isNotEmpty())
        assertTrue("Expected formula calculation mismatch detected", analysis.formulaVerificationMismatches.isNotEmpty())
        assertTrue(analysis.totalFormulaCells > 0)
        assertEquals(1, analysis.candidateFormulas.size)

        val draft = analysis.candidateFormulas.first()
        assertEquals("F-XLS-01", draft.suggestedFormulaNo)
        assertEquals(2, draft.ingredients.size)
        // Cross-sheet formula ='Prices'!B2 (5.00) evaluated dynamically
        assertEquals(5.00, draft.ingredients.first { it.name.contains("تيتانيوم") }.unitCost, 0.01)

        // Commit staged import -> strictly moves formula to NEEDS_REVIEW, approvedForProduction = false, and deduplicates raw materials
        val count = repository.commitStagedExcelImport(
            analysis = analysis,
            selectedDrafts = analysis.candidateFormulas,
            archiveFilePath = "/private/vault/factory_formulas_test.xlsx",
            actorName = labUser.fullName,
            actorAccount = labUser
        ).getOrThrow()

        assertEquals(1, count)
        val afterImport = db.pmpDao().getFormulationById(existingId)!!
        assertEquals("NEEDS_REVIEW", afterImport.approvalStatus)
        assertFalse(afterImport.approvedForProduction)
        assertEquals(2, db.pmpDao().getFactoryItemsDirect().size)
        assertEquals(1, db.labDao().getAllExcelImportsDirect().size)
    }

    @Test
    fun `7-role RBAC matrix, explicit DENY precedence, log sanitization, and PBKDF2 AES-GCM sealing hold strictly`() {
        // Admin: full access
        assertTrue(LabSecurityManager.canViewSecretFormulas(adminUser))
        assertTrue(LabSecurityManager.canCreateOrEditFormulas(adminUser))
        assertTrue(LabSecurityManager.canApproveFormulas(adminUser))
        assertTrue(LabSecurityManager.canExportSecretFormulas(adminUser))

        // Lab Chemist: view & edit & export, cannot approve without APPROVE_FORMULAS
        assertTrue(LabSecurityManager.canViewSecretFormulas(labUser))
        assertTrue(LabSecurityManager.canCreateOrEditFormulas(labUser))
        assertFalse(LabSecurityManager.canApproveFormulas(labUser))
        assertTrue(LabSecurityManager.canExportSecretFormulas(labUser))
        assertTrue(LabSecurityManager.canLogLabExperiments(labUser))

        // QC Officer: decide QC gate & log experiments, NEVER view secret formulas
        assertFalse(LabSecurityManager.canViewSecretFormulas(qcUser))
        assertFalse(LabSecurityManager.canCreateOrEditFormulas(qcUser))
        assertTrue(LabSecurityManager.canLogLabExperiments(qcUser))
        assertTrue(LabSecurityManager.canDecideQcGate(qcUser))

        // Production / Warehouse / Sales / Accountant: NEVER view secret ratios
        listOf(productionUser, accountantUser, warehouseUser, salesUser).forEach { nonLab ->
            assertFalse("Secret ratios must be hidden for ${nonLab.role}", LabSecurityManager.canViewSecretFormulas(nonLab))
            assertFalse(LabSecurityManager.canExportSecretFormulas(nonLab))
        }
        assertTrue(LabSecurityManager.canViewFormulaCosts(accountantUser))
        assertFalse(LabSecurityManager.canViewFormulaCosts(productionUser))
        assertFalse(LabSecurityManager.canViewRawMaterialRegistry(salesUser))
        assertTrue(LabSecurityManager.canViewRawMaterialRegistry(warehouseUser))

        // Explicit DENY overrides allow role
        val deniedChemist = labUser.copy(permissions = "المختبر الكيميائي,DENY:VIEW_SECRET_FORMULAS")
        assertFalse(LabSecurityManager.canViewSecretFormulas(deniedChemist))

        // Formula redaction hides ingredientsJson and sensitive costs based on role
        val sensitive = FormulationEntity(
            formulaNo = "SEC-01",
            productName = "منتج سري",
            totalCost = 5000.0,
            costPerKg = 5.0,
            ingredientsJson = "[{\"name\":\"Resin\",\"weight\":500}]"
        )
        val forProd = LabSecurityManager.redactFormulationForUser(sensitive, productionUser)
        assertEquals("[]", forProd.ingredientsJson)
        assertEquals(0.0, forProd.totalCost, 0.0)

        // Log sanitization strips raw percentages & ingredient JSON
        val sanitized = LabSecurityManager.sanitizeLogText("Formula X TiO2 22.5% payload [{\"weight\":225}] failed")
        assertFalse(sanitized.contains("22.5%"))
        assertFalse(sanitized.contains("225"))

        // Keystore / JVM AES-GCM & PBKDF2 passphrase sealing roundtrip
        val secretPayload = "[{\"name\":\"TiO2\",\"weight\":185.5}]"
        val devSealed = LabSecurityManager.sealWithDeviceKey(secretPayload).getOrThrow()
        assertFalse(devSealed.contains("TiO2"))
        assertEquals(secretPayload, LabSecurityManager.unsealWithDeviceKey(devSealed).getOrThrow())

        val pass = "PmpLab@2026!".toCharArray()
        val pwdSealed = LabSecurityManager.sealWithPassphrase(secretPayload, pass).getOrThrow()
        assertFalse(pwdSealed.contains("185.5"))
        assertEquals(secretPayload, LabSecurityManager.unsealWithPassphrase(pwdSealed, pass).getOrThrow())
        assertTrue(LabSecurityManager.unsealWithPassphrase(pwdSealed, "WrongPass123".toCharArray()).isFailure)
    }

    private fun buildSyntheticFormulaWorkbookXlsx(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun addEntry(name: String, xml: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            addEntry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                </Types>""".trimIndent()
            )
            addEntry(
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Formula1" sheetId="1" r:id="rId1"/>
                    <sheet name="Prices" sheetId="2" r:id="rId2"/>
                  </sheets>
                </workbook>""".trimIndent()
            )
            addEntry(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                </Relationships>""".trimIndent()
            )
            // Sheet 2: Prices
            addEntry(
                "xl/worksheets/sheet2.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="inlineStr"><is><t>Material</t></is></c>
                      <c r="B1" t="inlineStr"><is><t>Price</t></is></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="inlineStr"><is><t>ثاني أكسيد التيتانيوم</t></is></c>
                      <c r="B2"><v>5.00</v></c>
                    </row>
                    <row r="3">
                      <c r="A3" t="inlineStr"><is><t>BrokenLink</t></is></c>
                      <c r="B3" t="e"><f>[1]ExternalBook.xlsx!A1</f><v>#REF!</v></c>
                    </row>
                  </sheetData>
                </worksheet>""".trimIndent()
            )
            // Sheet 1: Formula1 (with cross-sheet ref ='Prices'!B2, SUM formula with mismatch cached value, and code F-XLS-01)
            addEntry(
                "xl/worksheets/sheet1.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">
                      <c r="A1" t="inlineStr"><is><t>كود التركيبة</t></is></c>
                      <c r="B1" t="inlineStr"><is><t>F-XLS-01</t></is></c>
                    </row>
                    <row r="2">
                      <c r="A2" t="inlineStr"><is><t>اسم المنتج</t></is></c>
                      <c r="B2" t="inlineStr"><is><t>دهان سيلر مائي</t></is></c>
                    </row>
                    <row r="3">
                      <c r="A3" t="inlineStr"><is><t>اسم المادة</t></is></c>
                      <c r="B3" t="inlineStr"><is><t>الوزن</t></is></c>
                      <c r="C3" t="inlineStr"><is><t>السعر</t></is></c>
                    </row>
                    <row r="4">
                      <c r="A4" t="inlineStr"><is><t>ثاني أكسيد التيتانيوم</t></is></c>
                      <c r="B4"><v>40</v></c>
                      <c r="C4"><f>'Prices'!B2</f><v>5.00</v></c>
                    </row>
                    <row r="5">
                      <c r="A5" t="inlineStr"><is><t>راتنج أكريليك</t></is></c>
                      <c r="B5"><v>60</v></c>
                      <c r="C5"><v>3.00</v></c>
                    </row>
                    <row r="6">
                      <c r="A6" t="inlineStr"><is><t>الإجمالي</t></is></c>
                      <c r="B6"><f>SUM(B4:B5)</f><v>999</v></c>
                    </row>
                  </sheetData>
                </worksheet>""".trimIndent()
            )
        }
        return out.toByteArray()
    }
}

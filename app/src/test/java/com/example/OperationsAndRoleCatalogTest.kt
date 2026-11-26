package com.example

import com.example.core.architecture.EmployeeRoleCatalog
import com.example.core.architecture.EmployeeRoleKey
import com.example.data.local.UserAccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsAndRoleCatalogTest {

    @Test
    fun `role catalog resolves all 9 factory and Sudan roles`() {
        assertEquals(9, EmployeeRoleCatalog.ROLE_PRESET_LIST.size)

        val gm = UserAccountEntity(fullName = "م. مجدي فضل", username = "admin", role = "مدير النظام", branch = "SD-001", permissions = "الكل")
        assertEquals(EmployeeRoleKey.GENERAL_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(gm))

        val fin = UserAccountEntity(fullName = "أ. أحمد الطلخاوي", username = "talkhawy", role = "المدير المالي (مصر)", branch = "EG-001", permissions = "المحاسبة")
        assertEquals(EmployeeRoleKey.FINANCE_MANAGER_EGYPT, EmployeeRoleCatalog.resolveEmployeeRoleKey(fin))

        val qc = UserAccountEntity(fullName = "م. رانيا سمير", username = "rania", role = "مدير الجودة والمشتريات", branch = "EG-001", permissions = "المشتريات")
        assertEquals(EmployeeRoleKey.QUALITY_PROCUREMENT_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(qc))

        val prod = UserAccountEntity(fullName = "م. مصطفى الصيفي", username = "saify", role = "مهندس الإنتاج", branch = "EG-001", permissions = "المصنع")
        assertEquals(EmployeeRoleKey.PRODUCTION_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(prod))

        val pack = UserAccountEntity(fullName = "م. محمد عبدالباسط", username = "pack1", role = "مهندس التعبئة والتغليف", branch = "EG-001", permissions = "التعبئة والتغليف")
        assertEquals(EmployeeRoleKey.PACKING_ENGINEER, EmployeeRoleCatalog.resolveEmployeeRoleKey(pack))

        val wh = UserAccountEntity(fullName = "إبراهيم مبارك", username = "ibrahim", role = "أمين مخزن مصر", branch = "EG-001", permissions = "المخازن")
        assertEquals(EmployeeRoleKey.WAREHOUSE_KEEPER_EGYPT, EmployeeRoleCatalog.resolveEmployeeRoleKey(wh))

        val load = UserAccountEntity(fullName = "منذر مزمل", username = "monzer", role = "مسؤول التحميل والعدد", branch = "EG-001", permissions = "المخازن")
        assertEquals(EmployeeRoleKey.LOADING_TOOLS_SUPERVISOR, EmployeeRoleCatalog.resolveEmployeeRoleKey(load))

        val sudan = UserAccountEntity(fullName = "عمر عبدالوهاب", username = "omar_hf", role = "محاسب خزنة ومبيعات", branch = "مخزن حلفا (HF-001)", permissions = "الخزن,التحويلات")
        assertEquals(EmployeeRoleKey.SUDAN_BRANCH_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(sudan))

        val sales = UserAccountEntity(fullName = "مندوب", username = "sales1", role = "مسؤول مبيعات وفواتير", branch = "SD-001", permissions = "المبيعات")
        assertEquals(EmployeeRoleKey.SALES_AGENT, EmployeeRoleCatalog.resolveEmployeeRoleKey(sales))
    }

    @Test
    fun `shift evaluation flags waste above 25 cans and tracks 1000 carton shift target`() {
        val ok = EmployeeRoleCatalog.evaluateShiftMetrics(500, 490, 20, 25, 10, 5)
        assertEquals(98.0, ok.achievementPercent, 0.01)
        assertFalse(ok.wasteExceeded)
        assertEquals(15, ok.totalDowntimeMin)

        val badWaste = EmployeeRoleCatalog.evaluateShiftMetrics(500, 500, 31, 25, 0, 0)
        assertTrue(badWaste.wasteExceeded)
        assertEquals(6, badWaste.wasteDiff)
    }

    @Test
    fun `budget deviation and 3-tier bonus rules match spec`() {
        val within = EmployeeRoleCatalog.evaluateBudgetDeviation(100000.0, 104500.0)
        assertFalse(within.exceeded)
        assertEquals(4.5, within.deviationPercent, 0.01)

        val over = EmployeeRoleCatalog.evaluateBudgetDeviation(100000.0, 106000.0)
        assertTrue(over.exceeded)
        assertEquals(6.0, over.deviationPercent, 0.01)

        assertEquals(5, EmployeeRoleCatalog.evaluateEmployeeBonusTier(100.0, 1.0, 0, 1).bonusMonths)
        assertEquals(4, EmployeeRoleCatalog.evaluateEmployeeBonusTier(100.0, 2.0, 0, 0).bonusMonths)
        assertEquals(3, EmployeeRoleCatalog.evaluateEmployeeBonusTier(96.0, 4.5, 4, 0).bonusMonths)
        assertEquals(0, EmployeeRoleCatalog.evaluateEmployeeBonusTier(94.0, 2.0, 0, 0).bonusMonths)
    }
}

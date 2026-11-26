package com.example

import com.example.core.architecture.EmployeeRoleCatalog
import com.example.core.architecture.EmployeeRoleKey
import com.example.data.local.UserAccountEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationsAndRoleCatalogTest {

    private fun fakeUser(
        fullName: String,
        username: String,
        role: String,
        branch: String,
        permissions: String
    ) = UserAccountEntity(
        userCode = "USR-${username.uppercase()}",
        fullName = fullName,
        username = username,
        role = role,
        branch = branch,
        permissions = permissions,
        serverSyncToken = "SYNC-$username"
    )

    @Test
    fun `role catalog resolves all 9 factory and Sudan roles`() {
        assertEquals(9, EmployeeRoleCatalog.ROLE_PRESET_LIST.size)

        val gm = fakeUser("م. مجدي فضل", "admin", "مدير النظام", "SD-001", "الكل")
        assertEquals(EmployeeRoleKey.GENERAL_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(gm))

        val fin = fakeUser("أ. أحمد الطلخاوي", "talkhawy", "المدير المالي (مصر)", "EG-001", "المحاسبة")
        assertEquals(EmployeeRoleKey.FINANCE_MANAGER_EGYPT, EmployeeRoleCatalog.resolveEmployeeRoleKey(fin))

        val qc = fakeUser("م. رانيا سمير", "rania", "مدير الجودة والمشتريات", "EG-001", "المشتريات")
        assertEquals(EmployeeRoleKey.QUALITY_PROCUREMENT_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(qc))

        val prod = fakeUser("م. مصطفى الصيفي", "saify", "مهندس الإنتاج", "EG-001", "المصنع")
        assertEquals(EmployeeRoleKey.PRODUCTION_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(prod))

        val pack = fakeUser("م. محمد عبدالباسط", "pack1", "مهندس التعبئة والتغليف", "EG-001", "التعبئة والتغليف")
        assertEquals(EmployeeRoleKey.PACKING_ENGINEER, EmployeeRoleCatalog.resolveEmployeeRoleKey(pack))

        val wh = fakeUser("إبراهيم مبارك", "ibrahim", "أمين مخزن مصر", "EG-001", "المخازن")
        assertEquals(EmployeeRoleKey.WAREHOUSE_KEEPER_EGYPT, EmployeeRoleCatalog.resolveEmployeeRoleKey(wh))

        val load = fakeUser("منذر مزمل", "monzer", "مسؤول التحميل والعدد", "EG-001", "المخازن")
        assertEquals(EmployeeRoleKey.LOADING_TOOLS_SUPERVISOR, EmployeeRoleCatalog.resolveEmployeeRoleKey(load))

        val sudan = fakeUser("عمر عبدالوهاب", "omar_hf", "محاسب خزنة ومبيعات", "مخزن حلفا (HF-001)", "الخزن,التحويلات")
        assertEquals(EmployeeRoleKey.SUDAN_BRANCH_MANAGER, EmployeeRoleCatalog.resolveEmployeeRoleKey(sudan))

        val sales = fakeUser("مندوب", "sales1", "مسؤول مبيعات وفواتير", "SD-001", "المبيعات")
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

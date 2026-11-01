package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CloudAccountSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createLinkedAccount_validPayload_createsDocumentAndReturnsCode() = runBlocking {
        val managerUid = signInTestUser(MANAGER_EMAIL)
        val repository = CloudAccountSyncRepository(firestore)
        val code = "USR-SD-" + UUID.randomUUID().toString().take(8)

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createLinkedAccount(
                userCode = code,
                fullName = "أحمد محمد - محاسب",
                username = "sudan_acc",
                role = "محاسب خزنة ومبيعات",
                branch = "فرع السودان (SD-001)",
                permissions = "الخزن,المبيعات,المخازن",
                serverSyncToken = "SYNC-PMP-SD-1001",
                linkedUid = managerUid
            )
        }
        assertTrue("Expected createLinkedAccount to succeed: ${createResult.exceptionOrNull()}", createResult.isSuccess)
        assertEquals(code, createResult.getOrThrow())
    }

    @Test
    fun getManagedAccounts_authenticatedManager_returnsMatchingAccounts() = runBlocking {
        val managerUid = signInTestUser(MANAGER_EMAIL)
        val repository = CloudAccountSyncRepository(firestore)
        val code = "USR-EG-" + UUID.randomUUID().toString().take(8)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createLinkedAccount(
                userCode = code,
                fullName = "محمود حسن - مهندس مصنع",
                username = "factory_eng",
                role = "مهندس إنتاج - المصنع",
                branch = "فرع مصر والمصنع (EG-001)",
                permissions = "المصنع,المخازن,التقارير",
                serverSyncToken = "SYNC-PMP-EG-1002",
                linkedUid = managerUid
            ).getOrThrow()
        }

        val accountsResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.getManagedAccounts() }
        assertTrue(accountsResult.isSuccess)
        assertTrue(accountsResult.getOrThrow().any { it.userCode == code && it.permissions == "المصنع,المخازن,التقارير" })
    }

    @Test
    fun observeAssignedAccounts_linkedEmployee_emitsRealtimePermissionUpdates() = runBlocking {
        val employeeUid = signInTestUser(EMPLOYEE_EMAIL)
        val managerUid = signInTestUser(MANAGER_EMAIL)
        val managerRepo = CloudAccountSyncRepository(firestore)
        val code = "USR-HF-" + UUID.randomUUID().toString().take(8)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            managerRepo.createLinkedAccount(
                userCode = code,
                fullName = "عثمان علي - أمين مخزن",
                username = "halfa_store",
                role = "أمين مخزن وترحيل",
                branch = "مخزن حلفا (HF-001)",
                permissions = "المخازن,المصنع",
                serverSyncToken = "SYNC-PMP-HF-1003",
                linkedUid = employeeUid
            ).getOrThrow()
        }

        signInTestUser(EMPLOYEE_EMAIL)
        val employeeRepo = CloudAccountSyncRepository(firestore)
        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            employeeRepo.observeAssignedAccounts().first { list -> list.any { it.userCode == code } }
        }
        assertTrue(emitted.any { it.userCode == code && it.linkedUid == employeeUid && it.permissions == "المخازن,المصنع" })
    }

    @Test
    fun getLinkedAccountByCode_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val managerUid = signInTestUser(MANAGER_EMAIL)
        val managerRepo = CloudAccountSyncRepository(firestore)
        val code = "USR-SEC-" + UUID.randomUUID().toString().take(8)

        withTimeout(DEFAULT_TIMEOUT_MS) {
            managerRepo.createLinkedAccount(
                userCode = code,
                fullName = "حساب خاص بالمدير",
                username = "admin_priv",
                role = "مدير النظام",
                branch = "فرع السودان (SD-001)",
                permissions = "الخزن,المبيعات",
                serverSyncToken = "SYNC-SEC-1",
                linkedUid = managerUid
            ).getOrThrow()
        }

        signInTestUser(INTRUDER_EMAIL)
        val intruderRepo = CloudAccountSyncRepository(firestore)
        val result = withTimeout(DEFAULT_TIMEOUT_MS) { intruderRepo.getLinkedAccountByCode(code) }
        assertTrue("Expected cross-user getLinkedAccountByCode to fail", result.isFailure)
        val fsEx = result.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeManagedAccounts_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = CloudAccountSyncRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeManagedAccounts().first()
            }
            fail("Expected unauthenticated observeManagedAccounts to throw Exception")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected nested FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    private companion object {
        const val MANAGER_EMAIL = "manager@pmp-test.com"
        const val EMPLOYEE_EMAIL = "employee@pmp-test.com"
        const val INTRUDER_EMAIL = "intruder@pmp-test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}

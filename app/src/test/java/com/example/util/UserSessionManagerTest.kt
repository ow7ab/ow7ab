package com.example.util

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PmpDatabase
import com.example.data.local.UserAccountEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserSessionManagerTest {

    private lateinit var context: Context
    private lateinit var database: PmpDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PmpDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testUserSessionManagerSingleton() {
        val manager1 = UserSessionManager.getInstance(context, database)
        val manager2 = UserSessionManager.getInstance(context, database)
        assertSame(manager1, manager2)
    }

    @Test
    fun testClearLocalCachesAndSessionData_purgesFilesAndResetsRoom() = runBlocking {
        val dao = database.pmpDao()

        // 1. Insert a user and mark them active
        val user = UserAccountEntity(
            id = 101L,
            userCode = "EMP-101",
            fullName = "Test User",
            username = "test_user",
            phoneOrEmail = "test@example.com",
            pinOrPassword = "password123",
            role = "كاشير",
            branch = "فرع الخرطوم",
            permissions = "الخزن,المبيعات",
            serverSyncToken = "SYNC-TOKEN-101",
            isCurrentSession = true
        )
        dao.insertUserAccount(user)

        var loadedUser = dao.getUserById(101L)
        assertNotNull(loadedUser)
        assertTrue(loadedUser!!.isCurrentSession)

        // 2. Create a temporary cached file
        val tempCacheFile = File(context.cacheDir, "sensitive_invoice_export.pdf")
        tempCacheFile.writeText("Confidential financial statement")
        assertTrue(tempCacheFile.exists())

        // 3. Instantiate UserSessionManager
        val manager = UserSessionManager(
            appContext = context,
            database = database
        )

        // 4. Perform session cleanup
        manager.clearLocalCachesAndSessionData(
            clearBiometrics = true,
            clearFileCache = true,
            clearActiveRoomSession = true
        )

        // 5. Verify cached file is deleted
        assertFalse("Cache file should have been purged", tempCacheFile.exists())

        // 6. Verify Room active session was cleared (isCurrentSession should be false)
        loadedUser = dao.getUserById(101L)
        assertNotNull(loadedUser)
        assertFalse("Active session flag in Room should be false", loadedUser!!.isCurrentSession)
    }

    @Test
    fun testStartAndStopMonitoringLifecycle() {
        val manager = UserSessionManager(
            appContext = context,
            database = database
        )

        assertFalse(manager.isAuthTransitioning.value)

        // Should start and stop cleanly without exceptions
        manager.startMonitoring()
        manager.stopMonitoring()
    }

    @Test
    fun testAuthErrorHandler_producesArabicMessages() {
        val networkEx = com.google.firebase.FirebaseNetworkException("Network disconnected")
        val networkMsg = AuthErrorHandler.getReadableErrorMessage(networkEx)
        assertTrue(networkMsg.contains("الإنترنت"))

        val genericEx = RuntimeException("Connection timed out")
        val genericMsg = AuthErrorHandler.getReadableErrorMessage(genericEx)
        assertTrue(genericMsg.isNotBlank())
    }

    @Test
    fun testGranularReportPermissions() {
        val salesOnlyEmployee = UserAccountEntity(
            id = 202L,
            userCode = "EMP-202",
            fullName = "Sales Only",
            username = "sales_user",
            role = "كاشير",
            branch = "فرع الخرطوم",
            permissions = "التقارير,تقارير المبيعات",
            serverSyncToken = "SYNC-202"
        )

        // Should have access to sales report
        val perms = salesOnlyEmployee.permissions.split(",").map { it.trim() }.toSet()
        assertTrue(perms.contains("تقارير المبيعات"))
        assertFalse("Should not have raw materials report permission", perms.contains("تقارير المواد الخام"))
    }
}

package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserAccountEntity
import com.example.data.remote.CloudErrorCategory
import com.example.data.remote.CloudSyncErrorTracker
import com.example.data.remote.ServiceProbeId
import com.example.ui.screens.SyncDiagnosticDashboardView
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.EnterpriseRbacManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h1200dp")
class CloudSyncAuditTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `cloudSyncErrorTracker classifies all 8 server error categories cleanly`() {
    val permDenied = FirebaseFirestoreException(
      "PERMISSION_DENIED: Missing or insufficient permissions.",
      FirebaseFirestoreException.Code.PERMISSION_DENIED
    )
    assertEquals(CloudErrorCategory.PERMISSION_DENIED, CloudSyncErrorTracker.classifyThrowable(permDenied).category)
    assertFalse(CloudErrorCategory.PERMISSION_DENIED.isTransientRetryable)

    val unauth = FirebaseFirestoreException(
      "UNAUTHENTICATED: Missing auth token",
      FirebaseFirestoreException.Code.UNAUTHENTICATED
    )
    assertEquals(CloudErrorCategory.UNAUTHENTICATED, CloudSyncErrorTracker.classifyThrowable(unauth).category)

    val unavailable = FirebaseFirestoreException(
      "UNAVAILABLE: Service unavailable",
      FirebaseFirestoreException.Code.UNAVAILABLE
    )
    assertEquals(CloudErrorCategory.UNAVAILABLE, CloudSyncErrorTracker.classifyThrowable(unavailable).category)
    assertTrue(CloudErrorCategory.UNAVAILABLE.isTransientRetryable)

    val deadline = FirebaseFirestoreException(
      "DEADLINE_EXCEEDED: Request timed out",
      FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
    )
    assertEquals(CloudErrorCategory.DEADLINE_EXCEEDED, CloudSyncErrorTracker.classifyThrowable(deadline).category)
    assertEquals(
      CloudErrorCategory.NETWORK_ERROR,
      CloudSyncErrorTracker.classifyThrowable(SocketTimeoutException("connect timed out")).category
    )

    val resourceExhausted = FirebaseFirestoreException(
      "RESOURCE_EXHAUSTED: Quota exceeded",
      FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED
    )
    assertEquals(CloudErrorCategory.RESOURCE_EXHAUSTED, CloudSyncErrorTracker.classifyThrowable(resourceExhausted).category)

    val failedPrecondition = FirebaseFirestoreException(
      "FAILED_PRECONDITION: Query requires an index",
      FirebaseFirestoreException.Code.FAILED_PRECONDITION
    )
    assertEquals(CloudErrorCategory.FAILED_PRECONDITION, CloudSyncErrorTracker.classifyThrowable(failedPrecondition).category)

    assertEquals(
      CloudErrorCategory.NETWORK_ERROR,
      CloudSyncErrorTracker.classifyThrowable(UnknownHostException("firestore.googleapis.com")).category
    )
    assertEquals(
      CloudErrorCategory.NETWORK_ERROR,
      CloudSyncErrorTracker.classifyThrowable(FirebaseNetworkException("network error")).category
    )

    val conflict = FirebaseFirestoreException(
      "ABORTED: concurrent modification conflict",
      FirebaseFirestoreException.Code.ABORTED
    )
    assertEquals(CloudErrorCategory.DATA_CONFLICT, CloudSyncErrorTracker.classifyThrowable(conflict).category)
  }

  @Test
  fun `cloudSyncErrorTracker redacts sensitive tokens, api keys, and passwords from error messages`() {
    val dirty = "Auth failed for Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjMifQ.sig123456 " +
      "key=AIzaSyDalLI3Bg-vR0NXaw69a6nyx4IxBw2dINo&pinOrPassword=superSecret99!"
    val clean = CloudSyncErrorTracker.sanitizeCloudMessage(dirty)

    assertFalse("Bearer token must be redacted", clean.contains("sig123456"))
    assertFalse("API key must be redacted", clean.contains("AIzaSyDalLI3Bg"))
    assertFalse("PIN/password must be redacted", clean.contains("superSecret99"))
    assertTrue(clean.contains("[REDACTED]") || clean.contains("[REDACTED_API_KEY]"))
  }

  @Test
  fun `enterpriseRbacManager blocks self-modification, Manager-to-Manager edits, and Admin promotion`() {
    val superAdmin = UserAccountEntity(
      id = 1L,
      userCode = "USR-ADMIN",
      fullName = "مدير النظام",
      username = "admin",
      role = "مدير النظام",
      branch = "الخرطوم",
      permissions = EnterpriseRbacManager.PermissionTokens.ACTION_USER_MANAGEMENT,
      serverSyncToken = "TOKEN_ADMIN",
      active = true
    )
    val managerActor = UserAccountEntity(
      id = 2L,
      userCode = "MGR-001",
      fullName = "مدير التشغيل",
      username = "ops_mgr",
      role = "المدير",
      branch = "الخرطوم",
      permissions = "${EnterpriseRbacManager.PermissionTokens.ACTION_USER_MANAGEMENT},المبيعات",
      serverSyncToken = "TOKEN_MGR1",
      active = true
    )
    val otherManager = UserAccountEntity(
      id = 3L,
      userCode = "MGR-002",
      fullName = "مدير مصنع مصر",
      username = "egypt_mgr",
      role = "مدير فرع",
      branch = "القاهرة",
      permissions = "المصنع,الإنتاج",
      serverSyncToken = "TOKEN_MGR2",
      active = true
    )
    val employee = UserAccountEntity(
      id = 4L,
      userCode = "EMP-010",
      fullName = "موظف مبيعات",
      username = "sales_emp",
      role = "مسؤول مبيعات وفواتير",
      branch = "الخرطوم",
      permissions = "المبيعات,صلاحية العرض",
      serverSyncToken = "TOKEN_EMP1",
      active = true
    )

    // 1. Manager cannot edit self
    val selfEdit = EnterpriseRbacManager.configureEmployeePermissions(
      actor = managerActor,
      targetUser = managerActor,
      newRole = "مدير النظام",
      newPermissions = "المبيعات"
    )
    assertTrue("Manager must not modify own role/permissions", selfEdit.isFailure)

    // 2. Manager cannot edit another Manager
    val peerMgrEdit = EnterpriseRbacManager.configureEmployeePermissions(
      actor = managerActor,
      targetUser = otherManager,
      newRole = "موظف",
      newPermissions = "صلاحية العرض"
    )
    assertTrue("Manager must not modify another Manager", peerMgrEdit.isFailure)

    // 3. Manager cannot promote an Employee to Admin / Manager role
    val promoteRes = EnterpriseRbacManager.configureEmployeePermissions(
      actor = managerActor,
      targetUser = employee,
      newRole = "مدير النظام",
      newPermissions = "المبيعات"
    )
    assertTrue("Manager must not promote employee to Admin", promoteRes.isFailure)

    // 4. Manager cannot grant ADMIN_SETTINGS escalation token
    val tokenEscRes = EnterpriseRbacManager.configureEmployeePermissions(
      actor = managerActor,
      targetUser = employee,
      newRole = "مسؤول مبيعات وفواتير",
      newPermissions = "المبيعات,${EnterpriseRbacManager.PermissionTokens.ACTION_ADMIN_SETTINGS}"
    )
    assertTrue("Manager must not grant ADMIN_SETTINGS", tokenEscRes.isFailure)

    // 5. SuperAdmin CAN adjust another manager/employee cleanly
    val adminEdit = EnterpriseRbacManager.configureEmployeePermissions(
      actor = superAdmin,
      targetUser = employee,
      newRole = "محاسب",
      newPermissions = "المحاسبة,صلاحية العرض"
    )
    assertTrue("Primary SuperAdmin must succeed updating employee permissions", adminEdit.isSuccess)
  }

  @Test
  fun `syncDiagnosticDashboard shows unmeasured state honestly and renders read-only service probes`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = PmpViewModel(app)

    composeTestRule.setContent {
      MyApplicationTheme {
        SyncDiagnosticDashboardView(viewModel = viewModel)
      }
    }

    // Must NOT display fabricated 38 ms latency when serverHealthReport is null
    assertEquals(0, composeTestRule.onAllNodesWithText("38 ms", substring = true).fetchSemanticsNodes().size)
    // Must NOT contain DEV-NODE static placeholder in active error logs
    assertEquals(0, composeTestRule.onAllNodesWithText("DEV-NODE", substring = true).fetchSemanticsNodes().size)

    composeTestRule.onNodeWithTag("sync_dashboard_list").performScrollToNode(hasTestTag("service_probes_card"))
    composeTestRule.onNodeWithTag("service_probes_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("comprehensive_diagnostic_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("preflight_status_card").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("probe_network_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("probe_auth_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("probe_firestore_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("probe_rbac_btn").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("probe_updates_btn").performScrollTo().assertIsDisplayed()

    // Ensure all 5 enum probe IDs are exercised and preflight triggers cleanly
    assertEquals(5, ServiceProbeId.entries.size)
    assertTrue(viewModel.serverClusterCode.value.isNotBlank())
    viewModel.refreshPreflightCheck()
  }

  @Test
  fun `cloudErpSyncRepository builds deterministic email directory keys and canonical RBAC tiers`() {
    assertEquals(
      "EMAIL_oaw7ab_gmail_com",
      com.example.data.remote.CloudErpSyncRepository.buildEmailDirectoryKey("  OAW7AB@gmail.com ")
    )

    val admin = UserAccountEntity(
      id = 1L, userCode = "USR-ADMIN", fullName = "Admin", username = "admin",
      role = "مدير النظام", branch = "الخرطوم", permissions = "", serverSyncToken = "T", active = true
    )
    val manager = UserAccountEntity(
      id = 2L, userCode = "MGR-1", fullName = "Mgr", username = "mgr",
      role = "المدير", branch = "الخرطوم", permissions = "", serverSyncToken = "T", active = true
    )
    val accountant = UserAccountEntity(
      id = 3L, userCode = "ACC-1", fullName = "Acc", username = "acc",
      role = "المحاسب", branch = "الخرطوم", permissions = "", serverSyncToken = "T", active = true
    )
    val deputy = UserAccountEntity(
      id = 4L, userCode = "DEP-1", fullName = "Dep", username = "dep",
      role = "نائب محاسب", branch = "الخرطوم", permissions = "", serverSyncToken = "T", active = true
    )
    val employee = UserAccountEntity(
      id = 5L, userCode = "EMP-1", fullName = "Emp", username = "emp",
      role = "مسؤول مبيعات وفواتير", branch = "الخرطوم", permissions = "", serverSyncToken = "T", active = true
    )

    assertEquals("ADMIN", com.example.data.remote.CloudErpSyncRepository.resolveCanonicalRbacTier(admin))
    assertEquals("MANAGER", com.example.data.remote.CloudErpSyncRepository.resolveCanonicalRbacTier(manager))
    assertEquals("ACCOUNTANT", com.example.data.remote.CloudErpSyncRepository.resolveCanonicalRbacTier(accountant))
    assertEquals("DEPUTY_ACCOUNTANT", com.example.data.remote.CloudErpSyncRepository.resolveCanonicalRbacTier(deputy))
    assertEquals("EMPLOYEE", com.example.data.remote.CloudErpSyncRepository.resolveCanonicalRbacTier(employee))
  }
}

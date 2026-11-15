package com.example

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserAccountEntity
import com.example.ui.screens.LoginScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.BiometricPreferenceManager
import com.example.util.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("PMP Paints", appName)
  }

  @Test
  fun `viewModel and MainActivity initialize without crash`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = PmpViewModel(app)
    assertNotNull(viewModel.currentUser)
    assertNotNull(viewModel.cloudSyncState.value)

    val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
    assertNotNull(controller.get())
  }

  @Test
  fun `direct APK update permission is declared and canRequestPackageInstalls never throws`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val updateManager = UpdateManager(app)
    assertTrue(
      "REQUEST_INSTALL_PACKAGES must be declared so downloaded APKs install directly without Google Play redirect",
      updateManager.hasDeclaredRequestInstallPackagesPermission()
    )
    // Calling canRequestPackageInstalls must never throw SecurityException in Robolectric/Android
    updateManager.canRequestPackageInstalls()

    val viewModel = PmpViewModel(app)
    assertTrue(viewModel.hasDeclaredInstallPackagesPermission())
  }

  @Test
  fun `updateManager periodic check scheduling and snooze suppression behave cleanly`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val updateManager = UpdateManager(app)
    updateManager.clearUpdateNotificationSnooze()

    val baseTime = 1_780_000_000_000L
    // Before recording any check timestamp, a periodic check should be due
    assertTrue(
      "Periodic check must run when no prior check timestamp exists",
      updateManager.shouldRunPeriodicCheck(nowMillis = baseTime)
    )

    updateManager.recordPeriodicCheckTimestamp(baseTime)
    assertFalse(
      "Periodic check must not re-run immediately before interval elapses",
      updateManager.shouldRunPeriodicCheck(
        nowMillis = baseTime + (UpdateManager.DEFAULT_PERIODIC_CHECK_INTERVAL_MS / 2)
      )
    )
    assertTrue(
      "Periodic check must run again once interval elapses",
      updateManager.shouldRunPeriodicCheck(
        nowMillis = baseTime + UpdateManager.DEFAULT_PERIODIC_CHECK_INTERVAL_MS + 1000L
      )
    )

    // Non-mandatory snooze should suppress duplicate prompts for the same version until expiry or newer version
    updateManager.snoozeUpdateNotification(
      versionCode = 22,
      durationMs = UpdateManager.DEFAULT_NOTIFICATION_SNOOZE_MS,
      nowMillis = baseTime
    )
    assertTrue(
      "Snoozed version 22 must be suppressed inside snooze window",
      updateManager.isUpdateNotificationSnoozed(22, nowMillis = baseTime + 60_000L)
    )
    assertFalse(
      "Newer version 23 must NOT be suppressed by snooze on version 22",
      updateManager.isUpdateNotificationSnoozed(23, nowMillis = baseTime + 60_000L)
    )
    assertFalse(
      "Snooze must expire cleanly after DEFAULT_NOTIFICATION_SNOOZE_MS",
      updateManager.isUpdateNotificationSnoozed(
        22,
        nowMillis = baseTime + UpdateManager.DEFAULT_NOTIFICATION_SNOOZE_MS + 1000L
      )
    )
    updateManager.clearUpdateNotificationSnooze()
  }

  @Test
  fun `loginScreen hides bound biometric user details and renders circular fingerprint icon`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val boundUser = UserAccountEntity(
      id = 1L,
      userCode = "USR-PMP-001",
      fullName = "Omar AbdAlwhab",
      username = "omar",
      phoneOrEmail = "omar@pmp.com",
      pinOrPassword = "hash",
      role = "مدير النظام",
      branch = "فرع السودان (SD-001)",
      permissions = "المبيعات,الخزن",
      serverSyncToken = "SYNC-PMP-001",
      active = true
    )
    BiometricPreferenceManager.bindUser(app, boundUser)
    val viewModel = PmpViewModel(app)

    composeTestRule.setContent {
      MyApplicationTheme {
        LoginScreen(
          viewModel = viewModel,
          onLoginSuccess = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("login_submit_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("biometric_login_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithText("Omar AbdAlwhab", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("USR-PMP-001", substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithText("البصمة مرتبطة بحساب", substring = true).assertDoesNotExist()

    BiometricPreferenceManager.clearBinding(app)
  }

  @Test
  fun `glassBottomNavigationBar renders floating tabs with badges and handles selection`() {
    val screens = listOf(
      PmpScreen.DASHBOARD,
      PmpScreen.TREASURY,
      PmpScreen.SALES,
      PmpScreen.INVENTORY,
      PmpScreen.SETTINGS
    )
    var selectedScreen: PmpScreen? = null

    composeTestRule.setContent {
      MyApplicationTheme {
        GlassBottomNavigationBar(
          allowedScreens = screens,
          currentScreen = PmpScreen.TREASURY,
          badgeCounts = mapOf(PmpScreen.SETTINGS to 3),
          onScreenSelected = { selectedScreen = it }
        )
      }
    }

    composeTestRule.onNodeWithTag("nav_tab_dashboard").assertIsDisplayed()
    composeTestRule.onNodeWithTag("nav_tab_treasury").assertIsDisplayed()
    composeTestRule.onNodeWithTag("nav_tab_inventory").assertIsDisplayed()
    composeTestRule.onNodeWithText("3").assertIsDisplayed()

    composeTestRule.onNodeWithTag("nav_tab_inventory").performClick()
    assertEquals(PmpScreen.INVENTORY, selectedScreen)
  }
}

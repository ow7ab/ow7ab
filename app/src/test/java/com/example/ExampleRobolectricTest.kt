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
  fun `canRequestPackageInstalls never throws SecurityException without REQUEST_INSTALL_PACKAGES`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val updateManager = UpdateManager(app)
    assertFalse(
      "REQUEST_INSTALL_PACKAGES should remain undeclared for Play Policy compliance",
      updateManager.hasDeclaredRequestInstallPackagesPermission()
    )
    assertFalse(
      "canRequestPackageInstalls must return false safely without throwing SecurityException",
      updateManager.canRequestPackageInstalls()
    )

    val viewModel = PmpViewModel(app)
    assertFalse(viewModel.hasDeclaredInstallPackagesPermission())
    assertFalse(viewModel.canRequestPackageInstalls(null))
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

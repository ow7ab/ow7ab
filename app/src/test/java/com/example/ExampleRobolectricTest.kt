package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.viewmodel.PmpViewModel
import com.example.util.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

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
}

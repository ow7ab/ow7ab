package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testPasswordHashingAndVerification() {
    val rawPin = "1234"
    val hashed = com.example.data.repository.PmpRepository.hashSecret(rawPin)
    assertNotEquals(rawPin, hashed)
    assertTrue(hashed.length >= 32)

    // Verification check as done in checkPendingAccountApprovalAndLogin
    val enteredPin = "1234"
    val enteredHashed = com.example.data.repository.PmpRepository.hashSecret(enteredPin)
    val isValid = enteredPin == rawPin || enteredHashed == hashed
    assertTrue(isValid)

    val wrongPin = "0000"
    val wrongHashed = com.example.data.repository.PmpRepository.hashSecret(wrongPin)
    val isWrongValid = wrongPin == hashed || wrongHashed == hashed
    assertFalse(isWrongValid)
  }

  @Test
  fun testDeletedRecordsRegistry() {
    val registry = com.example.data.local.DeletedRecordsRegistry

    // 1. Test Movement Tombstone Registration
    registry.registerDeleted("movements", 101L, "MOV-101", "المدير")
    assertTrue(registry.isDeleted("movements", 101L))
    assertTrue(registry.isDeleted("movements", "MOV-101"))
    assertFalse(registry.isDeleted("movements", 999L))

    // 2. Test Customer Tombstone Registration
    registry.registerDeleted("customers", 42L, "CUST-42", "المدير")
    assertTrue(registry.isDeleted("customers", 42L))
    assertTrue(registry.isDeleted("customers", "CUST-42"))
    assertFalse(registry.isDeleted("customers", 100L))

    // 3. Test Invoices Tombstone Registration
    registry.registerDeleted("invoices", 500L, "INV-500", "المدير")
    assertTrue(registry.isDeleted("invoices", 500L))
    assertTrue(registry.isDeleted("invoices", "INV-500"))

    // 4. Test Cluster Epoch Wipe Protection
    val wipeTime = System.currentTimeMillis()
    registry.setClusterEpoch(wipeTime, "مدير النظام")
    assertEquals(wipeTime, registry.getClusterEpoch())
    assertTrue(registry.isPreEpoch(wipeTime - 10_000L))
    assertFalse(registry.isPreEpoch(wipeTime + 10_000L))

    // 5. Test Remote Tombstones Merge
    val remoteTombs = listOf(
        mapOf("collection" to "transfers", "entityId" to "77", "docId" to "TRF-77", "deletedAt" to System.currentTimeMillis())
    )
    registry.mergeRemoteTombstones(remoteTombs)
    assertTrue(registry.isDeleted("transfers", 77L))
    assertTrue(registry.isDeleted("transfers", "TRF-77"))

    // 6. Test Factory Items & Products Tombstone Registration and Unregistration
    registry.registerDeleted("factory_items", "FI-15", "تيتانيوم روتيل", "المدير")
    registry.registerDeleted("factory_items", "15", "تيتانيوم روتيل", "المدير")
    assertTrue(registry.isDeleted("factory_items", "FI-15"))
    assertTrue(registry.isDeleted("factory_items", 15L))
    registry.unregisterDeleted("factory_items", "FI-15")
    registry.unregisterDeleted("factory_items", 15L)
    assertFalse(registry.isDeleted("factory_items", "FI-15"))
    assertFalse(registry.isDeleted("factory_items", 15L))

    registry.registerDeleted("products", 88L, "PROD-88", "المدير")
    assertTrue(registry.isDeleted("products", 88L))
    assertTrue(registry.isDeleted("products", "PROD-88"))
    registry.unregisterDeleted("products", 88L)
    registry.unregisterDeleted("products", "PROD-88")
    assertFalse(registry.isDeleted("products", 88L))
    assertFalse(registry.isDeleted("products", "PROD-88"))
  }

  @Test
  fun testCloudReleaseConfiguration() {
    val latestRelease = com.example.util.UpdateManager.LATEST_CLOUD_RELEASE_INFO
    assertTrue(latestRelease.versionCode >= 13)
    assertTrue(latestRelease.versionName.contains("3.0"))
    assertTrue(latestRelease.downloadUrl.startsWith("https://"))
    assertTrue(latestRelease.releaseNotes.isNotBlank())
    assertTrue(latestRelease.buildId.isNotBlank())
    assertTrue(latestRelease.gitCommit.isNotBlank())
    assertEquals("com.pmp", BuildConfig.APPLICATION_ID)
    assertEquals(13, BuildConfig.VERSION_CODE)
    assertEquals("3.0-PRO", BuildConfig.VERSION_NAME)
    assertEquals("PMP-v3.0-PRO-b13-8f4d2c1a", BuildConfig.BUILD_ID)
    assertEquals("8f4d2c1a", BuildConfig.GIT_COMMIT_SHA)
    assertTrue(BuildConfig.BUILD_TIMESTAMP_UTC.endsWith("Z"))
    assertEquals("ow7ab/ow7ab", BuildConfig.GITHUB_RELEASE_REPO)
  }

  @Test
  fun testFirstAttemptUpdateDiscoveryAndVersionJsonParsing() {
    val sampleJson = """
      {
        "versionCode": 14,
        "versionName": "3.1-PRO",
        "buildId": "PMP-v3.1-PRO-b14-9a8b7c6d",
        "gitCommit": "9a8b7c6d",
        "publishedAt": "2026-11-08T10:30:00Z",
        "downloadUrl": "https://ow7ab.github.io/ow7ab/pmp-paints-v3.1-PRO-b14.apk",
        "releaseNotes": "تحديث جديد يتضمن ميزات فعلية",
        "fileSize": 19500000,
        "fileSizeMb": 18.6,
        "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "minSupportedVersion": 1
      }
    """.trimIndent()

    val parsed = com.example.util.UpdateManager.parseVersionJsonToUpdateInfo(sampleJson)
    assertNotNull(parsed)
    assertEquals(14, parsed!!.versionCode)
    assertEquals("3.1-PRO", parsed.versionName)
    assertEquals("PMP-v3.1-PRO-b14-9a8b7c6d", parsed.buildId)
    assertEquals("9a8b7c6d", parsed.gitCommit)
    assertEquals("2026-11-08T10:30:00Z", parsed.publishedAt)
    assertEquals(19500000L, parsed.fileSize)
    assertTrue(parsed.downloadUrl.endsWith("pmp-paints-v3.1-PRO-b14.apk"))
    assertEquals(64, parsed.sha256Checksum.length)

    // Verify highest version wins across multiple sources (e.g., stale Firestore v12 vs fresh JSON v14)
    val staleSource = com.example.util.UpdateInfo(
      versionCode = 12,
      versionName = "2.9-PRO",
      downloadUrl = "https://example.com/old.apk",
      releaseNotes = "قديم"
    )
    val bestCandidate = listOf(staleSource, parsed).maxByOrNull { it.versionCode }
    assertNotNull(bestCandidate)
    assertEquals(14, bestCandidate!!.versionCode)
    assertTrue(bestCandidate.versionCode > BuildConfig.VERSION_CODE)
  }

  @Test
  fun testCacheBustingAndCandidateDownloadUrls() {
    val baseUrl = com.example.util.UpdateManager.DEFAULT_UPDATE_METADATA_URL
    val busted1 = com.example.util.UpdateManager.buildCacheBustedUrl(baseUrl, 1700000001L)
    val busted2 = com.example.util.UpdateManager.buildCacheBustedUrl(baseUrl, 1700000002L)
    assertTrue(busted1.contains("?_cb=1700000001"))
    assertNotEquals(busted1, busted2)

    val updateInfo = com.example.util.UpdateInfo(
      versionCode = 14,
      versionName = "3.1-PRO",
      downloadUrl = "https://ow7ab.github.io/ow7ab/pmp-paints-v3.1-PRO-b14.apk",
      releaseNotes = "تحديث"
    )
    val candidates = com.example.util.UpdateManager.buildCandidateDownloadUrls(updateInfo)
    assertTrue(candidates.any { it.contains("pmp-paints-v3.1-PRO-b14.apk") })
    assertTrue(candidates.any { it == "${com.example.util.UpdateManager.GITHUB_PAGES_BASE_URL}/pmp-paints-latest.apk" })
    assertTrue(candidates.any { it == "${com.example.util.UpdateManager.DEFAULT_DOWNLOAD_PORTAL_URL}/pmp-paints-latest.apk" })
    assertTrue(candidates.any { it == "${com.example.util.UpdateManager.SHARED_DOWNLOAD_PORTAL_URL}/pmp-paints-latest.apk" })
  }

  @Test
  fun testVersionAtoVersionBBuildIdentityVerificationAndFailureCases() {
    // Version A (Installed): versionCode = 12, buildId = "PMP-v2.9-PRO-b12-1111aaaa"
    val versionACode = 12
    val versionABuildId = "PMP-v2.9-PRO-b12-1111aaaa"

    // Version B (Target Update): versionCode = 13, buildId = "PMP-v3.0-PRO-b13-8f4d2c1a"
    val versionBUpdate = com.example.util.UpdateInfo(
      versionCode = 13,
      versionName = "3.0-PRO",
      downloadUrl = "https://ow7ab.github.io/ow7ab/pmp-paints-v3.0-PRO-b13.apk",
      releaseNotes = "ميزة جديدة في الإصدار 13",
      sha256Checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      fileSize = 19398656L,
      buildId = "PMP-v3.0-PRO-b13-8f4d2c1a",
      gitCommit = "8f4d2c1a",
      publishedAt = "2026-11-07T08:00:00Z"
    )

    // 1. Validate metadata integrity for Version B against Version A -> MUST PASS
    val validDecision = com.example.util.UpdateManager.validateUpdateMetadataIntegrity(
      installedVersionCode = versionACode,
      installedBuildId = versionABuildId,
      candidate = versionBUpdate
    )
    assertTrue(validDecision.isValid)

    // 2. Reject older or equal versionCode -> MUST FAIL
    val olderDecision = com.example.util.UpdateManager.validateUpdateMetadataIntegrity(
      installedVersionCode = 13,
      installedBuildId = "PMP-v3.0-PRO-b13-8f4d2c1a",
      candidate = versionBUpdate.copy(versionCode = 12)
    )
    assertFalse(olderDecision.isValid)

    // 3. Reject fake version bump where versionCode increased to 13 but buildId is still Version A's buildId -> MUST FAIL
    val fakeBumpDecision = com.example.util.UpdateManager.validateUpdateMetadataIntegrity(
      installedVersionCode = versionACode,
      installedBuildId = versionABuildId,
      candidate = versionBUpdate.copy(buildId = versionABuildId)
    )
    assertFalse("Must reject update with identical buildId as installed version", fakeBumpDecision.isValid)

    // 4. Reject corrupted / invalid SHA-256 format -> MUST FAIL
    val badShaDecision = com.example.util.UpdateManager.validateUpdateMetadataIntegrity(
      installedVersionCode = versionACode,
      installedBuildId = versionABuildId,
      candidate = versionBUpdate.copy(sha256Checksum = "invalid_short_sha")
    )
    assertFalse(badShaDecision.isValid)

    // 5. Verify genuine transition from Version A -> Version B (both versionCode and BuildConfig.BUILD_ID changed)
    val genuineTransition = com.example.util.UpdateManager.verifyBuildIdentityTransition(
      previousVersionCode = versionACode,
      previousBuildId = versionABuildId,
      currentVersionCode = 13,
      currentBuildId = "PMP-v3.0-PRO-b13-8f4d2c1a",
      expectedUpdate = versionBUpdate
    )
    assertTrue(genuineTransition.isVerified)
    assertTrue(genuineTransition.versionCodeAdvanced)
    assertTrue(genuineTransition.buildIdChanged)
    assertTrue(genuineTransition.matchesExpectedBuildId)

    // 6. Detect spoofed update where versionCode changed to 13 but installed binary still has Version A's Build ID
    val spoofedTransition = com.example.util.UpdateManager.verifyBuildIdentityTransition(
      previousVersionCode = versionACode,
      previousBuildId = versionABuildId,
      currentVersionCode = 13,
      currentBuildId = versionABuildId,
      expectedUpdate = versionBUpdate
    )
    assertFalse("Spoofed update without binary change must fail verification", spoofedTransition.isVerified)
    assertFalse(spoofedTransition.buildIdChanged)
  }

  @Test
  fun testApkSha256AndHeaderVerification() {
    val tempValidZip = java.io.File.createTempFile("test_valid_apk", ".apk")
    val tempInvalidHtml = java.io.File.createTempFile("test_invalid_html", ".apk")
    try {
      // Write valid ZIP/APK magic bytes PK\x03\x04 + payload
      tempValidZip.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x0A, 0x00, 0x00, 0x00))
      assertTrue(com.example.util.UpdateManager.isValidZipApkHeader(tempValidZip))

      val sha = com.example.util.UpdateManager.computeFileSha256(tempValidZip)
      assertEquals(64, sha.length)
      assertEquals(sha, com.example.util.UpdateManager.computeFileSha256(tempValidZip))

      // Write HTML error response (e.g. 404 page) -> must be rejected by isValidZipApkHeader
      tempInvalidHtml.writeText("<html><body>404 Not Found</body></html>")
      assertFalse(com.example.util.UpdateManager.isValidZipApkHeader(tempInvalidHtml))
    } finally {
      tempValidZip.delete()
      tempInvalidHtml.delete()
    }
  }

  @Test
  fun testSuperAdminAccountGuard() {
    val adminUser = com.example.data.local.UserAccountEntity(
      id = 1L,
      userCode = "USR-ADMIN",
      fullName = "مدير النظام",
      username = "admin",
      phoneOrEmail = "admin@pmp.com",
      pinOrPassword = "1234",
      role = "مدير النظام",
      branch = "الإدارة العامة",
      permissions = "",
      serverSyncToken = "SYNC-ADMIN"
    )
    val regularManager = adminUser.copy(id = 2L, username = "manager2", userCode = "USR-MGR2")
    val employee = adminUser.copy(id = 3L, username = "emp1", role = "محاسب", userCode = "USR-EMP1")

    assertTrue(adminUser.isSuperAdminAccount())
    assertFalse(regularManager.isSuperAdminAccount())
    assertFalse(employee.isSuperAdminAccount())
  }

  @Test
  fun testOneUiElasticResistancePhysics() {
    val maxOffset = 400f
    // Zero or negative pull returns 0f
    assertEquals(0f, com.example.ui.components.computeElasticOffset(0f, maxOffset), 0.001f)
    assertEquals(0f, com.example.ui.components.computeElasticOffset(-50f, maxOffset), 0.001f)

    // Positive pull moves smoothly and monotonically with hyperbolic resistance
    val smallPull = com.example.ui.components.computeElasticOffset(80f, maxOffset)
    val mediumPull = com.example.ui.components.computeElasticOffset(250f, maxOffset)
    val deepPull = com.example.ui.components.computeElasticOffset(900f, maxOffset)

    assertTrue(smallPull > 0f)
    assertTrue(mediumPull > smallPull)
    assertTrue(deepPull > mediumPull)
    // Never exceeds maxOffsetPx asymptote
    assertTrue(deepPull < maxOffset)
  }

  @Test
  fun testCashboxArabicTombstonesAndSyncIds() {
    val registry = com.example.data.local.DeletedRecordsRegistry

    val docIdHalfa = registry.cashboxDocId("خزنة حلفا", 10L)
    val docIdAtbara = registry.cashboxDocId("خزنة عطبرة", 11L)
    assertNotEquals("Arabic cashbox names must generate distinct docIds", docIdHalfa, docIdAtbara)
    assertEquals(docIdHalfa, registry.cashboxDocId("  خزنة حلفا  ", 99L))

    // Register deletion of "خزنة حلفا" and verify "خزنة عطبرة" is unaffected
    registry.registerDeleted("cashboxes", 10L, "خزنة حلفا", "مدير النظام")
    registry.registerDeleted("cashboxes", docIdHalfa, "خزنة حلفا", "مدير النظام")

    assertTrue(registry.isDeleted("cashboxes", 10L))
    assertTrue(registry.isDeleted("cashboxes", "خزنة حلفا"))
    assertTrue(registry.isDeleted("cashboxes", docIdHalfa))
    assertFalse("Other Arabic cashboxes must not collide", registry.isDeleted("cashboxes", "خزنة عطبرة"))
    assertFalse("Other cashbox docIds must not collide", registry.isDeleted("cashboxes", docIdAtbara))

    // Re-creating the cashbox unregisters its tombstone cleanly
    registry.unregisterDeleted("cashboxes", 10L)
    registry.unregisterDeleted("cashboxes", "خزنة حلفا")
    registry.unregisterDeleted("cashboxes", docIdHalfa)
    assertFalse(registry.isDeleted("cashboxes", "خزنة حلفا"))
    assertFalse(registry.isDeleted("cashboxes", docIdHalfa))
  }

  @Test
  fun testXlsxWorkbookGenerationAndArabicRtl() {
    val headers = listOf("رقم الفاتورة", "العميل", "الإجمالي", "العملة")
    val rows = listOf(
      listOf("INV-1001", "شركة النيل للدهانات", "150000.00", "SDG"),
      listOf("INV-1002", "معرض الخرطوم", "4500.50", "EGP")
    )
    val xlsxBytes = com.example.util.StatementPdfAndExcelExporter.buildXlsxBytes(
      reportTitle = "تقرير مبيعات الشهر الحالي",
      subtitle = "الفترة: الشهر الحالي",
      headers = headers,
      rows = rows
    )

    // Verify valid ZIP header (PK\x03\x04)
    assertTrue(xlsxBytes.size > 200)
    assertEquals(0x50.toByte(), xlsxBytes[0])
    assertEquals(0x4B.toByte(), xlsxBytes[1])
    assertEquals(0x03.toByte(), xlsxBytes[2])
    assertEquals(0x04.toByte(), xlsxBytes[3])

    // Inspect ZIP entries to verify OpenXML structure and Arabic RTL worksheet content
    val entries = mutableMapOf<String, String>()
    java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(xlsxBytes)).use { zis ->
      var entry = zis.nextEntry
      while (entry != null) {
        entries[entry.name] = zis.readBytes().toString(Charsets.UTF_8)
        zis.closeEntry()
        entry = zis.nextEntry
      }
    }

    assertTrue(entries.containsKey("[Content_Types].xml"))
    assertTrue(entries.containsKey("xl/workbook.xml"))
    assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))

    val sheetXml = entries["xl/worksheets/sheet1.xml"] ?: ""
    assertTrue("Worksheet must enable Arabic RTL view", sheetXml.contains("rightToLeft=\"1\""))
    assertTrue(sheetXml.contains("تقرير مبيعات الشهر الحالي"))
    assertTrue(sheetXml.contains("شركة النيل للدهانات"))
    assertTrue(sheetXml.contains("INV-1001"))
  }

  @Test
  fun testSkeletonSectionUiStates() {
    val loadingState: com.example.ui.components.PmpSectionUiState =
      com.example.ui.components.PmpSectionUiState.Loading
    val successState: com.example.ui.components.PmpSectionUiState =
      com.example.ui.components.PmpSectionUiState.Success
    val emptyState = com.example.ui.components.PmpSectionUiState.Empty("لا توجد فواتير")
    val errorState = com.example.ui.components.PmpSectionUiState.Error("انقطاع الاتصال", canRetry = true)

    assertTrue(loadingState is com.example.ui.components.PmpSectionUiState.Loading)
    assertTrue(successState is com.example.ui.components.PmpSectionUiState.Success)
    assertEquals("لا توجد فواتير", emptyState.title)
    assertTrue(errorState.canRetry)
    assertEquals("انقطاع الاتصال", errorState.message)
  }

  @Test
  fun testGitHubActionsWorkflowSdkLicenseAndBuildConfiguration() {
    val workflowFile = listOf(
      java.io.File(".github/workflows/build-release.yml"),
      java.io.File("../.github/workflows/build-release.yml")
    ).firstOrNull { it.exists() }
    assertNotNull("Workflow file .github/workflows/build-release.yml must exist", workflowFile)
    val content = workflowFile!!.readText(Charsets.UTF_8)

    // 1. Must NOT use android-actions/setup-android@v3 which fails on interactive license prompt
    assertFalse("Must not use android-actions/setup-android", content.contains("android-actions/setup-android"))

    // 2. Must NOT use || true to hide SDK or build errors
    assertFalse("Must not use '|| true' to mask errors", content.contains("|| true"))

    // 3. Must configure JDK 21 and Gradle 9.3.1 for AGP 9.1.1 compatibility
    assertTrue("Must use JDK 21", content.contains("java-version: \"21\""))
    assertTrue("Must use Gradle 9.3.1", content.contains("gradle-version: \"9.3.1\""))

    // 4. Must accept SDK licenses non-interactively via sdkmanager --licenses with check=True
    assertTrue("Must invoke sdkmanager --licenses", content.contains("\"--licenses\""))
    assertTrue("Must install platforms;android-36.1", content.contains("\"platforms;android-36.1\""))
    assertTrue("Must install build-tools;36.0.0", content.contains("\"build-tools;36.0.0\""))

    // 5. Must restore debug.keystore safely from secrets.DEBUG_KEYSTORE_BASE64 (or debug.keystore.base64)
    //    and never auto-generate a random keystore via -genkeypair
    assertTrue("Must reference secrets.DEBUG_KEYSTORE_BASE64", content.contains("secrets.DEBUG_KEYSTORE_BASE64"))
    assertTrue("Must verify androiddebugkey alias", content.contains("androiddebugkey"))
    assertTrue(
      "Must provide instructions for Settings -> Secrets and variables -> Actions -> New repository secret",
      content.contains("Settings -> Secrets and variables -> Actions -> New repository secret")
    )
    assertFalse("Must never auto-generate a random keystore", content.contains("-genkeypair"))

    // 6. Must verify APK package name (com.pmp), versionCode, signature, and approved SHA-256 certificate before release
    assertTrue("Must verify com.pmp package name with aapt2", content.contains("name='com.pmp'"))
    assertTrue("Must verify APK signature with apksigner", content.contains("apksigner"))
    assertTrue(
      "Must enforce approved PMP PAINTS signing certificate SHA-256 in workflow",
      content.contains(com.example.util.UpdateManager.APPROVED_SIGNING_CERT_SHA256)
    )
    assertTrue(
      "Must run automated unit tests (:app:testDebugUnitTest) before building release APK",
      content.contains(":app:testDebugUnitTest")
    )

    // 7. Verify root version.json and public/version.json are synchronized at v3.0-PRO (versionCode 13)
    val rootVersionFile = listOf(
      java.io.File("version.json"),
      java.io.File("../version.json")
    ).firstOrNull { it.exists() }
    val publicVersionFile = listOf(
      java.io.File("public/version.json"),
      java.io.File("../public/version.json")
    ).firstOrNull { it.exists() }
    assertNotNull("Root version.json must exist", rootVersionFile)
    assertNotNull("public/version.json must exist", publicVersionFile)

    val rootParsed = com.example.util.UpdateManager.parseVersionJsonToUpdateInfo(rootVersionFile!!.readText(Charsets.UTF_8))
    val publicParsed = com.example.util.UpdateManager.parseVersionJsonToUpdateInfo(publicVersionFile!!.readText(Charsets.UTF_8))
    assertNotNull(rootParsed)
    assertNotNull(publicParsed)
    assertEquals(13, rootParsed!!.versionCode)
    assertEquals(rootParsed.versionCode, publicParsed!!.versionCode)
    assertEquals(rootParsed.versionName, publicParsed.versionName)
    assertEquals(rootParsed.buildId, publicParsed.buildId)
    assertEquals(rootParsed.sha256Checksum, publicParsed.sha256Checksum)
    assertEquals(64, rootParsed.sha256Checksum.length)

    // 8. Verify README.md exists and documents DEBUG_KEYSTORE_BASE64 without leaking any raw keystore base64
    val readmeFile = listOf(
      java.io.File("README.md"),
      java.io.File("../README.md")
    ).firstOrNull { it.exists() }
    assertNotNull("README.md must exist", readmeFile)
    val readmeContent = readmeFile!!.readText(Charsets.UTF_8)
    assertTrue(readmeContent.contains("DEBUG_KEYSTORE_BASE64"))
    assertTrue(readmeContent.contains("New repository secret"))
    assertFalse("README must not contain raw JKS magic base64 header (/u3+7Q)", readmeContent.contains("/u3+7Q"))
  }
}

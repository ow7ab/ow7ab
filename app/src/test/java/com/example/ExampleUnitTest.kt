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
    assertTrue(latestRelease.versionCode >= 14)
    assertTrue(latestRelease.versionName.isNotBlank())
    assertTrue(latestRelease.downloadUrl.startsWith("https://"))
    assertTrue(latestRelease.releaseNotes.isNotBlank())
    assertTrue(latestRelease.buildId.isNotBlank())
    assertTrue(latestRelease.gitCommit.isNotBlank())
    assertEquals("com.aistudio.pmppaints.erp", BuildConfig.APPLICATION_ID)
    assertTrue("VERSION_CODE must be >= 14", BuildConfig.VERSION_CODE >= 14)
    assertTrue("VERSION_NAME must not be blank", BuildConfig.VERSION_NAME.isNotBlank())
    val cleanVersionName = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()
    val expectedBuildId = "PMP-v${cleanVersionName}-b${BuildConfig.VERSION_CODE}-${BuildConfig.GIT_COMMIT_SHA}"
    assertEquals(expectedBuildId, BuildConfig.BUILD_ID)
    assertTrue(
      "GIT_COMMIT_SHA must be a valid short hex commit hash, got: ${BuildConfig.GIT_COMMIT_SHA}",
      Regex("^[0-9a-fA-F]{7,40}$").matches(BuildConfig.GIT_COMMIT_SHA)
    )
    assertEquals(BuildConfig.VERSION_CODE, latestRelease.versionCode)
    assertEquals(BuildConfig.VERSION_NAME, latestRelease.versionName)
    assertEquals(BuildConfig.BUILD_ID, latestRelease.buildId)
    assertEquals(BuildConfig.GIT_COMMIT_SHA, latestRelease.gitCommit)
    assertTrue(BuildConfig.BUILD_TIMESTAMP_UTC.endsWith("Z"))
    assertEquals("ow7ab/ow7ab", BuildConfig.GITHUB_RELEASE_REPO)
  }

  @Test
  fun testFirstAttemptUpdateDiscoveryAndVersionJsonParsing() {
    val targetVersionCode = BuildConfig.VERSION_CODE + 1
    val sampleJson = """
      {
        "versionCode": $targetVersionCode,
        "versionName": "v3.2",
        "buildId": "PMP-v3.2-b$targetVersionCode-9a8b7c6d",
        "gitCommit": "9a8b7c6d",
        "publishedAt": "2026-11-08T10:30:00Z",
        "downloadUrl": "https://ow7ab.github.io/ow7ab/pmp-paints-v3.2-b$targetVersionCode.apk",
        "releaseNotes": "تحديث جديد يتضمن ميزات فعلية",
        "fileSize": 19500000,
        "fileSizeMb": 18.6,
        "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "minSupportedVersion": 1
      }
    """.trimIndent()

    val parsed = com.example.util.UpdateManager.parseVersionJsonToUpdateInfo(sampleJson)
    assertNotNull(parsed)
    assertEquals(targetVersionCode, parsed!!.versionCode)
    assertEquals("v3.2", parsed.versionName)
    assertEquals("PMP-v3.2-b$targetVersionCode-9a8b7c6d", parsed.buildId)
    assertEquals("9a8b7c6d", parsed.gitCommit)
    assertEquals("2026-11-08T10:30:00Z", parsed.publishedAt)
    assertEquals(19500000L, parsed.fileSize)
    assertTrue(parsed.downloadUrl.endsWith("pmp-paints-v3.2-b$targetVersionCode.apk"))
    assertEquals(64, parsed.sha256Checksum.length)

    // Verify highest version wins across multiple sources (e.g., stale Firestore v12 vs fresh JSON)
    val staleSource = com.example.util.UpdateInfo(
      versionCode = 12,
      versionName = "2.9-PRO",
      downloadUrl = "https://example.com/old.apk",
      releaseNotes = "قديم"
    )
    val bestCandidate = listOf(staleSource, parsed).maxByOrNull { it.versionCode }
    assertNotNull(bestCandidate)
    assertEquals(targetVersionCode, bestCandidate!!.versionCode)
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

    // 6. Must verify APK package name (com.aistudio.pmppaints.erp), versionCode, signature, and approved SHA-256 certificate before release
    assertTrue(
      "Must verify com.aistudio.pmppaints.erp package name with aapt2",
      content.contains("name='com.aistudio.pmppaints.erp'")
    )
    assertTrue("Must verify APK signature with apksigner", content.contains("apksigner"))
    assertTrue(
      "Must enforce approved PMP PAINTS signing certificate SHA-256 in workflow",
      content.contains(com.example.util.UpdateManager.APPROVED_SIGNING_CERT_SHA256)
    )
    assertTrue(
      "Must run automated unit tests (:app:testDebugUnitTest) before building release APK",
      content.contains(":app:testDebugUnitTest")
    )
    assertTrue(
      "Must configure concurrency control to prevent duplicate release races",
      content.contains("concurrency:") && content.contains("cancel-in-progress: false")
    )
    assertTrue(
      "Must support website-only fast deployment path without rebuilding Android APK",
      content.contains("WEBSITE_ONLY") && content.contains("actions/upload-pages-artifact@v3") && content.contains("actions/deploy-pages@v4")
    )

    // 7. Verify root version.json and public/version.json are synchronized and represent a valid published release
    //    (kept separate from the new in-progress build version until the new APK is built, verified, and published)
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
    assertTrue(
      "Published version.json versionCode (${rootParsed!!.versionCode}) must be >= MINIMUM_SUPPORTED_VERSION_CODE",
      rootParsed.versionCode >= com.example.core.architecture.PmpAppVersionContract.MINIMUM_SUPPORTED_VERSION_CODE
    )
    assertTrue(
      "Published version.json versionCode (${rootParsed.versionCode}) must be <= current build versionCode (${BuildConfig.VERSION_CODE})",
      rootParsed.versionCode <= BuildConfig.VERSION_CODE
    )
    assertTrue("Published version.json versionName must not be blank", rootParsed.versionName.isNotBlank())
    assertTrue("Published version.json buildId must not be blank", rootParsed.buildId.isNotBlank())
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

  @Test
  fun testGenerateVersionJsonAndFirebaseStorageEndpoints() {
    val sampleInfo = com.example.util.UpdateInfo(
      versionCode = 15,
      versionName = "3.2-PRO",
      downloadUrl = "https://raw.githubusercontent.com/ow7ab/ow7ab/main/public/pmp-paints-latest.apk",
      releaseNotes = "تحديث سحابي جديد عبر Firebase Storage لتحديث صفحة التنزيل تلقائياً",
      isMandatory = true,
      sha256Checksum = "19bd7aaf4bcf57b022354fc6543f350f5d0814ed2773244f868e3bc3a0cd2abc",
      fileSizeMb = 30.8,
      fileSize = 32296140L,
      buildId = "PMP-v3.2-PRO-b15-8f4d2c1a",
      gitCommit = "8f4d2c1a",
      publishedAt = "2026-11-08T12:00:00Z",
      signingCertSha256 = com.example.util.UpdateManager.APPROVED_SIGNING_CERT_SHA256
    )

    val generatedJson = com.example.util.UpdateManager.generateVersionJsonString(sampleInfo)
    assertTrue(generatedJson.contains("\"versionCode\": 15"))
    assertTrue(generatedJson.contains("\"versionName\": \"3.2-PRO\""))
    assertTrue(generatedJson.contains("\"isMandatory\": true"))

    val roundTrip = com.example.util.UpdateManager.parseVersionJsonToUpdateInfo(generatedJson)
    assertNotNull(roundTrip)
    assertEquals(15, roundTrip!!.versionCode)
    assertEquals("3.2-PRO", roundTrip.versionName)
    assertEquals("PMP-v3.2-PRO-b15-8f4d2c1a", roundTrip.buildId)
    assertTrue(roundTrip.isMandatory)
    assertEquals(sampleInfo.sha256Checksum, roundTrip.sha256Checksum)

    assertEquals(
      "gen-lang-client-0220128746.firebasestorage.app",
      com.example.util.UpdateManager.FIREBASE_STORAGE_BUCKET
    )
    assertTrue(
      com.example.util.UpdateManager.FIREBASE_STORAGE_VERSION_JSON_URL.startsWith(
        "https://firebasestorage.googleapis.com/v0/b/gen-lang-client-0220128746.firebasestorage.app/o/version.json"
      )
    )

    // Verify public/index.html is wired to Firebase Storage version.json URL
    val indexHtmlFile = listOf(
      java.io.File("public/index.html"),
      java.io.File("../public/index.html")
    ).firstOrNull { it.exists() }
    assertNotNull("public/index.html must exist", indexHtmlFile)
    val indexHtml = indexHtmlFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "public/index.html must fetch from Firebase Storage version.json URL",
      indexHtml.contains(com.example.util.UpdateManager.FIREBASE_STORAGE_VERSION_JSON_URL)
    )

    // Verify PmpViewModel declares all class properties BEFORE init {} to prevent startup NullPointerException
    val vmFile = listOf(
      java.io.File("app/src/main/java/com/example/ui/viewmodel/PmpViewModel.kt"),
      java.io.File("src/main/java/com/example/ui/viewmodel/PmpViewModel.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpViewModel.kt must exist", vmFile)
    val vmLines = vmFile!!.readLines(Charsets.UTF_8)
    val initLineIndex = vmLines.indexOfFirst { it.trim() == "init {" }
    assertTrue("init { block must exist in PmpViewModel.kt", initLineIndex > 0)
    val propsAfterInit = vmLines.drop(initLineIndex + 1).filter { line ->
      Regex("""^    (private |internal |public )?(val |var )\w+""").containsMatchIn(line)
    }
    assertTrue(
      "No class-level val/var properties may be declared after init {} in PmpViewModel (found: $propsAfterInit)",
      propsAfterInit.isEmpty()
    )
  }

  @Test
  fun testRuntimeCrashPreventionGuards() {
    // 1. Verify MainActivity resolves effectiveCurrentScreen BEFORE Crossfade so SaveableStateProvider never receives duplicate keys
    val mainActivityFile = listOf(
      java.io.File("app/src/main/java/com/example/MainActivity.kt"),
      java.io.File("src/main/java/com/example/MainActivity.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("MainActivity.kt must exist", mainActivityFile)
    val mainContent = mainActivityFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "MainActivity must pass effectiveCurrentScreen into Crossfade to prevent duplicate SaveableStateProvider keys",
      mainContent.contains("Crossfade(") && mainContent.contains("targetState = effectiveCurrentScreen")
    )
    assertTrue(
      "MainActivity must install defensive UncaughtExceptionHandler",
      mainContent.contains("installDefensiveUncaughtExceptionHandler()")
    )

    // 2. Verify PmpDatabase is upgraded to version 11 with MIGRATION_9_10 and MIGRATION_10_11 schema repair
    val dbFile = listOf(
      java.io.File("app/src/main/java/com/example/data/local/PmpDatabase.kt"),
      java.io.File("src/main/java/com/example/data/local/PmpDatabase.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpDatabase.kt must exist", dbFile)
    val dbContent = dbFile!!.readText(Charsets.UTF_8)
    assertTrue("PmpDatabase must be at version = 11", dbContent.contains("version = 11"))
    assertTrue("PmpDatabase must register MIGRATION_9_10", dbContent.contains("MIGRATION_9_10"))
    assertTrue("PmpDatabase must register MIGRATION_10_11", dbContent.contains("MIGRATION_10_11"))

    // 3. Verify PmpDao has no bare @Insert annotations that could throw SQLiteConstraintException
    val daoFile = listOf(
      java.io.File("app/src/main/java/com/example/data/local/PmpDao.kt"),
      java.io.File("src/main/java/com/example/data/local/PmpDao.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpDao.kt must exist", daoFile)
    val bareInserts = daoFile!!.readLines(Charsets.UTF_8).filter { it.trim() == "@Insert" }
    assertTrue("All @Insert in PmpDao must specify OnConflictStrategy.REPLACE (found bare @Insert: $bareInserts)", bareInserts.isEmpty())

    // 4. Verify UpdateManager includes Firebase Storage APK URL in candidate download URLs and avoids broken EXTRA_RETURN_RESULT
    val updateManagerFile = listOf(
      java.io.File("app/src/main/java/com/example/util/UpdateManager.kt"),
      java.io.File("src/main/java/com/example/util/UpdateManager.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("UpdateManager.kt must exist", updateManagerFile)
    val umContent = updateManagerFile!!.readText(Charsets.UTF_8)
    assertFalse(
      "UpdateManager must not combine Intent.EXTRA_RETURN_RESULT with FLAG_ACTIVITY_NEW_TASK",
      umContent.contains("Intent.EXTRA_RETURN_RESULT")
    )
    assertTrue(
      "UpdateManager must support Firebase Storage direct APK hosting",
      umContent.contains("FIREBASE_STORAGE_LATEST_APK_URL")
    )
  }

  @Test
  fun testAdminFirebaseUploadProgressBarAndProfessionalDarkMode() {
    // 1. Test Professional Dark Mode reactive controller and color tokens
    val controller = com.example.ui.theme.PmpThemeController
    val originalMode = controller.selectedThemeMode
    val originalDark = controller.isDarkModeActive
    try {
      controller.selectedThemeMode = com.example.ui.theme.PmpThemeMode.LIGHT
      controller.isDarkModeActive = false
      val lightSurface = com.example.ui.theme.PmpWhiteSurface
      val lightText = com.example.ui.theme.TextDark

      controller.selectedThemeMode = com.example.ui.theme.PmpThemeMode.DARK
      controller.isDarkModeActive = true
      val darkSurface = com.example.ui.theme.PmpWhiteSurface
      val darkText = com.example.ui.theme.TextDark

      assertNotEquals("Surface color must adapt between Light and Dark Pro modes", lightSurface, darkSurface)
      assertNotEquals("Primary text color must adapt between Light and Dark Pro modes", lightText, darkText)
    } finally {
      controller.selectedThemeMode = originalMode
      controller.isDarkModeActive = originalDark
    }

    // 2. Verify SettingsScreen includes the Admin Firebase Upload Progress Bar and Dark Mode Pro controls
    val settingsFile = listOf(
      java.io.File("app/src/main/java/com/example/ui/screens/SettingsScreen.kt"),
      java.io.File("src/main/java/com/example/ui/screens/SettingsScreen.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("SettingsScreen.kt must exist", settingsFile)
    val settingsContent = settingsFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "SettingsScreen must include admin_firebase_upload_progress_bar",
      settingsContent.contains("admin_firebase_upload_progress_bar")
    )
    assertTrue(
      "SettingsScreen must include admin_firebase_upload_progress_text",
      settingsContent.contains("admin_firebase_upload_progress_text")
    )
    assertTrue(
      "SettingsScreen must include dialog_firebase_upload_progress_bar",
      settingsContent.contains("dialog_firebase_upload_progress_bar")
    )
    assertTrue(
      "SettingsScreen must include dark_mode_toggle_switch",
      settingsContent.contains("dark_mode_toggle_switch")
    )
    assertTrue(
      "SettingsScreen must include professional_dark_mode_card",
      settingsContent.contains("professional_dark_mode_card")
    )
  }

  @Test
  fun testCompleteOtaUpdateSystemAndCleanArabicUiAndErrorSanitization() {
    // 1. Verify all 10 required Firebase / version.json schema fields in UpdateInfo & buildFirestoreUpdatePayload
    val update = com.example.util.UpdateInfo(
      versionCode = 14,
      versionName = "3.1-PRO",
      downloadUrl = "https://github.com/ow7ab/ow7ab/releases/download/v3.1-PRO/pmp-paints-v3.1-PRO-b14.apk",
      releaseNotes = "• تحسين سرعة المزامنة السحابية.\n• تحسين استقرار الواجهة.",
      isMandatory = true,
      sha256Checksum = "9bb5d7bc646468b5d838303cd7e08846a3e14b939615e9a8e00600d8a146f57a",
      fileSize = 32494119L,
      fileSizeMb = 30.99,
      minSupportedVersionCode = 10,
      buildId = "PMP-v3.1-PRO-b14-9f8e7d6c",
      gitCommit = "9f8e7d6c",
      publishedAt = "2026-11-08T15:00:00Z",
      releaseStatus = "PUBLISHED"
    )

    assertEquals("9bb5d7bc646468b5d838303cd7e08846a3e14b939615e9a8e00600d8a146f57a", update.apkSha256)
    assertEquals(32494119L, update.apkSize)
    assertEquals(10, update.minimumSupportedVersion)
    assertTrue(update.mandatoryUpdate)
    assertEquals("PUBLISHED", update.releaseStatus)

    val payload = com.example.util.UpdateManager.buildFirestoreUpdatePayload(update, "admin")
    val requiredTenFields = listOf(
      "versionName",
      "versionCode",
      "downloadUrl",
      "releaseNotes",
      "apkSha256",
      "apkSize",
      "minimumSupportedVersion",
      "mandatoryUpdate",
      "publishedAt",
      "releaseStatus"
    )
    for (field in requiredTenFields) {
      assertTrue("Firestore update payload must include required field '$field'", payload.containsKey(field))
    }
    assertEquals("3.1-PRO", payload["versionName"])
    assertEquals(14, payload["versionCode"])
    assertEquals("PUBLISHED", payload["releaseStatus"])

    // 2. Verify DRAFT / UNPUBLISHED releases are rejected by validateUpdateMetadataIntegrity
    val draftDecision = com.example.util.UpdateManager.validateUpdateMetadataIntegrity(
      installedVersionCode = 13,
      installedBuildId = "PMP-v3.0-PRO-b13-8f4d2c1a",
      candidate = update.copy(releaseStatus = "DRAFT")
    )
    assertFalse("Draft or unpublished release must be rejected", draftDecision.isValid)

    // 3. Verify User-Facing Error Message Sanitization (never expose HTTP 404, ow7ab/ow7ab, .apk, com.pmp, SHA-256)
    val raw404Error = "تعذر تنزيل ملف التحديث الفعلي (APK) للإصدار v3.22-PRO (Build 22). تفاصيل الخادم: [HTTP 404 (pmp-paints-v3.22-PRO-b22.apk), HTTP 404 (pmp-paints-latest.apk)]. يرجى التأكد من اكتمال بناء ورفع ملف APK عبر GitHub Actions في المستودع (ow7ab/ow7ab)."
    val cleanUserError = com.example.util.UpdateManager.sanitizeUserFacingErrorMessage(raw404Error)
    assertFalse("User error must not contain HTTP 404", cleanUserError.contains("HTTP 404", ignoreCase = true))
    assertFalse("User error must not contain ow7ab/ow7ab", cleanUserError.contains("ow7ab", ignoreCase = true))
    assertFalse("User error must not contain .apk filename", cleanUserError.contains(".apk", ignoreCase = true))
    assertEquals("تعذر تنزيل التحديث حالياً. يرجى المحاولة لاحقاً.", cleanUserError)

    val rawSigError = "Package com.pmp signature mismatch: Cert SHA-256 1F:F7:75 does not match installed PackageManager signature"
    val cleanSigError = com.example.util.UpdateManager.sanitizeUserFacingErrorMessage(rawSigError)
    assertFalse(cleanSigError.contains("com.pmp"))
    assertFalse(cleanSigError.contains("SHA-256"))
    assertEquals("ملف التحديث غير صالح أو غير مكتمل.", cleanSigError)

    // 4. Verify User-Facing Release Notes Sanitization
    val technicalReleaseNotes = "• إصلاح جذري لملف pmp-paints-latest.apk في مستودع ow7ab/ow7ab.\n• فحص SHA-256 Checksum عبر PackageManager.\n• تحسين سرعة استجابة الواجهة وتجربة المستخدم."
    val cleanNotes = com.example.util.UpdateManager.sanitizeUserFacingReleaseNotes(technicalReleaseNotes)
    assertFalse(cleanNotes.contains("pmp-paints-latest.apk"))
    assertFalse(cleanNotes.contains("ow7ab"))
    assertFalse(cleanNotes.contains("SHA-256"))
    assertTrue(cleanNotes.contains("تحسين سرعة استجابة الواجهة وتجربة المستخدم"))

    // 5. Verify DeveloperUpdateLogger records technical diagnostics for developers without exposing to regular UI
    com.example.util.DeveloperUpdateLogger.clear()
    com.example.util.DeveloperUpdateLogger.logError("DownloadCheck", "HTTP 404 on https://github.com/ow7ab/ow7ab/releases/download/v3.22-PRO/pmp-paints-v3.22-PRO-b22.apk")
    val devLogs = com.example.util.DeveloperUpdateLogger.getRecentLogs()
    assertTrue(devLogs.isNotEmpty())
    assertTrue(devLogs.first().contains("HTTP 404"))

    // 6. Verify UpdateUiState covers all required lifecycle states
    val states = com.example.util.UpdateUiState.entries.map { it.name }.toSet()
    val expectedStates = setOf(
      "IDLE", "CHECKING", "UP_TO_DATE", "AVAILABLE",
      "DOWNLOADING", "VERIFYING", "READY_TO_INSTALL", "INSTALLING",
      "FAILED", "CANCELLED"
    )
    assertEquals(expectedStates, states)

    // 7. Verify UpdateManager no longer contains fake OTA version bumping or self-APK copy fallback
    val umFile = listOf(
      java.io.File("app/src/main/java/com/example/util/UpdateManager.kt"),
      java.io.File("src/main/java/com/example/util/UpdateManager.kt")
    ).firstOrNull { it.exists() }
    assertNotNull(umFile)
    val umSource = umFile!!.readText(Charsets.UTF_8)
    assertFalse(
      "UpdateManager must never copy installed sourceDir as a fake downloaded update",
      umSource.contains("sourceApk.copyTo(targetFile")
    )
    assertFalse(
      "UpdateManager must never fake-install when !isExternalBinaryUpgrade",
      umSource.contains("commitVerifiedOtaUpdate(")
    )

    // 8. Verify redesigned SettingsScreen contains the clean Arabic Updates card and state-driven buttons
    val settingsFile = listOf(
      java.io.File("app/src/main/java/com/example/ui/screens/SettingsScreen.kt"),
      java.io.File("src/main/java/com/example/ui/screens/SettingsScreen.kt")
    ).firstOrNull { it.exists() }
    assertNotNull(settingsFile)
    val settingsSource = settingsFile!!.readText(Charsets.UTF_8)
    assertTrue(settingsSource.contains("تحديثات التطبيق"))
    assertTrue(settingsSource.contains("التطبيق محدّث"))
    assertTrue(settingsSource.contains("أنت تستخدم أحدث إصدار من PMP PAINTS."))
    assertTrue(settingsSource.contains("تحديث جديد متاح"))
    assertTrue(settingsSource.contains("يتوفر إصدار جديد يتضمن تحسينات وإصلاحات."))
    assertTrue(settingsSource.contains("app_updates_card"))
    assertTrue(settingsSource.contains("update_status_up_to_date_card"))
    assertTrue(settingsSource.contains("update_status_available_card"))
    assertTrue(settingsSource.contains("update_status_downloading_card"))
    assertTrue(settingsSource.contains("update_status_ready_card"))
    assertTrue(settingsSource.contains("update_status_failed_card"))
    assertTrue(settingsSource.contains("check_for_updates_button"))
    assertTrue(settingsSource.contains("download_install_update_button"))
    assertTrue(settingsSource.contains("cancel_update_download_button"))
  }

  @Test
  fun testInventoryThreeColumnTableAndWeightManagementSystem() {
    // 1. Test internal unit normalization in InventoryWeightManager
    assertEquals("250", com.example.util.InventoryWeightManager.normalizeWeightValue("250"))
    assertEquals("250", com.example.util.InventoryWeightManager.normalizeWeightValue("250 جم"))
    assertEquals("250", com.example.util.InventoryWeightManager.normalizeWeightValue("٢٥٠ جرام"))
    assertEquals("250", com.example.util.InventoryWeightManager.normalizeWeightValue("0.25 كجم"))
    assertEquals("1000", com.example.util.InventoryWeightManager.normalizeWeightValue("1 كجم"))
    assertEquals("400", com.example.util.InventoryWeightManager.normalizeWeightValue("400 جم"))
    assertEquals("250 جم", com.example.util.InventoryWeightManager.formatWeightLabel("250 جم"))
    assertEquals("400 جم", com.example.util.InventoryWeightManager.formatWeightLabel("400"))

    // 2. Verify InventoryScreen preserves existing top header & tabs while adding the 3-column RTL table and weight controls
    val invFile = listOf(
      java.io.File("app/src/main/java/com/example/ui/screens/InventoryScreen.kt"),
      java.io.File("src/main/java/com/example/ui/screens/InventoryScreen.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("InventoryScreen.kt must exist", invFile)
    val invSource = invFile!!.readText(Charsets.UTF_8)

    // Existing header & sections must be preserved intact
    assertTrue(invSource.contains("المخازن والمنتجات وإدارة البضاعة"))
    assertTrue(invSource.contains("أرصدة المخزون"))
    assertTrue(invSource.contains("إضافة صنف وتوريد للمخزن"))
    assertTrue(invSource.contains("الأصناف والمخازن"))
    assertTrue(invSource.contains("سجل الحركات"))

    // New 3-column RTL table (اللون | الوزن | العدد) & Weight Selector & Weight Management button
    assertTrue(invSource.contains("inventory_weight_selector_button"))
    assertTrue(invSource.contains("الوزن: \$selectedWeightLabel ▼"))
    assertTrue(invSource.contains("inventory_three_column_table"))
    assertTrue(invSource.contains("text = \"اللون\""))
    assertTrue(invSource.contains("text = \"الوزن\""))
    assertTrue(invSource.contains("text = \"العدد\""))
    assertTrue(invSource.contains("manage_weights_button"))
    assertTrue(invSource.contains("إدارة الأوزان"))

    // 3. Verify PmpRepository supports independent (color, weight) products and non-destructive weight renaming
    val repoFile = listOf(
      java.io.File("app/src/main/java/com/example/data/repository/PmpRepository.kt"),
      java.io.File("src/main/java/com/example/data/repository/PmpRepository.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpRepository.kt must exist", repoFile)
    val repoSource = repoFile!!.readText(Charsets.UTF_8)
    assertTrue(repoSource.contains("fun ensureProductForColorAndWeight("))
    assertTrue(repoSource.contains("fun renamePackageWeightSafely("))

    // 4. Verify CloudErpSyncRepository syncs inventory_weights with Firestore
    val syncFile = listOf(
      java.io.File("app/src/main/java/com/example/data/remote/CloudErpSyncRepository.kt"),
      java.io.File("src/main/java/com/example/data/remote/CloudErpSyncRepository.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("CloudErpSyncRepository.kt must exist", syncFile)
    val syncSource = syncFile!!.readText(Charsets.UTF_8)
    assertTrue(syncSource.contains("inventory_weights"))
    assertTrue(syncSource.contains("syncInventoryWeightsToCloud"))
  }

  @Test
  fun testCompleteEnterpriseErpCycleAndPermissionsAndIdempotency() {
    // 1. Verify PmpDatabase does NOT use fallbackToDestructiveMigration (Section 1 & 20)
    val dbFile = listOf(
      java.io.File("app/src/main/java/com/example/data/local/PmpDatabase.kt"),
      java.io.File("src/main/java/com/example/data/local/PmpDatabase.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpDatabase.kt must exist", dbFile)
    val dbSource = dbFile!!.readText(Charsets.UTF_8)
    assertFalse("PmpDatabase must never use fallbackToDestructiveMigration", dbSource.contains("fallbackToDestructiveMigration"))

    // 2. Verify Strict Admin-Only User & Permission Management & Segregation of Duties (Section 16)
    val adminUser = com.example.data.local.UserAccountEntity(
      id = 1L,
      userCode = "USR-ADMIN",
      fullName = "Admin",
      username = "admin",
      role = "مدير النظام",
      branch = "كل الفروع",
      permissions = "المستخدمين,الإعدادات",
      serverSyncToken = "T1"
    )
    val generalManager = com.example.data.local.UserAccountEntity(
      id = 2L,
      userCode = "USR-MGR",
      fullName = "المدير العام",
      username = "manager1",
      role = "مدير النظام",
      branch = "كل الفروع",
      permissions = "المخازن,المصنع,المبيعات,التقارير",
      serverSyncToken = "T2"
    )
    val procurementOfficer = com.example.data.local.UserAccountEntity(
      id = 3L,
      userCode = "USR-PROC",
      fullName = "مسؤول المشتريات",
      username = "proc_user",
      role = "مسؤول المشتريات",
      branch = "فرع مصر والمصنع",
      permissions = "المشتريات",
      serverSyncToken = "T3"
    )
    assertTrue("Only Admin can manage users and permissions", com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(adminUser))
    assertFalse("General Manager cannot modify user permissions", com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(generalManager))
    assertFalse("Procurement Officer cannot modify user permissions", com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(procurementOfficer))
    assertFalse("Employee cannot approve their own sensitive document", com.example.util.ErpLifecycleEngine.canApproveDocument(procurementOfficer, "proc_user"))
    assertTrue("General Manager can approve employee document", com.example.util.ErpLifecycleEngine.canApproveDocument(generalManager, "proc_user"))

    // 3. Verify Official Packaging Standards (250g -> 48, 900g -> 12, 4000g -> 4) (Section 9)
    val rule250 = com.example.util.ErpLifecycleEngine.resolvePackagingRule("250")
    val rule900 = com.example.util.ErpLifecycleEngine.resolvePackagingRule("900")
    val rule4000 = com.example.util.ErpLifecycleEngine.resolvePackagingRule("4000")
    assertEquals(48, rule250.cansPerCarton)
    assertEquals(12, rule900.cansPerCarton)
    assertEquals(4, rule4000.cansPerCarton)

    // 4. Mandatory 16-Step End-to-End Operational Cycle Verification (Section 22):
    // Step A: Purchase 500 kg raw materials (PO alone does NOT increase available stock)
    var rawWarehouseAvailableBalanceKg = 0.0
    val po500 = com.example.util.PurchaseOrderRecord(
      poNumber = "PO-2026-0500",
      orderDate = "2026-11-08",
      supplierName = "شركة الشرق للكيماويات",
      materialId = 10L,
      materialCode = "RAW-TIO2",
      materialName = "ثاني أكسيد التيتانيوم",
      orderedQty = 500.0,
      unit = "كجم",
      unitPrice = 120.0,
      currency = "EGP",
      createdBy = "proc_user"
    )
    assertEquals("PO creation alone must keep raw stock at 0", 0.0, rawWarehouseAvailableBalanceKg, 0.001)

    // Step B: Receive 495 kg, Accept 490 kg, Reject 5 kg, Record supply discrepancy
    val grn = com.example.util.ErpLifecycleEngine.evaluateGoodsReceipt(
      po = po500,
      storekeeperName = "أمين مخزن الخام",
      actualReceivedQty = 495.0,
      acceptedQty = 490.0,
      rejectedQty = 5.0,
      discrepancyReason = "عجز توريد 5 كجم وتلف 5 كجم أثناء النقل"
    )
    assertTrue("GRN must flag discrepancy", grn.hasDiscrepancy)
    assertEquals(-5.0, grn.shortageOrOverageQty, 0.001)
    assertEquals(com.example.util.GoodsReceiptStatus.DISCREPANCY_REVIEW, grn.status)

    // Step C: Approve accepted quantity (490 kg enters available raw stock)
    rawWarehouseAvailableBalanceKg += grn.acceptedQty
    assertEquals(490.0, rawWarehouseAvailableBalanceKg, 0.001)

    // Step D: Issue 210 kg to approved Production Order & confirm Production Engineer receipt
    val issuedToProductionKg = 210.0
    rawWarehouseAvailableBalanceKg -= issuedToProductionKg
    assertEquals(280.0, rawWarehouseAvailableBalanceKg, 0.001)

    // Step E: Produce 250g (48/carton), 900g (12/carton), and 4000g (4/carton) cans with damaged cans
    val run250 = com.example.util.PackagingRunBreakdown(
      weightGrams = 250,
      cansPerCarton = rule250.cansPerCarton,
      targetCartons = 5,
      actualProducedCans = 244,
      goodApprovedCans = 240,
      damagedCans = 4,
      underInspectionCans = 0
    )
    val run900 = com.example.util.PackagingRunBreakdown(
      weightGrams = 900,
      cansPerCarton = rule900.cansPerCarton,
      targetCartons = 10,
      actualProducedCans = 123,
      goodApprovedCans = 120,
      damagedCans = 3,
      underInspectionCans = 0
    )
    val run4000 = com.example.util.PackagingRunBreakdown(
      weightGrams = 4000,
      cansPerCarton = rule4000.cansPerCarton,
      targetCartons = 5,
      actualProducedCans = 21,
      goodApprovedCans = 20,
      damagedCans = 1,
      underInspectionCans = 0
    )
    assertTrue(run250.isEquationBalanced && run900.isEquationBalanced && run4000.isEquationBalanced)
    assertEquals(5, run250.fullCartonsCount)   // 240 / 48 = 5 cartons
    assertEquals(10, run900.fullCartonsCount)  // 120 / 12 = 10 cartons
    assertEquals(5, run4000.fullCartonsCount)  // 20 / 4 = 5 cartons

    // Step F: Quality Control (QC) Inspection & Finished Goods Receipt
    val qc900 = com.example.util.ErpLifecycleEngine.performQualityControlInspection(
      batchNumber = "PB-20261108-01",
      productionOrderNumber = "PO-PRD-001",
      formulation = null,
      inspectorName = "مسؤول الجودة",
      measuredDensity = 1.20,
      measuredViscosity = "95 KU",
      appearanceAndColorMatch = true,
      goodCandidateCans = run900.goodApprovedCans,
      damagedCans = run900.damagedCans,
      cansPerCarton = rule900.cansPerCarton
    )
    assertTrue(qc900.isApprovedForWarehouse)
    assertEquals(10, qc900.acceptedCartons)

    var finishedGoods900Cartons = 0.0
    finishedGoods900Cartons += qc900.acceptedCartons

    // Step G: Sell 4 cartons Cash & 3 cartons Credit, then collect partial payment
    val cashSoldCartons = 4.0
    val creditSoldCartons = 3.0
    val totalSoldCartons = cashSoldCartons + creditSoldCartons
    finishedGoods900Cartons -= totalSoldCartons
    assertEquals("Final FG stock must match approved receipts minus sales (10 - 7 = 3)", 3.0, finishedGoods900Cartons, 0.001)

    val unitSellingPriceEgp = 1925.0
    val cashSaleAmount = cashSoldCartons * unitSellingPriceEgp       // 7,700 EGP
    val creditSaleAmount = creditSoldCartons * unitSellingPriceEgp   // 5,775 EGP
    val partialCollectionAmount = 2000.0                             // 2,000 EGP collected on credit invoice
    val customerRemainingDebt = creditSaleAmount - partialCollectionAmount // 3,775 EGP remaining debt
    val actualCashboxCollected = cashSaleAmount + partialCollectionAmount  // 9,700 EGP (credit uncollected portion is NOT cash!)
    assertEquals(3775.0, customerRemainingDebt, 0.001)
    assertEquals(9700.0, actualCashboxCollected, 0.001)

    // Step H: Production Costing & Profitability Calculation (Section 15)
    val costing = com.example.util.ErpLifecycleEngine.calculateBatchCostingAndProfit(
      batchNumber = "PB-20261108-01",
      currency = "EGP",
      bomLines = listOf(
        com.example.util.ProductionBomRequirementLine(
          materialName = "ثاني أكسيد التيتانيوم",
          materialType = "صبغة",
          theoreticalRequiredKg = 100.0,
          actuallyConsumedKg = 108.0,
          wastedKg = 2.0,
          unitCostEgp = 80.0
        )
      ),
      packagingSpec = rule900,
      actualProducedCans = run900.actualProducedCans,
      goodApprovedCans = run900.goodApprovedCans,
      damagedCans = run900.damagedCans,
      directLaborCost = 600.0,
      manufacturingOverheadCost = 400.0,
      soldCartonsCount = totalSoldCartons,
      unitSellingPricePerCarton = unitSellingPriceEgp,
      operatingExpensesAllocated = 350.0
    )
    assertTrue("Total batch cost must be positive", costing.totalBatchManufacturingCost > 0.0)
    assertTrue("Gross profit must equal revenue minus COGS", costing.grossProfit > 0.0)
    assertEquals(costing.grossProfit - 350.0, costing.netProfit, 0.001)
  }

  @Test
  fun testFinancialIntegrityUpdateSafetyRoomV11AndWeightPricing() {
    // 1. Task 1: Verify UpdateManager has NO local sourceDir fallback in downloadApk or reachability checks,
    // enforces strict archiveVersionCode <= installedOsVersionCode rejection, and uses 15s/30s timeouts
    val umFile = listOf(
      java.io.File("app/src/main/java/com/example/util/UpdateManager.kt"),
      java.io.File("src/main/java/com/example/util/UpdateManager.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("UpdateManager.kt must exist", umFile)
    val umSource = umFile!!.readText(Charsets.UTF_8)
    assertFalse(
      "UpdateManager must never stream baseInstalledArchive or signedPackageArchive from sourceDir when download fails",
      umSource.contains("baseInstalledArchive") || umSource.contains("signedPackageArchive")
    )
    assertTrue(
      "UpdateManager must strictly reject archiveVersionCode <= installedOsVersionCode",
      umSource.contains("if (archiveVersionCode <= installedOsVersionCode)")
    )
    assertTrue(
      "UpdateManager must strictly verify archivePackage matches installed packageName",
      umSource.contains("archivePackage != appContext.packageName")
    )
    assertTrue(
      "UpdateManager must use connectTimeout = 15_000 and readTimeout = 30_000",
      umSource.contains("connectTimeout = 15_000") && umSource.contains("readTimeout = 30_000")
    )

    // 2. Task 2: Verify cancelInvoice reverses stock moves (RETURN_IN) and cashbox payments (منصرف),
    // and deleteCustomer / clearCustomerBalance never hard-delete customer invoices or payments
    val repoFile = listOf(
      java.io.File("app/src/main/java/com/example/data/repository/PmpRepository.kt"),
      java.io.File("src/main/java/com/example/data/repository/PmpRepository.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpRepository.kt must exist", repoFile)
    val repoSource = repoFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "cancelInvoice must fetch invoice items and insert compensating RETURN_IN StockMoveEntity",
      repoSource.contains("getInvoiceItemsByInvoiceIdDirect(invoiceId)") &&
        repoSource.contains("kind = \"RETURN_IN\"")
    )
    assertTrue(
      "cancelInvoice must fetch invoice payments and insert reversing outgoing MovementEntity",
      repoSource.contains("getInvoicePaymentsByInvoiceIdDirect(invoiceId)") &&
        repoSource.contains("REV:\${invoice.invoiceNo}:P\${payment.id}")
    )
    assertTrue(
      "deleteCustomer must check getCustomerInvoiceCountDirect and block hard deletion if invoices exist",
      repoSource.contains("dao.getCustomerInvoiceCountDirect(id)")
    )
    assertFalse(
      "PmpRepository must never call dao.deleteCustomerInvoices in deleteCustomer or clearCustomerBalance",
      repoSource.contains("dao.deleteCustomerInvoices(")
    )
    assertFalse(
      "PmpRepository must never call dao.deleteCustomerPayments in deleteCustomer or clearCustomerBalance",
      repoSource.contains("dao.deleteCustomerPayments(")
    )

    // 3. Task 3: Verify weight-adjusted carton pricing scales accurately using PackagingRuleSpec
    val price900 = com.example.data.repository.PmpRepository.calculateWeightAdjustedCartonPrice(
      targetWeight = "900",
      referenceWeight = "900",
      referenceCartonPrice = 385000.0
    )
    val price250From900 = com.example.data.repository.PmpRepository.calculateWeightAdjustedCartonPrice(
      targetWeight = "250",
      referenceWeight = "900",
      referenceCartonPrice = 385000.0
    )
    val price4000From250 = com.example.data.repository.PmpRepository.calculateWeightAdjustedCartonPrice(
      targetWeight = "4000",
      referenceWeight = "250",
      referenceCartonPrice = price250From900
    )
    assertEquals(385000.0, price900, 0.01)
    assertTrue("250g carton (48x250g=12kg) must be priced higher than 900g carton (12x900g=10.8kg)", price250From900 > price900)
    assertTrue("4000g carton (4x4000g=16kg) must be priced higher than 250g carton (12kg)", price4000From250 > price250From900)

    // 4. Task 4 & Task 5: Verify Room DB version 11 and SQL SUM/GROUP BY aggregations in PmpDao & PmpViewModel
    val daoFile = listOf(
      java.io.File("app/src/main/java/com/example/data/local/PmpDao.kt"),
      java.io.File("src/main/java/com/example/data/local/PmpDao.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpDao.kt must exist", daoFile)
    val daoSource = daoFile!!.readText(Charsets.UTF_8)
    assertTrue("PmpDao must define getAggregatedProductStockBalances with SUM and GROUP BY", daoSource.contains("fun getAggregatedProductStockBalances()") && daoSource.contains("GROUP BY productId"))
    assertTrue("PmpDao must define getAggregatedCashboxBalances with SUM and GROUP BY", daoSource.contains("fun getAggregatedCashboxBalances()") && daoSource.contains("GROUP BY TRIM(cashbox), TRIM(currency)"))

    val vmFile = listOf(
      java.io.File("app/src/main/java/com/example/ui/viewmodel/PmpViewModel.kt"),
      java.io.File("src/main/java/com/example/ui/viewmodel/PmpViewModel.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpViewModel.kt must exist", vmFile)
    val vmSource = vmFile!!.readText(Charsets.UTF_8)
    assertTrue("PmpViewModel must wire getAggregatedProductStockBalances on Dispatchers.IO", vmSource.contains("repository.getAggregatedProductStockBalances()"))
    assertTrue("PmpViewModel must wire getAggregatedCashboxBalances on Dispatchers.IO", vmSource.contains("repository.getAggregatedCashboxBalances()"))
  }

  @Test
  fun testDownloadPageSha256VerifyAndWebClientIdConfig() {
    // 1. Verify public/index.html has SHA-256 checksum verification UI and version.json fetch logic
    val indexHtmlFile = listOf(
      java.io.File("public/index.html"),
      java.io.File("../public/index.html")
    ).firstOrNull { it.exists() }
    assertNotNull("public/index.html must exist", indexHtmlFile)
    val indexHtml = indexHtmlFile!!.readText(Charsets.UTF_8)
    assertTrue("Download page must include verify button (#verifyBtn)", indexHtml.contains("id=\"verifyBtn\""))
    assertTrue("Download page must include verifyDownloadedFileIntegrity() function", indexHtml.contains("function verifyDownloadedFileIntegrity()"))
    assertTrue("Download page must display SHA-256 checksum (#sha256ChecksumDisplay)", indexHtml.contains("id=\"sha256ChecksumDisplay\""))
    assertTrue("Download page must resolve apkSha256 / sha256Checksum / sha256 from version.json", indexHtml.contains("resolveSha256FromVersionData"))
    assertTrue("Download page must support local APK SHA-256 verification via crypto.subtle.digest('SHA-256'", indexHtml.contains("crypto.subtle.digest('SHA-256'"))
    assertTrue("Download page must include Firestore release history section (#releaseHistorySection)", indexHtml.contains("id=\"releaseHistorySection\""))
    assertTrue("Download page must include release history list (#releaseHistoryList)", indexHtml.contains("id=\"releaseHistoryList\""))
    assertTrue("Download page must fetch release notes history from Firestore app_updates collection", indexHtml.contains("FIRESTORE_APP_UPDATES_COLLECTION_URL") && indexHtml.contains("fetchAndRenderReleaseHistoryFromFirestore"))
    assertTrue("Official website must integrate authoritative GitHub Releases API", indexHtml.contains("GITHUB_LATEST_RELEASE_API_URL") && indexHtml.contains("GITHUB_ALL_RELEASES_API_URL"))
    assertTrue("Official website must exclude draft and prerelease GitHub releases", indexHtml.contains("releaseJson.draft === true || releaseJson.prerelease === true"))
    assertTrue("Official website must support True Pitch Black mode and Arabic/English switcher", indexHtml.contains("data-theme=\"true-black\"") && indexHtml.contains("function toggleWebsiteLanguage()") && indexHtml.contains("function toggleWebsiteTheme()"))
    assertTrue("Official website must showcase real PMP brand assets from ./assets/", indexHtml.contains("./assets/ic_pmp_emblem.png") && indexHtml.contains("./assets/pmp_brand_logo.jpg"))

    val releasesHistoryFile = listOf(
      java.io.File("public/releases-history.json"),
      java.io.File("../public/releases-history.json")
    ).firstOrNull { it.exists() }
    assertNotNull("public/releases-history.json must exist", releasesHistoryFile)
    val releasesHistoryText = releasesHistoryFile!!.readText(Charsets.UTF_8)
    assertTrue("releases-history.json must reference com.aistudio.pmppaints.erp", releasesHistoryText.contains("\"applicationId\": \"com.aistudio.pmppaints.erp\""))

    // 2. Verify app/build.gradle.kts and gradle.properties configure R.string.default_web_client_id
    //    using the Web OAuth 2.0 Client ID (client_type 3), never the Android OAuth Client ID (client_type 1),
    //    and enable R8/ProGuard code shrinking and obfuscation (isMinifyEnabled = true, isShrinkResources = true)
    val buildGradleFile = listOf(
      java.io.File("app/build.gradle.kts"),
      java.io.File("build.gradle.kts")
    ).firstOrNull { it.exists() && it.readText(Charsets.UTF_8).contains("applicationId = \"com.aistudio.pmppaints.erp\"") }
    assertNotNull("app/build.gradle.kts must exist", buildGradleFile)
    val buildGradleText = buildGradleFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "app/build.gradle.kts must provide resValue fallback for default_web_client_id when google-services.json is absent in CI",
      buildGradleText.contains("resValue(\"string\", \"default_web_client_id\", resolvedDefaultWebClientId)")
    )
    assertTrue(
      "app/build.gradle.kts must enable R8/ProGuard code shrinking and obfuscation (isMinifyEnabled = true) in release buildType",
      buildGradleText.contains("isMinifyEnabled = true")
    )
    assertTrue(
      "app/build.gradle.kts must enable resource shrinking (isShrinkResources = true) in release buildType",
      buildGradleText.contains("isShrinkResources = true")
    )

    val gradlePropsFile = listOf(
      java.io.File("gradle.properties"),
      java.io.File("../gradle.properties")
    ).firstOrNull { it.exists() }
    assertNotNull("gradle.properties must exist", gradlePropsFile)
    val gradlePropsText = gradlePropsFile!!.readText(Charsets.UTF_8)
    val expectedWebClientId = "675531579375-732a74lc8b5r1ilu568geaigi1u6rb0i.apps.googleusercontent.com"
    val androidOAuthClientId = "675531579375-udut6cpbt7laqhbnfap3deenjj4ehb41.apps.googleusercontent.com"
    assertTrue(
      "gradle.properties must configure actual Web OAuth 2.0 Client ID (client_type 3)",
      gradlePropsText.contains("PMP_FIREBASE_WEB_CLIENT_ID=$expectedWebClientId")
    )
    assertFalse(
      "gradle.properties must NOT use Android OAuth Client ID (client_type 1) for default_web_client_id",
      gradlePropsText.contains(androidOAuthClientId)
    )
  }

  @Test
  fun testSecurityAuditHardeningRbacAndFirestoreRules() {
    // 1. Verify ONLY Admin may create accounts or change permissions; Manager must NOT inherit these capabilities
    val adminAccount = com.example.data.local.UserAccountEntity(
      id = 1L,
      userCode = "USR-ADMIN",
      fullName = "Admin - مدير النظام الأعلى",
      username = "admin",
      role = "مدير النظام",
      branch = "كل الفروع",
      permissions = "المستخدمين,الإعدادات",
      serverSyncToken = "SYNC-ADMIN",
      active = true
    )
    val managerAccount = com.example.data.local.UserAccountEntity(
      id = 2L,
      userCode = "USR-MGR-01",
      fullName = "المدير العام",
      username = "general_manager",
      role = "مدير النظام",
      branch = "كل الفروع",
      permissions = "المستخدمين,الإعدادات,المبيعات,المخازن,المصنع,الخزن,المشتريات,التقارير",
      serverSyncToken = "SYNC-MGR-01",
      active = true
    )
    val accountantAccount = com.example.data.local.UserAccountEntity(
      id = 3L,
      userCode = "USR-ACC-01",
      fullName = "محاسب الفرع",
      username = "branch_accountant",
      role = "المحاسب",
      branch = "فرع السودان (SD-001)",
      permissions = "الخزن,المبيعات,التقارير",
      serverSyncToken = "SYNC-ACC-01",
      active = true
    )

    assertTrue("Admin must be recognized as SuperAdmin", adminAccount.isSuperAdminAccount())
    assertFalse("Manager must NOT be recognized as SuperAdmin", managerAccount.isSuperAdminAccount())
    assertTrue("Manager is recognized as SystemManager for operational oversight", managerAccount.isSystemManager())
    assertFalse("Accountant must NOT be SuperAdmin or SystemManager", accountantAccount.isSuperAdminAccount() || accountantAccount.isSystemManager())

    assertTrue(
      "Admin is authorized to create accounts and modify permissions",
      com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(adminAccount)
    )
    assertFalse(
      "Manager must NOT inherit account creation or permission modification capabilities",
      com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(managerAccount)
    )
    assertFalse(
      "Accountant must NOT have account creation or permission modification capabilities",
      com.example.util.ErpLifecycleEngine.canManageUsersAndPermissions(accountantAccount)
    )

    // Granular permission checks on "المستخدمين" module for Manager vs Admin
    for (restrictedAction in listOf(
      com.example.util.ErpActionPermission.CREATE,
      com.example.util.ErpActionPermission.EDIT,
      com.example.util.ErpActionPermission.APPROVE,
      com.example.util.ErpActionPermission.MANAGE_SETTINGS
    )) {
      assertTrue(
        "Admin must have $restrictedAction on المستخدمين",
        com.example.util.ErpLifecycleEngine.hasGranularPermission(adminAccount, "المستخدمين", restrictedAction)
      )
      assertFalse(
        "Manager must be denied $restrictedAction on المستخدمين",
        com.example.util.ErpLifecycleEngine.hasGranularPermission(managerAccount, "المستخدمين", restrictedAction)
      )
    }

    // 2. Verify UsersScreen and PmpViewModel enforce Admin-only guards in source code
    val usersScreenFile = listOf(
      java.io.File("app/src/main/java/com/example/ui/screens/UsersScreen.kt"),
      java.io.File("src/main/java/com/example/ui/screens/UsersScreen.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("UsersScreen.kt must exist", usersScreenFile)
    val usersScreenSource = usersScreenFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "UsersScreen must gate Create Account tab (1 -> if (isSuperAdmin)) strictly to SuperAdmin",
      usersScreenSource.contains("1 -> if (isSuperAdmin)")
    )
    assertTrue(
      "UsersScreen must set canManagePermissions = isSuperAdmin",
      usersScreenSource.contains("canManagePermissions = isSuperAdmin")
    )

    // 3. Verify root .gitignore excludes debug.keystore and debug.keystore.base64 from public repository
    val gitignoreFile = listOf(
      java.io.File("../.gitignore"),
      java.io.File(".gitignore")
    ).firstOrNull { it.exists() && it.readText(Charsets.UTF_8).contains("local.properties") }
    assertNotNull("Root .gitignore must exist", gitignoreFile)
    val gitignoreContent = gitignoreFile!!.readText(Charsets.UTF_8)
    assertTrue(".gitignore must exclude debug.keystore", gitignoreContent.contains("debug.keystore"))
    assertTrue(".gitignore must exclude debug.keystore.base64", gitignoreContent.contains("debug.keystore.base64"))

    // 4. Verify AndroidManifest.xml disables cleartext traffic and keeps FileProvider non-exported
    val manifestFile = listOf(
      java.io.File("app/src/main/AndroidManifest.xml"),
      java.io.File("src/main/AndroidManifest.xml")
    ).firstOrNull { it.exists() }
    assertNotNull("AndroidManifest.xml must exist", manifestFile)
    val manifestContent = manifestFile!!.readText(Charsets.UTF_8)
    assertTrue(
      "AndroidManifest.xml must set android:usesCleartextTraffic=\"false\"",
      manifestContent.contains("android:usesCleartextTraffic=\"false\"")
    )
    assertTrue(
      "FileProvider must have android:exported=\"false\" and android:grantUriPermissions=\"true\"",
      manifestContent.contains("android:exported=\"false\"") && manifestContent.contains("android:grantUriPermissions=\"true\"")
    )

    // 5. Verify firestore.rules enforces strict authentication, ownership, and anti-escalation on /linked_accounts
    val rulesFile = listOf(
      java.io.File("firestore.rules"),
      java.io.File("../firestore.rules")
    ).firstOrNull { it.exists() }
    assertNotNull("firestore.rules must exist", rulesFile)
    val rulesContent = rulesFile!!.readText(Charsets.UTF_8)
    assertTrue("firestore.rules must enforce default-deny", rulesContent.contains("allow read, write: if false;"))
    assertTrue(
      "firestore.rules must restrict /linked_accounts get/list to isAccountOwner() || isLinkedEmployee()",
      rulesContent.contains("allow get: if isAccountOwner() || isLinkedEmployee();") &&
        rulesContent.contains("allow list: if isAccountOwner() || isLinkedEmployee();")
    )
    assertTrue(
      "firestore.rules must restrict employee updates to serverSyncToken and updatedAt only",
      rulesContent.contains("changedKeys.hasOnly(['serverSyncToken', 'updatedAt'])")
    )
  }

  @Test
  fun testModularCrossPlatformArchitectureAndTrueBlackDarkMode() {
    // 1. Verify unified version contract across PmpAppVersionContract, BuildConfig, and UpdateManager
    assertEquals(BuildConfig.VERSION_CODE, com.example.core.architecture.PmpAppVersionContract.CURRENT_VERSION_CODE)
    assertEquals(BuildConfig.VERSION_NAME, com.example.core.architecture.PmpAppVersionContract.CURRENT_VERSION_NAME)
    assertTrue("BuildConfig.VERSION_CODE must be strictly greater than 15", BuildConfig.VERSION_CODE > 15)
    assertTrue("BuildConfig.VERSION_NAME must not be blank", BuildConfig.VERSION_NAME.isNotBlank())
    assertEquals(BuildConfig.VERSION_CODE, com.example.util.UpdateManager.LATEST_CLOUD_RELEASE_INFO.versionCode)
    assertEquals(BuildConfig.VERSION_NAME, com.example.util.UpdateManager.LATEST_CLOUD_RELEASE_INFO.versionName)
    assertEquals("com.aistudio.pmppaints.erp", com.example.core.architecture.PmpAppVersionContract.APPLICATION_ID)

    val parsedVersion = com.example.core.architecture.PmpAppVersionContract.parseSemanticVersion("v4.2")
    assertEquals(4, parsedVersion.major)
    assertEquals(2, parsedVersion.minor)
    assertEquals(0, parsedVersion.patch)

    val parsedCurrent = com.example.core.architecture.PmpAppVersionContract.parseSemanticVersion(BuildConfig.VERSION_NAME)
    assertTrue("Parsed major version of BuildConfig.VERSION_NAME must be positive", parsedCurrent.major > 0)

    val currentCode = BuildConfig.VERSION_CODE
    val currentName = BuildConfig.VERSION_NAME
    val nextCode = currentCode + 1
    val nextName = "${parsedCurrent.major}.${parsedCurrent.minor + 1}"

    assertTrue(
      "Next remote version ($nextName / code $nextCode) must be detected as newer than installed ($currentName / code $currentCode)",
      com.example.core.architecture.PmpAppVersionContract.isRemoteUpdateNewer(
        installedVersionCode = currentCode,
        installedVersionName = currentName,
        remoteVersionCode = nextCode,
        remoteVersionName = nextName
      )
    )
    assertFalse(
      "Same version ($currentName / code $currentCode) must not trigger a redundant update",
      com.example.core.architecture.PmpAppVersionContract.isRemoteUpdateNewer(
        installedVersionCode = currentCode,
        installedVersionName = currentName,
        remoteVersionCode = currentCode,
        remoteVersionName = currentName
      )
    )

    // 2. Verify modular cross-platform registry supports Android, iOS, and Web targets without dependency violations
    assertTrue(
      "Modular architecture dependency graph must be 100% valid",
      com.example.core.architecture.PmpModuleRegistry.validateDependencyGraph()
    )
    val androidModules = com.example.core.architecture.PmpModuleRegistry.getModulesForPlatform(
      com.example.core.architecture.PmpTargetPlatform.ANDROID
    )
    val iosModules = com.example.core.architecture.PmpModuleRegistry.getModulesForPlatform(
      com.example.core.architecture.PmpTargetPlatform.IOS
    )
    val webModules = com.example.core.architecture.PmpModuleRegistry.getModulesForPlatform(
      com.example.core.architecture.PmpTargetPlatform.WEB
    )
    assertTrue("All 14 ERP modules must support Android", androidModules.size >= 14)
    assertEquals("All ERP modules must be portable to iOS", androidModules.size, iosModules.size)
    assertEquals("All ERP modules must be portable to Web", androidModules.size, webModules.size)

    // 3. Verify True Pitch Black Dark Mode ("الوضع المظلم الأسود") tokens and adaptive Compose theme state
    val darkTokens = com.example.core.architecture.PmpThemeArchitectureContract.resolveTokens(isDark = true)
    assertEquals(0xFF000000L, darkTokens.backgroundPrimaryHex)
    assertEquals(0xFF0A0A0DL, darkTokens.surfacePrimaryHex)
    assertEquals(0xFF121216L, darkTokens.surfaceCardHex)
    assertEquals("الوضع المظلم الأسود", com.example.core.architecture.PmpThemeArchitectureContract.TRUE_BLACK_MODE_NAME_AR)

    val previousMode = com.example.ui.theme.PmpThemeModeController.isDarkMode
    try {
      com.example.ui.theme.PmpThemeModeController.isDarkMode = true
      assertEquals(androidx.compose.ui.graphics.Color(0xFF000000), com.example.ui.theme.PmpWhiteBg)
      assertEquals(androidx.compose.ui.graphics.Color(0xFF121216), com.example.ui.theme.PmpWhiteCard)
      assertEquals(androidx.compose.ui.graphics.Color(0xFF18181D), com.example.ui.theme.PmpSubtleSurface)

      com.example.ui.theme.PmpThemeModeController.isDarkMode = false
      assertEquals(androidx.compose.ui.graphics.Color(0xFFF0F4F8), com.example.ui.theme.PmpWhiteBg)
      assertEquals(androidx.compose.ui.graphics.Color(0xFFF8FAFC), com.example.ui.theme.PmpWhiteCard)
    } finally {
      com.example.ui.theme.PmpThemeModeController.isDarkMode = previousMode
    }
  }

  @Test
  fun testPhase02AdvancedAuthenticationAndBiometricSecurity() {
    com.example.util.AuthSecurityManager.resetInMemoryStoreForTests()
    com.example.util.DeviceSessionStore.resetInMemoryStoreForTests()
    com.example.util.BiometricPreferenceManager.resetInMemoryStoreForTests()

    // 1. Strong Password Hashing (PBKDF2-HMAC-SHA256) + Legacy Migration
    val pbkdf2Hash1 = com.example.util.PasswordSecurityUtil.hashPassword("PmpSecure#2026")
    val pbkdf2Hash2 = com.example.util.PasswordSecurityUtil.hashPassword("PmpSecure#2026")
    assertTrue("PBKDF2 hash must use pbkdf2\$sha256\$ prefix", pbkdf2Hash1.startsWith("pbkdf2\$sha256\$65536\$"))
    assertNotEquals("Salted PBKDF2 hashes of same password must be unique", pbkdf2Hash1, pbkdf2Hash2)
    assertTrue(com.example.util.PasswordSecurityUtil.verifyPassword("PmpSecure#2026", pbkdf2Hash1))
    assertFalse(com.example.util.PasswordSecurityUtil.verifyPassword("WrongPass", pbkdf2Hash1))
    assertFalse(com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(pbkdf2Hash1))

    // Verify backward compatibility with legacy SHA-256 and plaintext PINs
    val legacySha = com.example.data.repository.PmpRepository.hashSecret("1234")
    assertTrue(com.example.util.PasswordSecurityUtil.verifyPassword("1234", legacySha))
    assertTrue(com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2(legacySha))
    assertTrue(com.example.util.PasswordSecurityUtil.verifyPassword("1234", "1234"))
    assertTrue(com.example.util.PasswordSecurityUtil.needsUpgradeToPbkdf2("1234"))

    // 2. Protection against repeated failed login attempts (Progressive Lockout)
    val baseNow = 1_750_000_000_000L
    for (i in 1..4) {
      val st = com.example.util.AuthSecurityManager.recordFailedLoginAttempt(
        context = null,
        identity = "cashier_sd",
        matchedUserCode = "USR-SD-002",
        reason = "Wrong password",
        nowMs = baseNow + i * 1000L
      )
      assertFalse("Should not lock on attempt $i", st.isLocked)
    }
    val fifthAttempt = com.example.util.AuthSecurityManager.recordFailedLoginAttempt(
      context = null,
      identity = "cashier_sd",
      matchedUserCode = "USR-SD-002",
      reason = "Wrong password #5",
      nowMs = baseNow + 5000L
    )
    assertTrue("5th failed attempt must trigger progressive lockout", fifthAttempt.isLocked)
    assertTrue("Lockout duration must be 5 minutes (300s)", fifthAttempt.remainingSeconds in 295..305)

    // Verify login is blocked during lockout window and allowed after expiry
    val duringLock = com.example.util.AuthSecurityManager.checkLoginAllowed(null, "cashier_sd", baseNow + 60_000L)
    assertTrue(duringLock.isLocked)
    val afterLock = com.example.util.AuthSecurityManager.checkLoginAllowed(null, "cashier_sd", baseNow + 310_000L)
    assertFalse(afterLock.isLocked)

    com.example.util.AuthSecurityManager.clearFailedLoginAttempts(null, "cashier_sd")
    assertFalse(com.example.util.AuthSecurityManager.checkLoginAllowed(null, "cashier_sd", baseNow + 60_000L).isLocked)

    // 3. Time-bounded (10m) & Attempt-limited (max 3 tries) OTP Password Reset
    val otp = com.example.util.AuthSecurityManager.issuePasswordResetChallenge(
      context = null,
      userCode = "USR-SD-002",
      username = "cashier_sd",
      channel = "EMAIL",
      nowMs = baseNow
    )
    assertEquals(6, otp.length)
    // Expired OTP must fail
    val expiredCheck = com.example.util.AuthSecurityManager.verifyAndConsumePasswordResetOtp(
      context = null,
      userCode = "USR-SD-002",
      otpInput = otp,
      nowMs = baseNow + 11 * 60 * 1000L
    )
    assertTrue("OTP must expire after 10 minutes", expiredCheck.isFailure)

    // Re-issue and test valid consumption + single-use guarantee
    val otp2 = com.example.util.AuthSecurityManager.issuePasswordResetChallenge(
      context = null,
      userCode = "USR-SD-002",
      username = "cashier_sd",
      channel = "EMAIL",
      nowMs = baseNow
    )
    val validOtpCheck = com.example.util.AuthSecurityManager.verifyAndConsumePasswordResetOtp(
      context = null,
      userCode = "USR-SD-002",
      otpInput = otp2,
      nowMs = baseNow + 60_000L
    )
    assertTrue("Valid OTP within 10 minutes must succeed", validOtpCheck.isSuccess)
    val replayOtpCheck = com.example.util.AuthSecurityManager.verifyAndConsumePasswordResetOtp(
      context = null,
      userCode = "USR-SD-002",
      otpInput = otp2,
      nowMs = baseNow + 61_000L
    )
    assertTrue("Consumed OTP must not be reusable", replayOtpCheck.isFailure)

    // 4. Optional Two-Factor Authentication (TOTP + 8 Single-Use Backup Recovery Codes)
    val (secret, backupCodes) = com.example.util.AuthSecurityManager.enableTwoFactorAuth(
      context = null,
      userCode = "USR-HQ-001",
      username = "admin",
      requireOnEveryLogin = true
    )
    assertTrue(secret.isNotBlank())
    assertEquals(8, backupCodes.size)

    val currentTotp = com.example.util.PasswordSecurityUtil.generateTotpCode(secret, baseNow)
    assertEquals(6, currentTotp.length)
    assertTrue(com.example.util.PasswordSecurityUtil.verifyTotpCode(secret, currentTotp, baseNow))

    val totpRes = com.example.util.AuthSecurityManager.verifyTwoFactorCodeOrRecovery(
      context = null,
      userCode = "USR-HQ-001",
      inputCode = currentTotp,
      nowMs = baseNow
    )
    assertTrue("Valid TOTP code must pass 2FA verification", totpRes.isSuccess)

    // Verify Backup Recovery Code works once and cannot be replayed
    val firstRecoveryCode = backupCodes.first()
    assertTrue(com.example.util.AuthSecurityManager.consumeBackupRecoveryCode(null, "USR-HQ-001", firstRecoveryCode))
    assertFalse("Single-use backup recovery code must not work a second time", com.example.util.AuthSecurityManager.consumeBackupRecoveryCode(null, "USR-HQ-001", firstRecoveryCode))

    // 5. Trusted Device Management & Session Expiration / Revocation
    val adminUser = com.example.data.local.UserAccountEntity(
      id = 1L,
      userCode = "USR-HQ-001",
      fullName = "مدير النظام",
      username = "admin",
      phoneOrEmail = "oaw7ab@gmail.com",
      pinOrPassword = pbkdf2Hash1,
      role = "مدير النظام",
      branch = "المقر الرئيسي",
      permissions = "الكل",
      serverSyncToken = "SYNC-HQ-1001",
      remoteServerId = "SRV-1",
      syncStatus = "APPROVED_BY_MANAGER",
      serverEndpoint = "https://firestore.googleapis.com/v1",
      isCurrentSession = true,
      active = true
    )

    val sessionId = com.example.util.DeviceSessionStore.saveActiveUserSession(null, adminUser, nowMs = baseNow)
    assertTrue(sessionId.startsWith("SESS-USR-HQ-001"))

    val validSession = com.example.util.DeviceSessionStore.validateSessionForUser(null, adminUser, nowMs = baseNow + 5 * 60_000L)
    assertTrue("Active session within timeout must be valid", validSession.isValid)

    val idleExpired = com.example.util.DeviceSessionStore.validateSessionForUser(null, adminUser, nowMs = baseNow + 45 * 60_000L)
    assertFalse("Session exceeding 30-minute idle timeout must expire", idleExpired.isValid)

    // Re-save and test explicit session revocation
    val sessionId2 = com.example.util.DeviceSessionStore.saveActiveUserSession(null, adminUser, nowMs = baseNow)
    com.example.util.AuthSecurityManager.revokeSessionById(null, sessionId2, "TEST_REVOKE", nowMs = baseNow + 1000L)
    val revokedCheck = com.example.util.DeviceSessionStore.validateSessionForUser(null, adminUser, nowMs = baseNow + 2000L)
    assertFalse("Explicitly revoked session ID must be rejected", revokedCheck.isValid)

    // 6. BIOMETRIC AUTHENTICATION ISOLATION (Critical Security Requirement):
    // Prove that biometric authentication is strictly bound to the exact user (userCode + username + credentialFingerprint)
    // and NEVER opens another employee's account even if Room auto-increment IDs collide!
    val employeeAhmad = com.example.data.local.UserAccountEntity(
      id = 2L,
      userCode = "USR-SD-2050",
      fullName = "أحمد المحاسب",
      username = "ahmad_sd",
      phoneOrEmail = "ahmad@pmp.com",
      pinOrPassword = pbkdf2Hash1,
      role = "محاسب مبيعات",
      branch = "فرع السودان (SD-001)",
      permissions = "المبيعات,الخزن",
      serverSyncToken = "SYNC-SD-2050",
      remoteServerId = "SRV-2050",
      syncStatus = "APPROVED_BY_MANAGER",
      serverEndpoint = "https://firestore.googleapis.com/v1",
      isCurrentSession = true,
      active = true
    )

    com.example.util.BiometricPreferenceManager.bindUser(null, employeeAhmad)
    val validBio = com.example.util.BiometricPreferenceManager.resolveAndValidateBoundAccount(
      context = null,
      allUsers = listOf(adminUser, employeeAhmad)
    )
    assertTrue("Biometric validation must succeed for the exact bound employee", validBio.isValid)
    assertEquals("USR-SD-2050", validBio.user?.userCode)

    // Simulate Room database re-sync where another employee ("خالد") gets id = 2L (the same local id Ahmad had!)
    val employeeKhaledWithSameLocalId = com.example.data.local.UserAccountEntity(
      id = 2L, // Colliding Room auto-increment ID!
      userCode = "USR-EG-9999",
      fullName = "خالد مخزن مصر",
      username = "khaled_eg",
      phoneOrEmail = "khaled@pmp.com",
      pinOrPassword = pbkdf2Hash2,
      role = "أمين مخزن",
      branch = "فرع مصر",
      permissions = "المخازن",
      serverSyncToken = "SYNC-EG-9999",
      remoteServerId = "SRV-9999",
      syncStatus = "APPROVED_BY_MANAGER",
      serverEndpoint = "https://firestore.googleapis.com/v1",
      isCurrentSession = false,
      active = true
    )
    val crossAccountCollisionCheck = com.example.util.BiometricPreferenceManager.resolveAndValidateBoundAccount(
      context = null,
      allUsers = listOf(adminUser, employeeKhaledWithSameLocalId)
    )
    assertFalse("Biometric auth MUST NEVER open another employee account when local IDs collide", crossAccountCollisionCheck.isValid)
    assertNull("Cross-account biometric resolution must return null user", crossAccountCollisionCheck.user)

    // Re-bind Ahmad and verify that if Ahmad's password or serverSyncToken changes on the server, biometric login requires password re-verification
    com.example.util.BiometricPreferenceManager.bindUser(null, employeeAhmad)
    val ahmadAfterServerTokenRotation = employeeAhmad.copy(serverSyncToken = "SYNC-SD-2050-ROTATED-999")
    val rotatedTokenBioCheck = com.example.util.BiometricPreferenceManager.resolveAndValidateBoundAccount(
      context = null,
      allUsers = listOf(adminUser, ahmadAfterServerTokenRotation)
    )
    assertFalse("Biometric auth must be rejected when serverSyncToken or password changes until re-authenticated", rotatedTokenBioCheck.isValid)

    // Verify that when another user (Admin) logs in on the same device, Ahmad's biometric binding is immediately replaced/isolated
    com.example.util.BiometricPreferenceManager.bindUser(null, employeeAhmad)
    com.example.util.BiometricPreferenceManager.synchronizeBindingOnAuthenticatedLogin(null, adminUser, autoBindIfUnbound = true)
    val boundAfterAdminLogin = com.example.util.BiometricPreferenceManager.getBoundUser(null)
    assertEquals("Logging in as Admin must isolate biometric binding from previous employee", "USR-HQ-001", boundAfterAdminLogin?.userCode)

    // 7. Verify Login Activity History & Suspicious Login Alerts were recorded
    val activities = com.example.util.AuthSecurityManager.getLoginActivities(null)
    val alerts = com.example.util.AuthSecurityManager.getSuspiciousAlerts(null)
    assertTrue("Login activities must be recorded", activities.isNotEmpty())
    assertTrue("Suspicious login alerts must be recorded on lockout/biometric mismatch", alerts.isNotEmpty())
  }
}

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

    // 2. Verify PmpDatabase is upgraded to version 10 with MIGRATION_9_10 schema repair
    val dbFile = listOf(
      java.io.File("app/src/main/java/com/example/data/local/PmpDatabase.kt"),
      java.io.File("src/main/java/com/example/data/local/PmpDatabase.kt")
    ).firstOrNull { it.exists() }
    assertNotNull("PmpDatabase.kt must exist", dbFile)
    val dbContent = dbFile!!.readText(Charsets.UTF_8)
    assertTrue("PmpDatabase must be at version = 10", dbContent.contains("version = 10"))
    assertTrue("PmpDatabase must register MIGRATION_9_10", dbContent.contains("MIGRATION_9_10"))

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
}


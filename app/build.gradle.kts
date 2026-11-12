import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.time.Duration

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.pmppaints.erp"
    minSdk = 24
    targetSdk = 36
    versionCode = 18
    versionName = "18.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
    resValues = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Suppress benign KSP in-process IntelliJ Core post-disposal callback on AWT-EventQueue-0
val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
val kspAwtSafeHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
  val isKspAwtTeardownRace =
      thread.name.startsWith("AWT-EventQueue") &&
          throwable is NullPointerException &&
          (throwable.message?.contains("ksp.com.intellij.openapi.application.Application") == true ||
              throwable.stackTrace.any { it.className.startsWith("ksp.com.intellij.") })
  if (!isKspAwtTeardownRace) {
    previousUncaughtHandler?.uncaughtException(thread, throwable)
  }
}
Thread.setDefaultUncaughtExceptionHandler(kspAwtSafeHandler)
Thread.getAllStackTraces().keys
    .filter { it.name.startsWith("AWT-EventQueue") }
    .forEach { it.uncaughtExceptionHandler = kspAwtSafeHandler }

val defaultConfiguredVersionCode: Int = android.defaultConfig.versionCode ?: 18
val defaultConfiguredVersionName: String =
    android.defaultConfig.versionName?.takeIf { it.isNotBlank() } ?: "18.0"
val resolvedVersionCode: Int =
    (project.findProperty("versionCode")?.toString()?.toIntOrNull()) ?: defaultConfiguredVersionCode
val resolvedVersionName: String =
    (project.findProperty("versionName")?.toString()?.takeIf { it.isNotBlank() })
        ?: defaultConfiguredVersionName
val resolvedGitCommit: String =
    (project.findProperty("gitCommit")?.toString()?.trim()?.takeIf { it.isNotBlank() })
        ?: System.getenv("GITHUB_SHA")?.take(8)?.takeIf { it.isNotBlank() }
        ?: "9a8b7c6d"
val resolvedBuildTimestampUtc: String =
    (project.findProperty("buildTimestampUtc")?.toString()?.trim()?.takeIf { it.isNotBlank() })
        ?: "2026-11-10T10:00:00Z"
val cleanVersionNameForBuildId: String =
    resolvedVersionName.removePrefix("v").removePrefix("V").trim()
val resolvedBuildId: String =
    (project.findProperty("buildId")?.toString()?.trim()?.takeIf { it.isNotBlank() })
        ?: "PMP-v${cleanVersionNameForBuildId}-b${resolvedVersionCode}-${resolvedGitCommit}"

val resolvedDefaultWebClientId: String =
    (project.findProperty("defaultWebClientId")?.toString()?.trim()?.takeIf { it.isNotBlank() })
        ?: (project.findProperty("DEFAULT_WEB_CLIENT_ID")?.toString()?.trim()?.takeIf { it.isNotBlank() })
        ?: System.getenv("DEFAULT_WEB_CLIENT_ID")?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv("GOOGLE_WEB_CLIENT_ID")?.trim()?.takeIf { it.isNotBlank() }
        ?: (project.findProperty("PMP_FIREBASE_WEB_CLIENT_ID")?.toString()?.trim()?.takeIf { it.isNotBlank() })
        ?: ""

val googleServicesJsonFile = file("google-services.json")
val hasGoogleServicesJson: Boolean =
    googleServicesJsonFile.isFile && googleServicesJsonFile.length() > 0L
val googleServicesProvidesWebClientId: Boolean =
    if (hasGoogleServicesJson) {
      val gsRaw = runCatching { googleServicesJsonFile.readText() }.getOrDefault("")
      (gsRaw.contains("\"package_name\": \"com.aistudio.pmppaints.erp\"") ||
          gsRaw.contains("\"package_name\": \"com.pmp\"")) &&
          Regex("\"client_type\"\\s*:\\s*3").containsMatchIn(gsRaw)
    } else {
      false
    }

android.defaultConfig.apply {
  versionCode = resolvedVersionCode
  versionName = resolvedVersionName

  buildConfigField("String", "GIT_COMMIT_SHA", "\"$resolvedGitCommit\"")
  buildConfigField("String", "BUILD_ID", "\"$resolvedBuildId\"")
  buildConfigField("String", "BUILD_TIMESTAMP_UTC", "\"$resolvedBuildTimestampUtc\"")
  buildConfigField("String", "GITHUB_RELEASE_REPO", "\"ow7ab/ow7ab\"")

  if (!googleServicesProvidesWebClientId && resolvedDefaultWebClientId.isNotBlank()) {
    resValue("string", "default_web_client_id", resolvedDefaultWebClientId)
  }
  if (!hasGoogleServicesJson) {
    resValue("string", "project_id", "gen-lang-client-0220128746")
    resValue("string", "gcm_defaultSenderId", "675531579375")
    resValue("string", "google_storage_bucket", "gen-lang-client-0220128746.firebasestorage.app")
    resValue("string", "google_app_id", "1:675531579375:android:129c907cec2b2ed43ee01a")
    val envFirebaseApiKey = System.getenv("FIREBASE_API_KEY")?.trim().orEmpty()
    if (envFirebaseApiKey.isNotBlank()) {
      resValue("string", "google_api_key", envFirebaseApiKey)
    }
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Automatically sync official PMP drawable brand assets into public/assets for the official website
runCatching {
  val drawableDir = file("src/main/res/drawable")
  val publicAssetsDir = rootProject.file("public/assets")
  if (drawableDir.isDirectory) {
    publicAssetsDir.mkdirs()
    listOf(
            "ic_pmp_emblem.png",
            "ic_pmp_emblem_solid.png",
            "pmp_brand_logo.jpg",
            "pmp_brand_poster.jpg",
            "pmp_reference_poster.jpg"
        )
        .forEach { assetName ->
          val srcFile = File(drawableDir, assetName)
          val dstFile = File(publicAssetsDir, assetName)
          if (srcFile.isFile && (!dstFile.isFile || dstFile.length() != srcFile.length())) {
            srcFile.copyTo(dstFile, overwrite = true)
          }
        }
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.messaging)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.androidx.biometric)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "kspDebug"(libs.androidx.room.compiler)
  "kspRelease"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

tasks.withType<Test> {
  failFast = true
  timeout.set(Duration.ofMinutes(2))
  systemProperty(
      "robolectric.dependency.repo.url",
      "https://maven-central.storage-download.googleapis.com/maven2"
  )
  testLogging {
    events("passed", "failed", "skipped")
  }
}

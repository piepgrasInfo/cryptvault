plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

// Release signing is configured only when keystore.properties exists, so a fresh
// clone (and CI) can still build debug without the signing material.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()

// Provider registrations (docs/PROVIDER_SETUP.md). Not secrets, but per developer, so they are
// read from a gitignored file into BuildConfig; an empty value hides that backup target.
val providerProperties = Properties().apply {
    val f = rootProject.file("providers.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun provider(key: String): String = "\"${providerProperties.getProperty(key, "").trim()}\""

android {
    namespace = "info.piepgras.cryptvault"
    compileSdk = 37

    defaultConfig {
        applicationId = "info.piepgras.cryptvault"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DROPBOX_APP_KEY", provider("DROPBOX_APP_KEY"))
        buildConfigField("String", "MSAL_CLIENT_ID", provider("MSAL_CLIENT_ID"))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            val keystoreProperties = Properties().apply {
                load(keystorePropertiesFile.inputStream())
            }
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")!!
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
            }
        }
    }

    // Two distributions of the same app (BUILD_BRIEF.md §13, decided 2026-09-19). `play` is the
    // Google Play build and the only one that may ever link Google Play services (the Drive
    // backup target needs them). `foss` is the build anyone can install - F-Droid, GitHub
    // Releases, piepgras.info - and carries its own applicationId, because Play App Signing
    // re-signs the Play copy with Google's key: with one id the two could never replace each
    // other, and switching would mean an uninstall, which takes the private vaults with it.
    // Anything Google-dependent belongs in `src/play/`, never in `src/main/`.
    flavorDimensions += "dist"
    productFlavors {
        create("play") {
            dimension = "dist"
            buildConfigField("String", "DISTRIBUTION", "\"play\"")
            buildConfigField("boolean", "PLAY_SERVICES_ALLOWED", "true")
        }
        create("foss") {
            dimension = "dist"
            applicationIdSuffix = ".foss"
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
            buildConfigField("boolean", "PLAY_SERVICES_ALLOWED", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    bundle {
        language {
            // The app switches locale in-app, so every language has to be in the
            // installed base APK rather than split out on demand.
            enableSplit = false
        }
    }

    packaging {
        // Bouncy Castle, PGPainless and kage each ship licence/notice files under the same names.
        resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md", "META-INF/NOTICE.md", "META-INF/DEPENDENCIES", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Names the build outputs after the app rather than the module, so the two distributions are
// told apart by their file names: cryptvault-foss-release.apk, cryptvault-play-release.aab.
base {
    archivesName = "cryptvault"
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.lifecycle.process)
    implementation(libs.exifinterface)
    implementation(libs.zxcvbn)
    implementation(libs.biometric)
    implementation(libs.fragment)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sentry.android.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    implementation(libs.zip4j)
    // kage ships the jdk15to18 flavour of the BC provider; PGPainless the jdk18on one. Same classes,
    // two jars — keep one (jdk18on, pinned below) or dexing fails on duplicates.
    implementation(libs.kage) { exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18") }
    implementation(libs.pgpainless)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pg)
    implementation(libs.bouncycastle.util)

    // The vault format (BUILD_BRIEF.md §3). cryptolib logs through slf4j: a no-op binding in
    // release, stderr (visible in logcat) in debug.
    implementation(libs.cryptolib)
    debugImplementation(libs.slf4j.simple)
    releaseImplementation(libs.slf4j.nop)

    // Google-dependent backup providers go here and nowhere else: `playImplementation` for the
    // Drive target's Credential Manager and play-services-auth (BUILD_BRIEF.md §6.1). Keeping
    // the `foss` classpath free of com.google.android.gms is checked by DistributionTest and by
    // reading the merged manifest of fossRelease.

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.cryptofs)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

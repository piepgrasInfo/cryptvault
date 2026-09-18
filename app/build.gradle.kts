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
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sentry.android.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // The vault format (BUILD_BRIEF.md §3). cryptolib logs through slf4j: a no-op binding in
    // release, stderr (visible in logcat) in debug.
    implementation(libs.cryptolib)
    debugImplementation(libs.slf4j.simple)
    releaseImplementation(libs.slf4j.nop)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.cryptofs)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}

import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// Release signing: values come from keystore.properties (git-ignored) or from
// environment variables in CI. The keystore itself is never committed.
// ---------------------------------------------------------------------------
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingValue(key: String): String? =
    (signingProps.getProperty(key) ?: System.getenv(key))?.takeIf { it.isNotBlank() }

val keystorePath = signingValue("RESO_KEYSTORE")
val keystorePassword = signingValue("RESO_KEYSTORE_PASSWORD")
val keyAliasName = signingValue("RESO_KEY_ALIAS")
val keyPasswordValue = signingValue("RESO_KEY_PASSWORD")
val hasReleaseSigning = listOf(keystorePath, keystorePassword, keyAliasName, keyPasswordValue).all { it != null } &&
    file(keystorePath!!).exists()

// SHA-256 of the release signing certificate, baked into the app so it can
// detect a re-signed (tampered) APK at runtime.
val releaseCertSha256: String = if (hasReleaseSigning) {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    FileInputStream(file(keystorePath!!)).use { ks.load(it, keystorePassword!!.toCharArray()) }
    val cert = ks.getCertificate(keyAliasName) ?: error("Key alias $keyAliasName not found in keystore")
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
} else ""

val apiBaseUrl: String = (project.findProperty("RESO_API_BASE_URL") as String?)?.trim().orEmpty()
val certPins: String = (project.findProperty("RESO_CERT_PINS") as String?)?.trim().orEmpty()
require(apiBaseUrl.startsWith("https://") && apiBaseUrl.endsWith("/")) {
    "RESO_API_BASE_URL must be an https:// URL ending with /"
}

android {
    namespace = "net.rapti.resoshare"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.rapti.resoshare"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "CERT_PINS", "\"$certPins\"")
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertSha256\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    flavorDimensions += listOf("role", "env")
    productFlavors {
        create("user") {
            dimension = "role"
            applicationId = "net.rapti.resoshare"
            buildConfigField("boolean", "IS_ADMIN", "false")
        }
        create("admin") {
            dimension = "role"
            applicationId = "net.rapti.resoshare.admin"
            buildConfigField("boolean", "IS_ADMIN", "true")
        }
        create("live") {
            dimension = "env"
            buildConfigField("boolean", "DEMO", "false")
        }
        create("demo") {
            dimension = "env"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("boolean", "DEMO", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("MissingTranslation", "ExtraTranslation")
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}

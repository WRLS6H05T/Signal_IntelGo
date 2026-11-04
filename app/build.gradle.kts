import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * One signing key for every build, kept in the repository on purpose.
 *
 * A desktop build and CI then produce APKs with the same certificate, so each new build
 * installs *over* the previous one; with the automatic per-machine debug key, Android refuses
 * it as coming from a different app and the only way out is an uninstall.
 */
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val stableKeystoreFile = rootProject.file(
    keystoreProps.getProperty("storeFile") ?: "keystore/signal-intelgo.jks",
)
val hasStableKeystore = stableKeystoreFile.exists()

android {
    namespace = "dev.skyward.rf"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.skyward.rf"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        // A v2-only APK is rejected as "package appears to be invalid" by some OEM package
        // installers even though the signature itself is fine. Signing every scheme costs a
        // few kilobytes and installs on everything from Android 8 to the current release.
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        if (hasStableKeystore) {
            create("stable") {
                storeFile = stableKeystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Signed with the shared key when it is present, which is what makes a download
            // from GitHub a plain update rather than a reinstall.
            if (hasStableKeystore) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (hasStableKeystore) signingConfig = signingConfigs.getByName("stable")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SGP4/SDP4 orbit propagation (pure Java, TEME output; we do our own TEME -> WGS84).
    implementation("org.orekit:orekit:12.2.1")

    // Meshtastic mesh nodes arrive as plaintext JSON over the public MQTT broker.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    testImplementation("junit:junit:4.13.2")
}

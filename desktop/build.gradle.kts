import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // The satellite catalogue is the phone's tracker, which takes an OkHttp client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Serial ports for the ESP32 console and flasher: the desktop has no UsbManager, so the
    // board is reached through the OS's own COM port with DTR/RTS for the bootloader reset.
    implementation("com.fazecast:jSerialComm:2.11.0")

    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/**
 * Ships the phone's coastline asset with the desktop app.
 *
 * The asset is not copied into the repository a second time: the build points at the phone's
 * own copy, so the globe and the map cannot drift apart about where a coastline is.
 */
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("app/src/main/assets")) {
        // The same coastlines and the same sensor firmware the phone ships, so the desk can
        // flash a board with the image the phone would have written.
        include("world_land.txt", "firmware/**")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

/**
 * Puts the BLE bridge beside the packaged executable.
 *
 * The app image carries its own Java runtime, but it cannot carry a Python one, so the script is
 * shipped as a plain file next to the .exe and found at runtime by [dev.skyward.rf.desktop.ble.BleBridge].
 */
val stageBleBridge = tasks.register<Copy>("stageBleBridge") {
    from(layout.projectDirectory.file("tools/ble_bridge.py"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/Signal_IntelGo/tools"))
}

// The Compose plugin registers this task lazily, so it is matched rather than named: asking for
// it by name here would run before it exists and fail the whole configuration.
tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(stageBleBridge)
}

compose.desktop {
    application {
        mainClass = "dev.skyward.rf.desktop.MainKt"

        // The BLE bridge is a script the app starts; it travels beside the app in the install
        // image so an installed build does not depend on the source tree being present.
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Signal_IntelGo"
            packageVersion = "1.0.0"
            description = "Signal_IntelGo desktop host"
        }
    }
}

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

    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
            // Compose's default jlink image leaves out modules the app actually calls: the token
            // exchange uses java.net.http and the sign-in redirect listener uses the JDK's
            // com.sun.net.httpserver. Without them the packaged app dies on the first
            // NoClassDefFoundError, which is exactly what happened when it was first run.
            modules("java.net.http", "jdk.httpserver", "java.naming", "jdk.unsupported")
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Signal_IntelGo"
            packageVersion = "1.0.0"
            description = "Signal_IntelGo desktop host"
        }
    }
}

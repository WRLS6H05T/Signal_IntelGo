import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The command centre on the desk.
 *
 * It is a plain JVM application, not an Android one: a board on a USB cable, a mains supply and
 * a screen that is always on belong on a desk, and the phone is what you carry. Both ends speak
 * one protocol - the sweep plans and the console parser come from `:shared` - so the desk never
 * reads a board differently from the handset.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Compose Desktop draws on the AWT event thread; this puts Dispatchers.Main on it.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")

    // The JVM has no serial port API at all, and a board's console is a serial port: this is the
    // one dependency the desk cannot be written without.
    implementation("com.fazecast:jSerialComm:2.11.0")

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

compose.desktop {
    application {
        mainClass = "dev.skyward.rf.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SignalIntelGo"
            packageVersion = "1.0.0"
            description = "Signal IntelGo command centre: sensor boards, sweeps and sessions"
        }
    }
}

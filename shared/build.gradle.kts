import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The parts of the app that speak to something outside it: the peer wire format, its chunking,
 * the drive packaging and the ESP32 advertisement codec.
 *
 * They live here rather than in `:app` because the desktop host has to speak exactly the same
 * bytes as the phone. Two copies of a protocol is how the two ends start disagreeing about a
 * field nobody noticed, so the phone and the desktop compile the same files.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // Live satellite positions: TLE fetch over HTTP, propagation with SGP4/SDP4.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.orekit:orekit:12.2.1")
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

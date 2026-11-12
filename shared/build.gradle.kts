import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The parts of the app that speak to something outside it: the peer wire format, its chunking,
 * the drive packaging and the ESP32 advertisement codec.
 *
 * They live here rather than in `:app` because none of them touches Android: keeping them in a
 * plain JVM module means the wire format, the drive recorder and the propagation maths can be
 * unit tested on a build machine, without a device or an emulator.
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
    // Meshtastic and MeshCore are read from their public MQTT brokers. The source lives here
    // because it is plain JVM code, so the desk and the phone read one mesh, not two.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
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

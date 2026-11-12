plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    // Compose on the desktop. 1.6.11 is the release paired with Kotlin 1.9.24, the compiler the
    // app already uses, so the two modules are compiled by one toolchain rather than two.
    id("org.jetbrains.compose") version "1.6.11" apply false
}

// Versions: AGP 9.3.1 (built-in Kotlin), Kotlin 2.4.21, Gradle 9.5.0 (see gradle/wrapper).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Pin the Kotlin Gradle plugin used by AGP's built-in Kotlin so it matches the Compose compiler plugin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

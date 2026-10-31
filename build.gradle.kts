// Top-level build file.
//
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the kotlin-android plugin is NOT applied.
// AGP brings its own Kotlin Gradle plugin; to pin a newer Kotlin / KSP / Compose-compiler version,
// the Android docs say to put them on the buildscript classpath. Modules then apply them without versions.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.kotlin.compose.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}

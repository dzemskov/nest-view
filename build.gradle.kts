// Top-level build file. AGP 9 compiles Kotlin itself ("built-in Kotlin"), so Android modules
// do not apply org.jetbrains.kotlin.android. Declaring kotlin-jvm here also lifts the Kotlin
// Gradle plugin on the build classpath to the version in the catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

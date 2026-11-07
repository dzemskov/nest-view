import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Device Access settings come from local.properties (never committed). See docs/SETUP.md.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val sdmProjectId: String = localProps.getProperty("SDM_PROJECT_ID", "")
val oauthClientId: String = localProps.getProperty("OAUTH_CLIENT_ID", "")
val oauthClientSecret: String = localProps.getProperty("OAUTH_CLIENT_SECRET", "")
// Android OAuth clients redirect to the reversed client ID scheme.
val redirectScheme: String = if (oauthClientId.endsWith(".apps.googleusercontent.com")) {
    "com.googleusercontent.apps." + oauthClientId.removeSuffix(".apps.googleusercontent.com")
} else {
    "app.nestview.oauth"
}
val oauthRedirectUri: String = localProps.getProperty("OAUTH_REDIRECT_URI", "$redirectScheme:/oauth2redirect")

android {
    namespace = "app.nestview"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.nestview"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SDM_PROJECT_ID", "\"$sdmProjectId\"")
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
        buildConfigField("String", "OAUTH_CLIENT_SECRET", "\"$oauthClientSecret\"")
        buildConfigField("String", "OAUTH_REDIRECT_URI", "\"$oauthRedirectUri\"")
        manifestPlaceholders["appAuthRedirectScheme"] = redirectScheme
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal sideloaded build: sign release with the debug key unless you add your own.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:sdm"))
    implementation(project(":core:streaming"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:monitor"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
}

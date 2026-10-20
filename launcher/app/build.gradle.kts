import java.util.Properties

// Sports settings live in local.properties (gitignored, see local.properties.example)
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.prince.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.prince.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SPORTS_LEAGUES", "\"${localProperties.getProperty("sports.leagues", "")}\"")
        buildConfigField("String", "SPORTS_TEAMS", "\"${localProperties.getProperty("sports.teams", "")}\"")
    }

    buildTypes {
        release {
            // Sideloaded only, so the debug key is fine; R8 makes Compose noticeably faster on low-end TV hardware
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.palette)
}

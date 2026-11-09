plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lvlahti.pochi"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lvlahti.pochi"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
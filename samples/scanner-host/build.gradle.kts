// Sample host: proves the scanners are reusable on their own. It depends only on scanner modules'
// public APIs and the shared UI kit. It must never depend on :app, :data:local or :domain:expenses;
// CI fails the build if it does (see .github/workflows/ci.yml, "Check sample host isolation").
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.receiptscan.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.receiptscan.sample.scannerhost"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("testRelease") {
            storeFile = rootProject.file("keystore/receiptscan-test-release.jks")
            storePassword = "receiptscan-test"
            keyAlias = "receiptscan-test"
            keyPassword = "receiptscan-test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("testRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }

    lint { abortOnError = false }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(project(":core:design-system"))
    implementation(project(":core:scanner-ui"))
    implementation(project(":core:capture"))
    // Three embeddable scanners: two ML Kit based, one with a bundled TensorFlow Lite model.
    implementation(project(":scanner:qr"))
    implementation(project(":scanner:text"))
    implementation(project(":scanner:plant"))
}

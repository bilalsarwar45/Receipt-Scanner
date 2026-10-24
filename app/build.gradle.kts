plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.receiptscan.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.receiptscan.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // TEST key committed to the repo so CI can produce an installable release build for sideloading.
        // It is NOT a Play upload key. See docs/RELEASE_SIGNING.md before publishing anywhere.
        create("testRelease") {
            storeFile = rootProject.file("keystore/receiptscan-test-release.jks")
            storePassword = "receiptscan-test"
            keyAlias = "receiptscan-test"
            keyPassword = "receiptscan-test"
        }
        // A real upload key can be supplied through environment variables without changing this file.
        val uploadStore = System.getenv("RS_UPLOAD_STORE_FILE")
        if (uploadStore != null) {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = System.getenv("RS_UPLOAD_STORE_PASSWORD")
                keyAlias = System.getenv("RS_UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("RS_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("testRelease")
        }
        debug {
            applicationIdSuffix = ".debug"
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

    packaging {
        jniLibs { useLegacyPackaging = false }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
        xmlReport = true
        htmlReport = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api", "androidx.compose.foundation.ExperimentalFoundationApi", "androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(project(":core:design-system"))
    implementation(project(":core:money-time"))
    implementation(project(":core:scanner-contracts"))
    implementation(project(":domain:expenses"))
    implementation(project(":domain:insights"))
    implementation(project(":domain:receipt-actions"))
    implementation(project(":data:local"))
    implementation(project(":core:capture"))
    implementation(project(":core:ocr"))
    implementation(project(":core:model-packs"))
    implementation(project(":core:scanner-ui"))
    implementation(project(":feature:commands"))
    implementation(project(":feature:voice-input"))
    implementation(project(":feature:documents"))
    implementation(project(":scanner:receipt"))
    implementation(project(":scanner:invoice"))
    implementation(project(":scanner:text"))
    implementation(project(":scanner:product"))
    implementation(project(":scanner:nutrition"))
    implementation(project(":scanner:expiry"))
    implementation(project(":scanner:food"))
    implementation(project(":scanner:qr"))
    implementation(project(":scanner:translation"))
    implementation(project(":scanner:math"))
    implementation(project(":scanner:counter"))
    implementation(project(":scanner:plant"))
    implementation(project(":scanner:wifi"))
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

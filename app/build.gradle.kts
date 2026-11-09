plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.kotoba"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kotoba"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Release signing comes from the environment (CI secrets or a local keystore); without it the release APK is unsigned.
    val keystorePath = System.getenv("KOTOBA_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KOTOBA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KOTOBA_KEY_ALIAS") ?: "kotoba"
                keyPassword = System.getenv("KOTOBA_KEY_PASSWORD") ?: System.getenv("KOTOBA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // -PdebugSignRelease lets CI smoke-test the minified build without the real key.
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
            else if (project.hasProperty("debugSignRelease")) signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // PdfBox pulls in bouncycastle; its post-quantum parameter tables (~8 MB) are never used.
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "org/bouncycastle/pqc/**")
    }
    // ML Kit OCR/translation native libs dominate the size, so ship one APK per ABI plus a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    // Documents: EPUB/HTML, PDF text, Anki .apkg (zstd for modern collections)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(variantOf(libs.zstd.jni) { artifactType("aar") })

    // On-device OCR (Latin, Japanese, Chinese, Korean) and translation
    implementation(libs.mlkit.text.latin)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.mlkit.translate)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}

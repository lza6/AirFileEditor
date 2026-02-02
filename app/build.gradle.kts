

plugins {
    alias(libs.plugins.android.application)
    kotlin("android") version "2.1.0"
}

android {
    namespace = "com.example.tfgwj"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tfgwj"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
        aidl = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation(libs.androidx.constraintlayout)
    
    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // DataStore
    implementation(libs.androidx.datastore.preferences)
    
    // DocumentFile
    implementation(libs.androidx.documentfile)
    
    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    
    // Archive extraction
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    
    // WorkManager
    implementation(libs.work.runtime.ktx)
    
    // Markdown rendering
    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
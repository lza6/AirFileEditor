plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.tfgwj.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.testLogging {
                    events("passed", "skipped", "failed")
                    setExceptionFormat("full")
                }
                // Windows 避免二进制锁问题：每次测试前清理结果目录
                it.doFirst {
                    val resultsDir = it.reports.junitXml.outputLocation.get().asFile
                    if (resultsDir.exists()) {
                        resultsDir.deleteRecursively()
                    }
                }
            }
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.markwon.core)
    implementation(libs.androidx.appcompat)

    // Test Dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation("androidx.test:core:1.6.1")
}
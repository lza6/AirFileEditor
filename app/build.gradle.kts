

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    kotlin("android") version "2.0.21"
    jacoco
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

android {
    namespace = "com.example.tfgwj"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tfgwj"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "16.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("${System.getenv("LOCALAPPDATA")}/Android/Sdk/tfgwj-release-key.jks")
            storePassword = "tfgwj2026"
            keyAlias = "tfgwj"
            keyPassword = "tfgwj2026"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    @Suppress("DEPRECATION")
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
        aidl = true
        compose = true
    }

    // Jacoco 配置 (使用 testCoverage)
    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }
}

// Jacoco 测试覆盖率报告任务
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val fileFilter =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*Test*.*",
            "**/databinding/**",
            "**/viewbinding/**",
        )

    val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
    val debugTree =
        fileTree("$buildDirPath/tmp/kotlin-classes/debug") {
            exclude(fileFilter)
        }

    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(files(debugTree))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get().asFile) {
            include("jacoco/testDebugUnitTest.exec")
        },
    )
}

// Jacoco 验证任务（设置覆盖率阈值）
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    violationRules {
        rule {
            element = "CLASS"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.70".toBigDecimal()
            }
            excludes =
                listOf(
                    "*.BuildConfig",
                    "*.R",
                    "*.R\$*",
                    "*.Manifest*",
                )
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":core"))

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

    // OkHttp - V7.0.0 Network Layer Upgrade
    implementation(libs.okhttp)

    // Compose - V11.0.0 UI Modernization
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Test Dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Detekt 静态代码分析配置
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

// Ktlint 代码格式化配置
ktlint {
    version.set("1.2.1")
    android.set(true)
    verbose.set(true)
    outputToConsole.set(true)
    outputColorName.set("RED")

    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// 代码质量检查任务（运行所有静态分析）
tasks.register("checkQuality") {
    dependsOn("detekt", "ktlintCheck", "jacocoTestCoverageVerification")
    group = "verification"
    description = "运行所有代码质量检查（Detekt + Ktlint + Jacoco）"
}

// 自动格式化代码
tasks.register("formatCode") {
    dependsOn("ktlintFormat")
    group = "formatting"
    description = "自动格式化代码（Ktlint）"
}

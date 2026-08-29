import java.util.Properties

// 从 keystore.properties（本地，gitignore）加载签名凭据；缺失则回退环境变量/默认
fun loadKeystoreProperties(): Properties {
    val props = Properties()
    val file = File(rootProject.file("keystore.properties").absolutePath)
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    if (props.getProperty("storePassword").isNullOrEmpty()) {
        System.getenv("TFGWJ_STORE_PASSWORD")?.let { props.setProperty("storePassword", it) }
    }
    if (props.getProperty("keyPassword").isNullOrEmpty()) {
        System.getenv("TFGWJ_KEY_PASSWORD")?.let { props.setProperty("keyPassword", it) }
    }
    return props
}

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
        versionCode = 11
        versionName = "17.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            val ks = loadKeystoreProperties()
            storeFile = file(ks.getProperty("storeFile") ?: "${System.getenv("LOCALAPPDATA")}/Android/Sdk/tfgwj-release-key.jks")
            storePassword = requireNotNull(ks.getProperty("storePassword")) { "缺少 storePassword（请配置 keystore.properties）" }
            keyAlias = ks.getProperty("keyAlias") ?: "tfgwj"
            keyPassword = requireNotNull(ks.getProperty("keyPassword")) { "缺少 keyPassword（请配置 keystore.properties）" }
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
// 覆盖率统计范围排除纯 UI 层（Compose 组件/主题/动画/布局/无障碍/对话框），
// 这类代码需仪器测试覆盖；单测门禁聚焦业务逻辑（mvi/security 等）。
val jacocoClassFilters =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "**/databinding/**",
        "**/viewbinding/**",
        // 纯 UI 层 — 由模拟器 E2E 与人工验收覆盖，不纳入单测门禁
        "**/ui/components/**",
        "**/ui/compose/**",
        "**/ui/theme/**",
        "**/ui/animation/**",
        "**/ui/layout/**",
        "**/ui/accessibility/**",
        "**/ui/navigation/**",
        "**/dialog/**",
        "**/MainActivity*",
        "**/PerformanceDashboardActivity*",
        // View/Dialog/Controller 依赖真实 Android Window，需仪器测试覆盖
        "**/ui/FloatingBall*",
    )

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
    val debugTree =
        fileTree("$buildDirPath/tmp/kotlin-classes/debug") {
            exclude(jacocoClassFilters)
        }

    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(files(debugTree))
    // AGP 8.x 将 exec 写到 outputs/unit_test_code_coverage/debugUnitTest/（历史路径 build/jacoco 已废弃）
    executionData.setFrom(
        fileTree(layout.buildDirectory.get().asFile) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
}

// Jacoco 验证任务（设置覆盖率阈值）
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("jacocoTestReport")

    // 与报告任务对齐：指向 AGP 8.x 实际输出的 exec 文件（默认指向遗留 build/jacoco 会因找不到输入被禁用导致 SKIPPED）
    executionData.setFrom(
        fileTree(layout.buildDirectory.get().asFile) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )

    // 必须显式设置 classDirectories，否则任务无类可验会空洞通过
    val buildDirPath = layout.buildDirectory.get().asFile.absolutePath
    classDirectories.setFrom(
        files(
            fileTree("$buildDirPath/tmp/kotlin-classes/debug") {
                exclude(jacocoClassFilters)
            },
        ),
    )

    violationRules {
        rule {
            // BUNDLE 聚合口径：整体达标即可，避免单类波动阻塞流水线
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // 棘轮基线（2026-08-26 实测）：纳入门禁范围实测行覆盖 27.8%。
                // 历史上 80% 阈值因门禁失效从未真正执行过；本值为首个真实基线，
                // 只允许上调不允许下调，后续随测试补全逐步提升至 80%。
                minimum = "0.27".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                // 分支棘轮基线（同上实测 30.8%）
                minimum = "0.30".toBigDecimal()
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

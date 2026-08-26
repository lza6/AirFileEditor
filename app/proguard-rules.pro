# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ========== V15 发布混淆规则 ==========

# 保留 Shizuku 相关类（反射调用）
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }

# 保留 Zip4j 与 Commons-Compress（内部使用反射）
-keep class net.lingala.zip4j.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }

# 保留 JSON 数据模型（DataStore/历史记录反序列化）
-keep class com.example.tfgwj.data.** { *; }
-keep class com.example.tfgwj.domain.model.** { *; }
-keep class com.example.tfgwj.model.** { *; }

# 保留 AIDL 生成的 Binder 接口
-keep interface com.example.tfgwj.IFileOperationService { *; }
-keep class com.example.tfgwj.IFileOperationService$** { *; }
-keep interface com.example.tfgwj.ICopyCallback { *; }
-keep interface com.example.tfgwj.IDeleteCallback { *; }

# 保留 Service 与 WorkManager Worker（Manifest/反射注册）
-keep class com.example.tfgwj.shizuku.FileOperationService { *; }
-keep class com.example.tfgwj.worker.FileReplaceWorkerV2 { *; }
-keep class com.example.tfgwj.worker.*Strategy { *; }

# 保留 ViewBinding/DataBinding 生成类
-keep class com.example.tfgwj.databinding.** { *; }

# 保留 Compose 编译器生成的类
-keep class androidx.compose.** { *; }

# 保留鸿蒙反射检测方法签名
-keepclassmembers class * {
    *** *getOsBrand();
}

# 保留 Kotlin coroutines 与 Flow（反射/协程恢复）
-keep class kotlinx.coroutines.** { *; }

# 保留 OkHttp 内部类（网络层）
-keep class okhttp3.** { *; }
-keep class retrofit2.** { *; }

# 保留 Log/错误信息行号便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留 WorkManager 序列化
-keep class androidx.work.** { *; }
-keep class com.example.tfgwj.performance.scheduler.** { *; }

# Apache Commons Compress R8 兼容（引用了 asm 但运行时不需要）
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.harmony.**

# Commons-Compress 可选编解码器（未引入依赖，仅在特定格式使用时需要）
-dontwarn com.github.luben.zstd.**
-dontwarn org.apache.commons.codec.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.xz.**

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

# Zstd JNI
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# XZ (LZMA)
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.** { *; }

# Kotlin Serialization (if used in future)
-keepattributes *Annotation*, EnclosingMethod, InnerClasses, Signature
-keep class kotlinx.serialization.** { *; }

# OkHttp ProGuard Rules
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepclassmembers class * extends javax.net.ssl.SSLSocketFactory {
    public <init>(...);
}
-keepclassmembers class * extends javax.net.ssl.X509TrustManager {
    public <init>(...);
}

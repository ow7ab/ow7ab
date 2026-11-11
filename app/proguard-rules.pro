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
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class kotlin.Metadata { *; }

# Room Database Entities & DAOs (Room v11)
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.example.data.local.** { *; }

# Domain & Architecture Contracts
-keep class com.example.core.architecture.** { *; }
-keep class com.example.data.model.** { *; }

# Firebase Firestore, Auth & Cloud Sync Models
-keep class com.example.data.remote.** { *; }
-keepclassmembers class com.example.data.** {
    public <init>(...);
    public <fields>;
    public <methods>;
}
-keepclassmembers class com.example.util.UpdateManager$* {
    public <init>(...);
    public <fields>;
    public <methods>;
}

# Moshi & Kotlin Reflection
-dontwarn com.squareup.moshi.**
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
    @com.squareup.moshi.* <fields>;
}

# Retrofit & OkHttp networking interfaces
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**


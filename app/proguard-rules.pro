# Readable crash stack traces (Crashlytics + mapping.txt)
-keepattributes SourceFile,LineNumberTable
# Start.io SDK: conservative rules (their docs say the SDK is already obfuscated). Revisit if the release build reports missing classes.
-keep class com.startapp.** { *; }
-dontwarn com.startapp.**
-keep class com.truenet.** { *; }
-dontwarn android.webkit.JavascriptInterface

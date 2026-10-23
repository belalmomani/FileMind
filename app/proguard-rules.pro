# Readable crash stack traces (Crashlytics + mapping.txt)
-keepattributes SourceFile,LineNumberTable
# Start.io SDK: conservative rules (their docs say the SDK is already obfuscated). Revisit if the release build reports missing classes.
-keep class com.startapp.** { *; }
-dontwarn com.startapp.**
-keep class com.truenet.** { *; }
-dontwarn android.webkit.JavascriptInterface
# 7Z/RAR readers: optional codecs/classes referenced by commons-compress are absent on purpose.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.objectweb.asm.**
-dontwarn org.slf4j.**
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }
-dontwarn com.github.junrar.**

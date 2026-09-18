# Project-specific ProGuard/R8 rules.

# Preserve line number information so opt-in crash reports are useful.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- cryptolib (the vault format) ---------------------------------------------------------
# masterkey.cryptomator is (de)serialised by Gson from field names; keep them as written.
-keep class org.cryptomator.cryptolib.common.MasterkeyFile { *; }
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
# CryptorProvider implementations are instantiated directly (CryptomatorVault.kt), but the
# library also registers them for ServiceLoader; keep both so either path works.
-keep class org.cryptomator.cryptolib.v1.CryptorProviderImpl { *; }
-keep class org.cryptomator.cryptolib.v2.CryptorProviderImpl { *; }
# Guava and slf4j reference JVM-only classes that never exist on Android.
-dontwarn com.google.j2objc.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.lang.model.**
-dontwarn org.slf4j.**
-dontwarn sun.misc.**

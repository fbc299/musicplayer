# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Media3
-keep class androidx.media3.** { *; }

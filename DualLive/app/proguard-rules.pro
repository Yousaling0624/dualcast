# Media3 / ExoPlayer 自带 consumer rules，这里只补充反射与可选依赖相关的规则
-dontwarn org.brotli.**
-keep class org.brotli.** { *; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keepattributes Signature
-keepattributes *Annotation*

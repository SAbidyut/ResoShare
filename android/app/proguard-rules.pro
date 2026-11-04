# ---- ResoShare R8 rules ----
# Strip all android.util.Log calls from release builds (no data leaks via logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# kotlinx.serialization: keep generated serializers of our DTOs.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class net.rapti.resoshare.** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class net.rapti.resoshare.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.rapti.resoshare.**$$serializer { *; }

# Retrofit service interfaces (generic return types must survive R8 full mode).
-keep,allowobfuscation interface net.rapti.resoshare.data.api.** { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Obfuscate aggressively; repackage everything into a flat namespace.
-repackageclasses 'r'
-allowaccessmodification

# kotlinx.serialization generates a synthetic Companion.serializer() per @Serializable class and
# looks it up reflectively. R8 cannot see that call, so without these the release build compiles
# cleanly and then throws SerializationException on the first API response — the worst possible
# failure mode, because it only appears in the build nobody tests before shipping.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, AnnotationDefault
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
    <fields>;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Every wire model in the app. Keeping them by package is more robust than by annotation alone,
# because @Serializable enums and nested types are reached through generated code R8 also rewrites.
-keep,includedescriptorclasses class space.gexemy.tasteroute.data.**$$serializer { *; }
-keepclassmembers class space.gexemy.tasteroute.data.** {
    *** Companion;
    <fields>;
}

# osmdroid loads tile sources and overlays reflectively from its own configuration, and reads
# BuildConfig via reflection to decide on its user agent.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Play Services location is consumed through generated stubs; the default rules cover it, but the
# location callback is registered reflectively by the fused provider.
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# Compose keeps its own rules via the AGP-bundled consumer file. Nothing to add here — and adding
# a blanket -keep on the UI package would defeat the shrinking this app actually needs, since
# material-icons-extended is most of the APK before R8 runs.

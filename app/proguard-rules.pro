# Navigation type-safe routes are @Serializable objects/classes.
-keep @kotlinx.serialization.Serializable class dev.musicstream.app.** { *; }
-keepclassmembers class dev.musicstream.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
# Retrofit interfaces with suspend functions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn org.slf4j.**

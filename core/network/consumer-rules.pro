# kotlinx.serialization DTOs (core:network and every feature's data layer)
-keepclassmembers @kotlinx.serialization.Serializable class dev.musicstream.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.musicstream.**$$serializer { *; }

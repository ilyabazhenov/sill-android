# kotlinx.serialization: keep the generated serializers of the wire types.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class dev.remotemac.protocol.**$$serializer { *; }
-keepclassmembers class dev.remotemac.protocol.** { *** Companion; }
-keepclasseswithmembers class dev.remotemac.protocol.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.remotemac.app.**$$serializer { *; }
-keepclassmembers class dev.remotemac.app.** { *** Companion; }
-keepclasseswithmembers class dev.remotemac.app.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.conscrypt.**

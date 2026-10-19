# kotlinx.serialization: keep the generated serializers of the wire types.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class dev.sash.protocol.**$$serializer { *; }
-keepclassmembers class dev.sash.protocol.** { *** Companion; }
-keepclasseswithmembers class dev.sash.protocol.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.sash.app.**$$serializer { *; }
-keepclassmembers class dev.sash.app.** { *** Companion; }
-keepclasseswithmembers class dev.sash.app.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.conscrypt.**

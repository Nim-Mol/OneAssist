# SQLCipher uses JNI
-keep class net.zetetic.database.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class app.oneassist.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.receiptscan.**$$serializer { *; }
-keepclassmembers class com.receiptscan.** { *** Companion; }
# MediaPipe and ML Kit use reflection/JNI.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.auto.value.**

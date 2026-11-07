# libwebrtc calls into Java through JNI; keep its classes and native-facing members.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization: keep generated serializers for our models.
-keepclassmembers class app.nestview.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.nestview.**$$serializer { *; }

# Tink / AppAuth
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

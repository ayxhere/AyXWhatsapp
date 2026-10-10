# AyX WhatsApp — ProGuard / R8 rules (release builds only).
#
# If the release APK misbehaves (ClassNotFoundException, missing JNI methods at
# runtime), set isMinifyEnabled back to false in app/build.gradle.kts and report
# it — then tighten these keeps instead of shipping an unshrunk APK.

# The whole app package is kept (deliberately conservative): the embedded Node
# runtime talks to Kotlin via JNI (NodeBridge) and reflection is used in places;
# shrinking app classes is not worth the risk for this build.
-keep class ayx.whatsapp.** { *; }

# JNI bridge to libnode.so — the native side looks these methods up by name.
# (Also covered by the blanket keep above; listed explicitly for documentation.)
-keepclasseswithmembernames class ayx.whatsapp.NodeBridge {
    native <methods>;
}

# WorkManager instantiates AyxUpdatesWorker by name from the scheduler.
# (Also covered by the blanket keep above; listed explicitly for documentation.)
-keep class ayx.whatsapp.group.AyxUpdatesWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Libraries ---------------------------------------------------------------
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

-keep class androidx.emoji2.** { *; }
-dontwarn androidx.emoji2.**

-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# --- Deliberately NOT added ---------------------------------------------------
# No certificate pinning: api.imayx.in is served through a Cloudflare Tunnel and
# edge certificates rotate. Pinning would break the app on rotation, so the
# standard Android CA trust store is used instead.

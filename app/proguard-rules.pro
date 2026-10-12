# AyX WhatsApp — ProGuard / R8 rules (release builds only).
#
# Narrowly-scoped keeps. The old blanket "-keep class ayx.whatsapp.** { *; }"
# defeated obfuscation for the entire app; it is replaced below with keeps for
# actual JNI entry points, Android components, serialization and reflection.
#
# If the release APK misbehaves (ClassNotFoundException, missing JNI methods at
# runtime), report it — then tighten these keeps instead of shipping unshrunk.

# --- JNI bridge to libnode.so — native side looks these methods up by name ---
-keepclasseswithmembernames class ayx.whatsapp.NodeBridge {
    native <methods>;
}

# --- Android components (instantiated by the framework) ---
-keep class ayx.whatsapp.MainActivity { *; }
-keep class ayx.whatsapp.NodeService { *; }
-keep class ayx.whatsapp.ReplyReceiver { *; }

# --- WorkManager (instantiated by name from the scheduler) ---
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# --- Data classes used in JSON serialization (Gson/org.json reflection) ---
# Keep fields for model classes serialized to/from the backend.
-keepclassmembers class ayx.whatsapp.** {
    <fields>;
}

# --- Libraries: keep only what R8 cannot infer ---
# Compose: keep composable metadata
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Coroutines: keep service-loader entries
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ZXing (QR): keep core decoder
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Emoji2
-keep class androidx.emoji2.** { *; }
-dontwarn androidx.emoji2.**

# Security-crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# WorkManager internals
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# Tink (pulled in by security-crypto) references javax.annotation.concurrent.GuardedBy,
# a compile-time-only annotation absent from the APK — safe to silence for R8.
-dontwarn com.google.crypto.tink.**
-dontwarn javax.annotation.concurrent.**

# --- Release hardening ---
# Strip debug logging from release builds (Log.d/v). Keep w/e/i for diagnostics.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# --- Deliberately NOT added ---------------------------------------------------
# No certificate pinning: api.imayx.in is served through a Cloudflare Tunnel and
# edge certificates rotate. Pinning would break the app on rotation, so the
# standard Android CA trust store is used instead.
# No blanket "-keep class ayx.whatsapp.**" — see header.

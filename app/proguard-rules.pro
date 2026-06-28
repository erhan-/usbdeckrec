# USB DeckRec ProGuard Rules

# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Oboe native library
-keep class com.google.oboe.** { *; }
-dontwarn com.google.oboe.**

# Keep Room database entities and DAOs
-keep class com.usbdeckrec.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Keep data classes used by Gson/Serialization
-keep class com.usbdeckrec.model.** { *; }
-keep class com.usbdeckrec.audio.MixerProfile { *; }
-keep class com.usbdeckrec.audio.AudioFormat { *; }

# Keep Compose runtime
-dontwarn androidx.compose.**

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# General Android rules
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.app.Activity

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

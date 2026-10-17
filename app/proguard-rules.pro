# WebUntis Dashboard — ProGuard Rules

# ── Kotlin & coroutines ───────────────────────────────────────────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod, Exceptions, *Annotation*

# ── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# Keep service method annotations (Retrofit reads them at runtime via reflection)
-keepclassmembers class * {
    @okhttp3.* <methods>;
}

# ── Retrofit ─────────────────────────────────────────────────────────────────
-dontwarn retrofit2.**
# Retrofit uses reflection on interface methods; keep interface declarations
-keep,allowobfuscation interface com.webuntis.dashboard.api.WebUntisService
-keepclassmembers,allowobfuscation interface com.webuntis.dashboard.api.WebUntisService {
    <methods>;
}

# ── Gson / JSON models ────────────────────────────────────────────────────────
# All data classes used for JSON (de)serialization must keep their fields.
# IMPORTANT: field names must NOT be obfuscated, since Gson maps JSON keys to
# fields by NAME via reflection — renaming a field silently breaks parsing
# (fields quietly stay null instead of throwing), which is very hard to notice.
-keep class com.webuntis.dashboard.model.** { *; }
-keepclassmembers class com.webuntis.dashboard.model.** { *; }
# ChangeSnapshot/ChangeLogEntry are also (de)serialized with Gson (see
# ChangeSnapshot.parse / SessionManager.lastNotifiedSnapshot) but live outside
# the model package — without this, R8 is free to merge/restructure these
# classes in release builds, which produced a ClassCastException the moment
# MainActivity.checkAndShowUnseenChanges() touched the deserialized
# ChangeLogEntry list, since Gson's reflective result no longer matched the
# class the call site expected.
-keep class com.webuntis.dashboard.api.ChangeSnapshot { *; }
-keep class com.webuntis.dashboard.api.ChangeLogEntry { *; }
# Everything else that Gson persists/reads and that lives OUTSIDE the model package. Their
# fields carry generics (List<Lesson>, ...); R8 (full mode) strips the generic Signature of
# members of classes it isn't told to keep, so Gson then reads the list elements as raw
# LinkedTreeMap and the first access fails with
#   ClassCastException: com.google.gson.internal.LinkedTreeMap cannot be cast to ...model.Lesson
# (seen on timetable start when the disk cache is read). Keeping them also pins the field names,
# which matters for data written by one build and read by the next (disk cache, saved accounts).
-keep class com.webuntis.dashboard.api.TimetableDay { *; }
-keep class com.webuntis.dashboard.api.WebUntisRepository$TeachingContentCache { *; }
-keep class com.webuntis.dashboard.api.SessionManager$SecondAccount { *; }
# kotlin.Pair is used as Gson target type (Pair<List<Homework>, Map<...>> etc. in the disk cache).
-keep class kotlin.Pair { *; }
# School search (public WebUntis endpoint): request/response models are @Keep-annotated; the
# Retrofit interface is kept like WebUntisService above.
-keep,allowobfuscation interface com.webuntis.dashboard.api.SchoolSearchService
-keepclassmembers,allowobfuscation interface com.webuntis.dashboard.api.SchoolSearchService {
    <methods>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers enum com.webuntis.dashboard.model.** { *; }
# Gson's reflective TypeAdapterFactory / TypeToken machinery
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── Navigation Safe Args ──────────────────────────────────────────────────────
-keep class * extends androidx.navigation.NavArgs

# ── Security: strip logging from release builds ───────────────────────────────
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── WorkManager / Room ────────────────────────────────────────────────────────
# WorkManager's WorkDatabase is a Room database; Room instantiates the generated
# WorkDatabase_Impl via reflection (getDeclaredConstructor()). R8 in AGP 9 strips that no-arg
# constructor, so WebUntisApp.onCreate crashed on start with
#   NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# Rules are name-based on purpose: Room is only a runtime dependency of WorkManager, so
# androidx.room.* classes are not resolvable from this module's rules.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class androidx.work.impl.**_Impl { <init>(); }
-keep class androidx.work.impl.WorkDatabase { *; }
-dontwarn androidx.room.**

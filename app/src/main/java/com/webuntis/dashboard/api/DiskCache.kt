package com.webuntis.dashboard.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.google.gson.Strictness
import com.google.gson.ToNumberPolicy
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.lang.reflect.Type
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent "last known content" store behind [WebUntisRepository]'s in-memory caches.
 *
 * The in-memory caches die with the process, so after every cold start each screen had to
 * show a spinner until the network answered. This keeps the last successful result of each
 * data category as a small JSON file, so the UI can show it immediately (stale-while-
 * revalidate) while the fresh data loads silently in the background.
 *
 * - Lives in `noBackupFilesDir`: survives restarts, is excluded from Android auto-backup.
 * - Every file carries the app's versionCode; a file written by another app version is
 *   treated as a miss (model classes may have changed shape, or R8 renamed their fields).
 * - Never throws: any read/write problem is a cache miss, and a corrupt file is deleted.
 * - Keys are scoped by the repository (server + user + active account), so a different login
 *   can never see another login's data.
 */
@Singleton
class DiskCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "DiskCache"

    private val dir: File by lazy {
        File(context.noBackupFilesDir, "data_cache").also { it.mkdirs() }
    }

    private val appVersion: Long by lazy {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        } catch (e: Exception) { 0L }
    }

    // TimetableDay holds a java.time.LocalDate, which Gson can't (and on newer runtimes
    // mustn't) handle reflectively — store it as an ISO string.
    private val gson: Gson = GsonBuilder()
        .setStrictness(Strictness.LENIENT)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .registerTypeAdapter(LocalDate::class.java,
            JsonSerializer<LocalDate> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(LocalDate::class.java,
            JsonDeserializer<LocalDate> { json, _, _ -> LocalDate.parse(json.asString) })
        .create()

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    /** Returns (fetchedAtMs, data) or null on any miss/mismatch/corruption. */
    @Synchronized
    fun <T> read(key: String, type: Type): Pair<Long, T>? {
        val f = file(key)
        if (!f.exists()) return null
        return try {
            val root = gson.fromJson(f.readText(), JsonObject::class.java)
            if (root.get("v")?.asLong != appVersion) { f.delete(); return null }
            val fetchedAt = root.get("fetchedAt").asLong
            val data: T = gson.fromJson(root.get("data"), type) ?: return null
            fetchedAt to data
        } catch (e: Exception) {
            Log.w(tag, "Discarding unreadable cache file ${f.name}", e)
            f.delete()
            null
        }
    }

    @Synchronized
    fun write(key: String, fetchedAt: Long, data: Any?) {
        if (data == null) return
        try {
            val root = JsonObject().apply {
                addProperty("v", appVersion)
                addProperty("fetchedAt", fetchedAt)
                add("data", gson.toJsonTree(data))
            }
            val target = file(key)
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeText(gson.toJson(root))
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        } catch (e: Exception) {
            Log.w(tag, "Could not persist cache entry $key", e)
        }
    }

    /** Deletes every entry whose file name contains [fragment]. */
    @Synchronized
    fun deleteContaining(fragment: String) {
        val safe = fragment.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        dir.listFiles()?.filter { it.name.contains(safe) }?.forEach { it.delete() }
    }

    @Synchronized
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

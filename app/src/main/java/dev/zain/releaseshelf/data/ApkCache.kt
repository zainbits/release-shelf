package dev.zain.releaseshelf.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable on-device cache for verified release APKs.
 *
 * Entries survive install failures and app restarts so the user can download now
 * and install later, or retry install without re-downloading.
 */
class ApkCache(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val directory: File = File(appContext.filesDir, "updates").also { it.mkdirs() }

    fun destination(release: ReleaseInfo): File {
        val safeRepo = release.repository.fullName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeTag = release.tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val safeApk = release.apk.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "${safeRepo}__${safeTag}__${safeApk}")
    }

    fun find(release: ReleaseInfo): File? {
        val entry = entries().firstOrNull { it.matches(release) } ?: return null
        val file = File(directory, entry.fileName)
        if (!file.isFile || file.length() == 0L) {
            remove(entry.repositoryFullName, entry.tag)
            return null
        }
        return file
    }

    fun isCached(release: ReleaseInfo): Boolean = find(release) != null

    fun markCached(release: ReleaseInfo, file: File) {
        check(file.isFile && file.length() > 0) { "Cannot cache a missing APK" }
        val relative = file.name
        val updated = entries()
            .filterNot { it.repositoryFullName == release.repository.fullName }
            .toMutableList()
        updated.add(
            CacheEntry(
                repositoryFullName = release.repository.fullName,
                tag = release.tag,
                versionName = release.versionName,
                versionCode = release.versionCode,
                packageName = release.packageName,
                sha256 = release.sha256,
                apkName = release.apk.name,
                fileName = relative,
                sizeBytes = file.length(),
                cachedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        writeEntries(updated)
    }

    fun remove(release: ReleaseInfo) {
        remove(release.repository.fullName, release.tag)
    }

    fun remove(repositoryFullName: String, tag: String? = null) {
        val remaining = mutableListOf<CacheEntry>()
        for (entry in entries()) {
            val match = entry.repositoryFullName == repositoryFullName &&
                (tag == null || entry.tag == tag)
            if (match) {
                File(directory, entry.fileName).delete()
                File(directory, "${entry.fileName}.part").delete()
            } else {
                remaining.add(entry)
            }
        }
        writeEntries(remaining)
    }

    fun pruneStale(currentReleases: List<ReleaseInfo>) {
        val keep = currentReleases.map { it.repository.fullName to it.cacheIdentity() }.toSet()
        val remaining = mutableListOf<CacheEntry>()
        for (entry in entries()) {
            val identity = entry.cacheIdentity()
            if (keep.contains(entry.repositoryFullName to identity)) {
                remaining.add(entry)
            } else {
                File(directory, entry.fileName).delete()
                File(directory, "${entry.fileName}.part").delete()
            }
        }
        writeEntries(remaining)
    }

    private fun entries(): List<CacheEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    add(CacheEntry.fromJson(array.getJSONObject(index)))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeEntries(entries: List<CacheEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    data class CacheEntry(
        val repositoryFullName: String,
        val tag: String,
        val versionName: String,
        val versionCode: Long?,
        val packageName: String?,
        val sha256: String?,
        val apkName: String,
        val fileName: String,
        val sizeBytes: Long,
        val cachedAtEpochMs: Long,
    ) {
        fun matches(release: ReleaseInfo): Boolean {
            if (repositoryFullName != release.repository.fullName) return false
            return cacheIdentity() == release.cacheIdentity()
        }

        fun cacheIdentity(): String = when {
            !sha256.isNullOrBlank() -> "sha:$sha256"
            else -> "tag:$tag|apk:$apkName|code:${versionCode ?: -1}"
        }

        fun toJson(): JSONObject = JSONObject()
            .put("repositoryFullName", repositoryFullName)
            .put("tag", tag)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .put("packageName", packageName)
            .put("sha256", sha256)
            .put("apkName", apkName)
            .put("fileName", fileName)
            .put("sizeBytes", sizeBytes)
            .put("cachedAtEpochMs", cachedAtEpochMs)

        companion object {
            fun fromJson(json: JSONObject): CacheEntry = CacheEntry(
                repositoryFullName = json.getString("repositoryFullName"),
                tag = json.getString("tag"),
                versionName = json.optString("versionName"),
                versionCode = if (json.has("versionCode") && !json.isNull("versionCode")) {
                    json.optLong("versionCode")
                } else {
                    null
                },
                packageName = json.optString("packageName").takeIf { it.isNotBlank() },
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() },
                apkName = json.optString("apkName"),
                fileName = json.getString("fileName"),
                sizeBytes = json.optLong("sizeBytes"),
                cachedAtEpochMs = json.optLong("cachedAtEpochMs"),
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "apk_cache"
        private const val KEY_ENTRIES = "entries"
    }
}

fun ReleaseInfo.cacheIdentity(): String = when {
    !sha256.isNullOrBlank() -> "sha:$sha256"
    else -> "tag:$tag|apk:${apk.name}|code:${versionCode ?: -1}"
}

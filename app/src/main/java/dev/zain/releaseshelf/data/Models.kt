package dev.zain.releaseshelf.data

data class RepositoryId(
    val owner: String,
    val name: String,
) {
    val fullName: String get() = "$owner/$name"

    companion object {
        fun parse(value: String): RepositoryId? {
            val parts = value.trim().trim('/').split('/')
            if (parts.size != 2 || parts.any { it.isBlank() }) return null
            val valid = Regex("[A-Za-z0-9_.-]+")
            if (parts.any { !valid.matches(it) }) return null
            return RepositoryId(parts[0], parts[1])
        }
    }
}

data class ReleaseAsset(
    val name: String,
    val apiUrl: String,
    val browserUrl: String,
    val sizeBytes: Long,
)

data class ReleaseInfo(
    val repository: RepositoryId,
    val displayName: String,
    val tag: String,
    val versionName: String,
    val versionCode: Long?,
    val packageName: String?,
    val minSdk: Int?,
    val publishedAt: String,
    val releaseUrl: String,
    val notes: String,
    val apk: ReleaseAsset,
    val sha256: String?,
)

data class InstalledVersion(
    val versionName: String,
    val versionCode: Long,
)

enum class UpdateStatus {
    UPDATE_AVAILABLE,
    CURRENT,
    NOT_INSTALLED,
    INSTALLED_NEWER,
    UNKNOWN,
}

data class TrackedRelease(
    val repository: RepositoryId,
    val release: ReleaseInfo? = null,
    val installed: InstalledVersion? = null,
    val status: UpdateStatus = UpdateStatus.UNKNOWN,
    val error: String? = null,
    val downloadProgress: Float? = null,
    /** Non-null while a PackageInstaller session is active for this release (0f–1f). */
    val installProgress: Float? = null,
    val isCached: Boolean = false,
)

fun updateStatus(remote: ReleaseInfo, installed: InstalledVersion?): UpdateStatus {
    if (installed == null) return UpdateStatus.NOT_INSTALLED
    remote.versionCode?.let { remoteCode ->
        return when {
            remoteCode > installed.versionCode -> UpdateStatus.UPDATE_AVAILABLE
            remoteCode < installed.versionCode -> UpdateStatus.INSTALLED_NEWER
            else -> UpdateStatus.CURRENT
        }
    }
    val comparison = compareVersionNames(remote.versionName, installed.versionName)
    return when {
        comparison > 0 -> UpdateStatus.UPDATE_AVAILABLE
        comparison < 0 -> UpdateStatus.INSTALLED_NEWER
        else -> UpdateStatus.CURRENT
    }
}

fun compareVersionNames(left: String, right: String): Int {
    fun parts(value: String) = value
        .removePrefix("v")
        .substringBefore('-')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }

    val leftParts = parts(left)
    val rightParts = parts(right)
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}

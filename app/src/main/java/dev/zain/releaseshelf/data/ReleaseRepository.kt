package dev.zain.releaseshelf.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File

class ReleaseRepository(private val context: Context) {
    private val sourceStore = RepositoryStore(context)
    private val tokenStore = TokenStore(context)
    private val apkCache = ApkCache(context)
    private val github = GitHubClient()

    fun sources(): List<RepositoryId> = sourceStore.get()
    fun hasToken(): Boolean = tokenStore.hasToken()
    fun token(): String = tokenStore.get()
    fun saveToken(token: String) = tokenStore.set(token)
    fun addSource(repository: RepositoryId): List<RepositoryId> = sourceStore.add(repository)
    fun removeSource(repository: RepositoryId): List<RepositoryId> = sourceStore.remove(repository)

    fun load(repository: RepositoryId): TrackedRelease = runCatching {
        val release = github.latestRelease(repository, tokenStore.get())
        val installed = release.packageName?.let(::installedVersion)
        val status = updateStatus(release, installed)
        // Same (or newer) install means the cached APK is useless — drop it so we never
        // offer Install / Re-download / Remove for an already-current app.
        if (status == UpdateStatus.CURRENT || status == UpdateStatus.INSTALLED_NEWER) {
            apkCache.remove(release)
        }
        TrackedRelease(
            repository = repository,
            release = release,
            installed = installed,
            status = status,
            isCached = apkCache.isCached(release),
        )
    }.getOrElse { error ->
        val message = when (error) {
            is GitHubException -> when (error.statusCode) {
                401 -> "GitHub token was rejected"
                403 -> "GitHub access or API limit blocked this request"
                404 -> "Repository or published release was not found"
                else -> error.message
            }
            else -> error.message ?: "Could not check this source"
        }
        TrackedRelease(repository = repository, error = message)
    }

    fun cachedApk(release: ReleaseInfo): File? = apkCache.find(release)

    fun isCached(release: ReleaseInfo): Boolean = apkCache.isCached(release)

    fun removeCached(release: ReleaseInfo) {
        apkCache.remove(release)
    }

    fun removeCachedForSource(repository: RepositoryId) {
        apkCache.remove(repository.fullName)
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(packageName: String): InstalledVersion? {
        val info: PackageInfo = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                context.packageManager.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return InstalledVersion(
            versionName = info.versionName.orEmpty().ifBlank { "Unknown" },
            versionCode = info.longVersionCode,
        )
    }
}

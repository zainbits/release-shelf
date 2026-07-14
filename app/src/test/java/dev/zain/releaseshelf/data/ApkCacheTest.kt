package dev.zain.releaseshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkCacheTest {
    @Test
    fun cacheIdentityPrefersSha256() {
        val release = sampleRelease(sha256 = "a".repeat(64), tag = "v1.0.0")
        assertEquals("sha:${"a".repeat(64)}", release.cacheIdentity())
    }

    @Test
    fun cacheIdentityFallsBackToTagApkAndCode() {
        val release = sampleRelease(sha256 = null, tag = "v1.2.3", versionCode = 12)
        assertEquals("tag:v1.2.3|apk:App-v1.2.3-release.apk|code:12", release.cacheIdentity())
    }

    @Test
    fun cacheEntryMatchesSameReleaseIdentity() {
        val release = sampleRelease(sha256 = "b".repeat(64))
        val entry = ApkCache.CacheEntry(
            repositoryFullName = release.repository.fullName,
            tag = release.tag,
            versionName = release.versionName,
            versionCode = release.versionCode,
            packageName = release.packageName,
            sha256 = release.sha256,
            apkName = release.apk.name,
            fileName = "file.apk",
            sizeBytes = 10,
            cachedAtEpochMs = 0,
        )
        assertTrue(entry.matches(release))
        assertFalse(entry.matches(release.copy(sha256 = "c".repeat(64))))
        assertFalse(
            entry.matches(
                release.copy(repository = RepositoryId("other", "repo")),
            ),
        )
    }

    private fun sampleRelease(
        sha256: String?,
        tag: String = "v1.0.0",
        versionCode: Long? = 1,
    ): ReleaseInfo = ReleaseInfo(
        repository = RepositoryId("zain", "App"),
        displayName = "App",
        tag = tag,
        versionName = tag.removePrefix("v"),
        versionCode = versionCode,
        packageName = "dev.zain.app",
        minSdk = 29,
        publishedAt = "2026-07-14T00:00:00Z",
        releaseUrl = "https://example.com",
        notes = "",
        apk = ReleaseAsset(
            name = "App-$tag-release.apk",
            apiUrl = "https://api.example.com/apk",
            browserUrl = "https://example.com/apk",
            sizeBytes = 100,
        ),
        sha256 = sha256,
    )
}

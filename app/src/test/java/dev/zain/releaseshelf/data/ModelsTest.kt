package dev.zain.releaseshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {
    @Test
    fun parsesRepositorySlug() {
        assertEquals(RepositoryId("owner", "repo"), RepositoryId.parse("owner/repo"))
        assertNull(RepositoryId.parse("owner/repo/extra"))
        assertNull(RepositoryId.parse("owner repo"))
    }

    @Test
    fun comparesSemanticVersionsNumerically() {
        assertEquals(1, compareVersionNames("1.10.0", "1.9.9"))
        assertEquals(0, compareVersionNames("v2.0", "2.0.0"))
        assertEquals(-1, compareVersionNames("0.9.4", "1.0.0"))
    }

    @Test
    fun versionCodeDrivesUpdateStatus() {
        val release = ReleaseInfo(
            repository = RepositoryId("owner", "repo"),
            displayName = "Repo",
            tag = "v1.2.0",
            versionName = "1.2.0",
            versionCode = 12,
            packageName = "dev.example.repo",
            minSdk = 29,
            publishedAt = "",
            releaseUrl = "",
            notes = "",
            apk = ReleaseAsset("repo.apk", "", "", 0),
            sha256 = null,
        )

        assertEquals(UpdateStatus.UPDATE_AVAILABLE, updateStatus(release, InstalledVersion("1.1.0", 11)))
        assertEquals(UpdateStatus.CURRENT, updateStatus(release, InstalledVersion("1.2.0", 12)))
        assertEquals(UpdateStatus.INSTALLED_NEWER, updateStatus(release, InstalledVersion("1.3.0", 13)))
        assertEquals(UpdateStatus.NOT_INSTALLED, updateStatus(release, null))
    }
}

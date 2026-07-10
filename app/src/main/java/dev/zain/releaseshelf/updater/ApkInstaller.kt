package dev.zain.releaseshelf.updater

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.zain.releaseshelf.data.ReleaseInfo
import java.io.File
import java.security.MessageDigest

class ApkInstaller(private val activity: Activity) {
    private var pending: PendingInstall? = null

    fun begin(file: File, release: ReleaseInfo): Result<Unit> = runCatching {
        validate(file, release)
        val request = PendingInstall(file, release)
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pending = request
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${activity.packageName}".toUri(),
                ),
            )
            return@runCatching
        }
        launchInstaller(request)
    }

    fun resumePending(): Result<Boolean> = runCatching {
        val request = pending ?: return@runCatching false
        if (!activity.packageManager.canRequestPackageInstalls()) return@runCatching false
        pending = null
        launchInstaller(request)
        true
    }

    @Suppress("DEPRECATION")
    private fun validate(file: File, release: ReleaseInfo) {
        check(file.isFile && file.length() > 0) { "Downloaded APK is missing" }
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            activity.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            activity.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: error("Android could not read the downloaded APK")

        release.packageName?.let { expected ->
            check(archive.packageName == expected) { "APK package does not match the release metadata" }
        }
        release.versionCode?.let { expected ->
            check(archive.longVersionCode == expected) { "APK version does not match the release metadata" }
        }

        val installed = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.packageManager.getPackageInfo(
                    archive.packageName,
                    PackageManager.PackageInfoFlags.of(flags.toLong()),
                )
            } else {
                activity.packageManager.getPackageInfo(archive.packageName, flags)
            }
        }.getOrNull()
        if (installed != null) {
            check(signers(archive) == signers(installed)) {
                "APK signature differs from the installed app"
            }
        }
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signatures = info.signingInfo?.run {
            if (hasMultipleSigners()) apkContentsSigners else signingCertificateHistory
        }.orEmpty()
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun launchInstaller(request: PendingInstall) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            request.file,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private data class PendingInstall(val file: File, val release: ReleaseInfo)

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }

}

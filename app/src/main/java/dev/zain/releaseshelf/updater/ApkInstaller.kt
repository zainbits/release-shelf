package dev.zain.releaseshelf.updater

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import dev.zain.releaseshelf.data.ReleaseInfo
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Installs verified APKs through [PackageInstaller] sessions.
 *
 * On Android 12+, sessions request [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED].
 * After ReleaseShelf becomes the installer of record for a package, eligible updates may
 * complete without the ordinary confirmation screen. Initial installs, Play Protect
 * interventions, and any other system gate still surface via
 * [PackageInstaller.STATUS_PENDING_USER_ACTION].
 *
 * Install progress is reported through [InstallStatusBus] and [InstallNotifier] so the
 * user can see work is underway even when the system UI is silent.
 */
class ApkInstaller(private val activity: Activity) {
    private var pending: PendingInstall? = null

    fun begin(file: File, release: ReleaseInfo): Result<Unit> = runCatching {
        validate(file, release)
        val request = PendingInstall(file, release)
        InstallStatusBus.tryEmit(
            InstallStatusBus.Event.Started(
                repositoryFullName = release.repository.fullName,
                displayName = release.displayName,
            ),
        )
        InstallNotifier.showProgress(
            activity,
            release.repository.fullName,
            release.displayName,
            progress = 0f,
        )
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
        commitSession(request)
    }

    fun resumePending(): Result<Boolean> = runCatching {
        val request = pending ?: return@runCatching false
        if (!activity.packageManager.canRequestPackageInstalls()) return@runCatching false
        pending = null
        InstallStatusBus.tryEmit(
            InstallStatusBus.Event.Started(
                repositoryFullName = request.release.repository.fullName,
                displayName = request.release.displayName,
            ),
        )
        InstallNotifier.showProgress(
            activity,
            request.release.repository.fullName,
            request.release.displayName,
            progress = 0f,
        )
        commitSession(request)
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

    private fun commitSession(request: PendingInstall) {
        val packageInstaller = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(request.file.length())
            request.release.packageName?.let(::setAppPackageName)
            setAppLabel(request.release.displayName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val repo = request.release.repository.fullName
        ActiveInstallSessions.put(
            ActiveInstallSessions.Entry(
                sessionId = sessionId,
                repositoryFullName = repo,
                displayName = request.release.displayName,
                packageName = request.release.packageName,
            ),
        )
        val session = packageInstaller.openSession(sessionId)
        try {
            writeApk(session, request.file, repo, request.release.displayName)
            InstallStatusBus.tryEmit(InstallStatusBus.Event.Progress(repo, 0.9f))
            InstallNotifier.showProgress(activity, repo, request.release.displayName, 0.9f)
            val statusIntent = Intent(activity, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_STATUS
                setPackage(activity.packageName)
                putExtra(InstallResultReceiver.EXTRA_DISPLAY_NAME, request.release.displayName)
                putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME, request.release.packageName)
                putExtra(InstallResultReceiver.EXTRA_REPOSITORY, repo)
                putExtra(InstallResultReceiver.EXTRA_SESSION_ID, sessionId)
            }
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                activity,
                sessionId,
                statusIntent,
                piFlags,
            )
            session.commit(pendingIntent.intentSender)
        } catch (error: Exception) {
            try {
                session.abandon()
            } catch (_: Exception) {
                // Session may already be closed or invalid.
            }
            ActiveInstallSessions.remove(sessionId)
            InstallNotifier.showFailed(
                activity,
                repo,
                request.release.displayName,
                error.message,
            )
            InstallStatusBus.tryEmit(
                InstallStatusBus.Event.Finished(
                    repositoryFullName = repo,
                    packageName = request.release.packageName,
                    displayName = request.release.displayName,
                    success = false,
                    message = error.message ?: "Could not start the package install session",
                ),
            )
            throw IOException("Could not start the package install session", error)
        } finally {
            try {
                session.close()
            } catch (_: Exception) {
                // Ignore close errors after abandon/commit.
            }
        }
    }

    private fun writeApk(
        session: PackageInstaller.Session,
        file: File,
        repositoryFullName: String,
        displayName: String,
    ) {
        val total = file.length().coerceAtLeast(1L)
        var written = 0L
        var lastReportedPercent = -1
        session.openWrite(SESSION_APK_NAME, 0, file.length()).use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    // Stage write is the first ~80% of our reported progress; system finishes rest.
                    val progress = (written.toFloat() / total.toFloat() * 0.8f).coerceIn(0f, 0.8f)
                    val percent = (progress * 100).toInt()
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        InstallStatusBus.tryEmit(
                            InstallStatusBus.Event.Progress(repositoryFullName, progress),
                        )
                        InstallNotifier.showProgress(
                            activity,
                            repositoryFullName,
                            displayName,
                            progress,
                        )
                    }
                }
            }
            session.fsync(out)
        }
    }

    private data class PendingInstall(val file: File, val release: ReleaseInfo)

    private companion object {
        const val SESSION_APK_NAME = "base.apk"
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

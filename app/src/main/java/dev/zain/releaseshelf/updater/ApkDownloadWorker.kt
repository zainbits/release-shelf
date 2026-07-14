package dev.zain.releaseshelf.updater

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zain.releaseshelf.MainActivity
import dev.zain.releaseshelf.R
import dev.zain.releaseshelf.data.ApkCache
import dev.zain.releaseshelf.data.GitHubClient
import dev.zain.releaseshelf.data.ReleaseAsset
import dev.zain.releaseshelf.data.ReleaseInfo
import dev.zain.releaseshelf.data.RepositoryId
import dev.zain.releaseshelf.data.TokenStore
import java.io.File
import kotlin.math.roundToInt

class ApkDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val cache = ApkCache(appContext)
    private val github = GitHubClient()
    private val tokenStore = TokenStore(appContext)

    override suspend fun doWork(): Result {
        val release = inputRelease() ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing download payload"),
        )
        val displayName = release.displayName.ifBlank { release.repository.name }
        val notificationId = notificationId(release.repository.fullName)
        val installWhenReady = inputData.getBoolean(KEY_INSTALL_WHEN_READY, false)

        setForeground(buildForegroundInfo(displayName, notificationId, 0, indeterminate = true))

        return try {
            cache.find(release)?.let { existing ->
                publishProgress(1f, release.repository.fullName, complete = true)
                notifyComplete(displayName, notificationId)
                return Result.success(successData(release, existing, installWhenReady))
            }

            val destination = cache.destination(release)
            var lastNotifiedPercent = -1
            val actualSha = github.download(
                asset = release.apk,
                token = tokenStore.get(),
                destination = destination,
                onProgress = { progress ->
                    publishProgress(progress, release.repository.fullName)
                    val percent = (progress * 100).roundToInt().coerceIn(0, 100)
                    if (percent != lastNotifiedPercent) {
                        lastNotifiedPercent = percent
                        setForegroundAsync(
                            buildForegroundInfo(
                                displayName,
                                notificationId,
                                percent,
                                indeterminate = progress <= 0f,
                            ),
                        )
                    }
                },
            )
            release.sha256?.let { expected ->
                check(actualSha.equals(expected, ignoreCase = true)) {
                    destination.delete()
                    "Downloaded APK checksum did not match the release metadata"
                }
            }
            cache.markCached(release, destination)
            publishProgress(1f, release.repository.fullName, complete = true)
            notifyComplete(displayName, notificationId)
            Result.success(successData(release, destination, installWhenReady))
        } catch (error: Exception) {
            notifyFailed(displayName, notificationId, error.message)
            Result.failure(
                Data.Builder()
                    .putString(KEY_REPOSITORY, release.repository.fullName)
                    .putString(KEY_ERROR, error.message ?: "Download failed")
                    .build(),
            )
        }
    }

    private fun publishProgress(progress: Float, repository: String, complete: Boolean = false) {
        setProgressAsync(
            Data.Builder()
                .putString(KEY_REPOSITORY, repository)
                .putFloat(KEY_PROGRESS, progress)
                .putBoolean(KEY_COMPLETE, complete)
                .build(),
        )
    }

    private fun successData(release: ReleaseInfo, file: File, installWhenReady: Boolean): Data =
        Data.Builder()
            .putString(KEY_REPOSITORY, release.repository.fullName)
            .putString(KEY_FILE, file.absolutePath)
            .putBoolean(KEY_INSTALL_WHEN_READY, installWhenReady)
            .build()

    private fun inputRelease(): ReleaseInfo? {
        val owner = inputData.getString(KEY_OWNER) ?: return null
        val name = inputData.getString(KEY_NAME) ?: return null
        val apkName = inputData.getString(KEY_APK_NAME) ?: return null
        val apkApiUrl = inputData.getString(KEY_APK_API_URL) ?: return null
        val apkBrowserUrl = inputData.getString(KEY_APK_BROWSER_URL) ?: return null
        val versionCode = inputData.getLong(KEY_VERSION_CODE, Long.MIN_VALUE)
        val minSdk = inputData.getInt(KEY_MIN_SDK, Int.MIN_VALUE)
        return ReleaseInfo(
            repository = RepositoryId(owner, name),
            displayName = inputData.getString(KEY_DISPLAY_NAME).orEmpty().ifBlank { name },
            tag = inputData.getString(KEY_TAG).orEmpty(),
            versionName = inputData.getString(KEY_VERSION_NAME).orEmpty(),
            versionCode = versionCode.takeIf { it != Long.MIN_VALUE },
            packageName = inputData.getString(KEY_PACKAGE_NAME),
            minSdk = minSdk.takeIf { it != Int.MIN_VALUE },
            publishedAt = inputData.getString(KEY_PUBLISHED_AT).orEmpty(),
            releaseUrl = inputData.getString(KEY_RELEASE_URL).orEmpty(),
            notes = "",
            apk = ReleaseAsset(
                name = apkName,
                apiUrl = apkApiUrl,
                browserUrl = apkBrowserUrl,
                sizeBytes = inputData.getLong(KEY_APK_SIZE, 0L),
            ),
            sha256 = inputData.getString(KEY_SHA256),
        )
    }

    private fun buildForegroundInfo(
        displayName: String,
        notificationId: Int,
        progressPercent: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = baseNotification(displayName)
            .setContentText(if (indeterminate) "Starting…" else "$progressPercent%")
            .setProgress(100, progressPercent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun notifyComplete(displayName: String, notificationId: Int) {
        val notification = baseNotification(displayName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(applicationContext.getString(R.string.download_notification_complete, displayName))
            .setContentText("Tap to open ReleaseShelf and install")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        postNotification(notificationId, notification)
    }

    private fun notifyFailed(displayName: String, notificationId: Int, message: String?) {
        val notification = baseNotification(displayName)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(applicationContext.getString(R.string.download_notification_failed, displayName))
            .setContentText(message ?: "Download failed")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        postNotification(notificationId, notification)
    }

    private fun postNotification(notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val manager = NotificationManagerCompat.from(applicationContext)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notificationId, notification)
    }

    private fun baseNotification(displayName: String): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title, displayName))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    companion object {
        const val CHANNEL_ID = "apk_downloads"
        const val WORK_TAG = "apk_download"
        const val UNIQUE_PREFIX = "download:"

        const val KEY_OWNER = "owner"
        const val KEY_NAME = "name"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_TAG = "tag"
        const val KEY_VERSION_NAME = "versionName"
        const val KEY_VERSION_CODE = "versionCode"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_MIN_SDK = "minSdk"
        const val KEY_PUBLISHED_AT = "publishedAt"
        const val KEY_RELEASE_URL = "releaseUrl"
        const val KEY_APK_NAME = "apkName"
        const val KEY_APK_API_URL = "apkApiUrl"
        const val KEY_APK_BROWSER_URL = "apkBrowserUrl"
        const val KEY_APK_SIZE = "apkSize"
        const val KEY_SHA256 = "sha256"
        const val KEY_INSTALL_WHEN_READY = "installWhenReady"
        const val KEY_REPOSITORY = "repository"
        const val KEY_PROGRESS = "progress"
        const val KEY_COMPLETE = "complete"
        const val KEY_FILE = "file"
        const val KEY_ERROR = "error"

        fun uniqueWorkName(repositoryFullName: String): String = "$UNIQUE_PREFIX$repositoryFullName"

        fun notificationId(repositoryFullName: String): Int =
            10_000 + (repositoryFullName.hashCode() and 0xFFFF)

        fun enqueue(
            context: Context,
            release: ReleaseInfo,
            installWhenReady: Boolean,
            replaceExisting: Boolean = false,
        ) {
            val builder = Data.Builder()
                .putString(KEY_OWNER, release.repository.owner)
                .putString(KEY_NAME, release.repository.name)
                .putString(KEY_DISPLAY_NAME, release.displayName)
                .putString(KEY_TAG, release.tag)
                .putString(KEY_VERSION_NAME, release.versionName)
                .putString(KEY_PUBLISHED_AT, release.publishedAt)
                .putString(KEY_RELEASE_URL, release.releaseUrl)
                .putString(KEY_APK_NAME, release.apk.name)
                .putString(KEY_APK_API_URL, release.apk.apiUrl)
                .putString(KEY_APK_BROWSER_URL, release.apk.browserUrl)
                .putLong(KEY_APK_SIZE, release.apk.sizeBytes)
                .putBoolean(KEY_INSTALL_WHEN_READY, installWhenReady)
            release.versionCode?.let { builder.putLong(KEY_VERSION_CODE, it) }
            release.packageName?.let { builder.putString(KEY_PACKAGE_NAME, it) }
            release.minSdk?.let { builder.putInt(KEY_MIN_SDK, it) }
            release.sha256?.let { builder.putString(KEY_SHA256, it) }

            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setInputData(builder.build())
                .addTag(WORK_TAG)
                .addTag(uniqueWorkName(release.repository.fullName))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(release.repository.fullName),
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, repositoryFullName: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(repositoryFullName))
        }

        fun cachedFileFromOutput(output: Data): File? =
            output.getString(KEY_FILE)?.let { File(it) }?.takeIf { it.isFile }
    }
}

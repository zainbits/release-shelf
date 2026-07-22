package dev.zain.releaseshelf.updater

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.zain.releaseshelf.MainActivity
import dev.zain.releaseshelf.R

/**
 * Ongoing and terminal notifications for PackageInstaller sessions.
 * Mirrors [ApkDownloadWorker]'s download notifications so users see install work
 * even when the system confirmation UI is suppressed.
 */
object InstallNotifier {
    const val CHANNEL_ID = "apk_installs"

    fun notificationId(repositoryFullName: String): Int =
        20_000 + (repositoryFullName.hashCode() and 0xFFFF)

    fun showProgress(
        context: Context,
        repositoryFullName: String,
        displayName: String,
        progress: Float,
    ) {
        val percent = (progress * 100).toInt().coerceIn(0, 100)
        val indeterminate = progress <= 0f
        val notification = baseBuilder(context, displayName)
            .setContentText(
                if (indeterminate) {
                    context.getString(R.string.install_notification_progress)
                } else {
                    "$percent%"
                },
            )
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()
        post(context, notificationId(repositoryFullName), notification)
    }

    fun showComplete(context: Context, repositoryFullName: String, displayName: String) {
        val notification = baseBuilder(context, displayName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.install_notification_complete, displayName))
            .setContentText(null)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        post(context, notificationId(repositoryFullName), notification)
    }

    fun showFailed(
        context: Context,
        repositoryFullName: String,
        displayName: String,
        detail: String?,
    ) {
        val notification = baseBuilder(context, displayName)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.install_notification_failed, displayName))
            .setContentText(detail)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        post(context, notificationId(repositoryFullName), notification)
    }

    fun cancel(context: Context, repositoryFullName: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(repositoryFullName))
    }

    private fun baseBuilder(context: Context, displayName: String): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.install_notification_title, displayName))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun post(context: Context, notificationId: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(notificationId, notification)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.install_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.install_channel_description)
            },
        )
    }
}

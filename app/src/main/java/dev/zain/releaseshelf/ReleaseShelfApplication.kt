package dev.zain.releaseshelf

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.zain.releaseshelf.updater.ApkDownloadWorker

class ReleaseShelfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                ApkDownloadWorker.CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_description)
            },
        )
    }
}

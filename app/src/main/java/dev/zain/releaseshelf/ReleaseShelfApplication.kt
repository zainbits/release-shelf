package dev.zain.releaseshelf

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import dev.zain.releaseshelf.data.AndroidSshSupport
import dev.zain.releaseshelf.updater.ActiveInstallSessions
import dev.zain.releaseshelf.updater.ApkDownloadWorker
import dev.zain.releaseshelf.updater.InstallNotifier
import dev.zain.releaseshelf.updater.InstallStatusBus

class ReleaseShelfApplication : Application() {
    private val sessionCallback = object : PackageInstaller.SessionCallback() {
        override fun onCreated(sessionId: Int) = Unit

        override fun onBadgingChanged(sessionId: Int) = Unit

        override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            val entry = ActiveInstallSessions.get(sessionId) ?: return
            // Blend system install progress into the remaining 20% after APK staging.
            val blended = (0.8f + progress.coerceIn(0f, 1f) * 0.2f).coerceIn(0f, 1f)
            InstallStatusBus.tryEmit(
                InstallStatusBus.Event.Progress(entry.repositoryFullName, blended),
            )
            InstallNotifier.showProgress(
                this@ReleaseShelfApplication,
                entry.repositoryFullName,
                entry.displayName,
                blended,
            )
        }

        override fun onFinished(sessionId: Int, success: Boolean) {
            // Terminal status is handled by InstallResultReceiver with full extras.
            // Keep the map entry until that broadcast arrives so UI can resolve the repo.
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Replace Android's incomplete BC provider so sshj can negotiate KEX.
        AndroidSshSupport.ensureCryptoProviders()
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
        manager.createNotificationChannel(
            NotificationChannel(
                InstallNotifier.CHANNEL_ID,
                getString(R.string.install_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.install_channel_description)
            },
        )
        packageManager.packageInstaller.registerSessionCallback(
            sessionCallback,
            Handler(Looper.getMainLooper()),
        )
    }
}

package dev.zain.releaseshelf.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Handles [PackageInstaller] session commit results.
 *
 * Launches the system confirmation UI when user action is still required
 * (first install, Play Protect, or other gates). Surfaces success and failure
 * outcomes with notifications, toasts, and [InstallStatusBus] events for the UI.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: "App"
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val repository = intent.getStringExtra(EXTRA_REPOSITORY)
            ?: intent.getIntExtra(EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
                ?.let { ActiveInstallSessions.get(it)?.repositoryFullName }
            ?: packageName?.let { ActiveInstallSessions.getByPackageName(it)?.repositoryFullName }
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
            .takeIf { it >= 0 }
            ?: intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Keep the in-progress indicator while the user confirms.
                if (repository != null) {
                    InstallStatusBus.tryEmit(InstallStatusBus.Event.Progress(repository, 0.95f))
                    InstallNotifier.showProgress(context, repository, displayName, 0.95f)
                }
                val confirmation = confirmationIntent(intent) ?: return
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                finishSession(sessionId, repository)
                if (repository != null) {
                    InstallNotifier.showComplete(context, repository, displayName)
                }
                InstallStatusBus.tryEmit(
                    InstallStatusBus.Event.Finished(
                        repositoryFullName = repository,
                        packageName = packageName,
                        displayName = displayName,
                        success = true,
                        message = null,
                    ),
                )
                Toast.makeText(
                    context,
                    context.getString(
                        dev.zain.releaseshelf.R.string.install_notification_complete,
                        displayName,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() }
                finishSession(sessionId, repository)
                if (repository != null) {
                    InstallNotifier.showFailed(context, repository, displayName, detail)
                }
                val message = if (detail != null) {
                    "Could not install $displayName: $detail"
                } else {
                    "Could not install $displayName"
                }
                InstallStatusBus.tryEmit(
                    InstallStatusBus.Event.Finished(
                        repositoryFullName = repository,
                        packageName = packageName,
                        displayName = displayName,
                        success = false,
                        message = message,
                    ),
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun finishSession(sessionId: Int?, repository: String?) {
        if (sessionId != null) {
            ActiveInstallSessions.remove(sessionId)
        } else if (repository != null) {
            ActiveInstallSessions.removeByRepository(repository)
        }
    }

    private fun confirmationIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "dev.zain.releaseshelf.INSTALL_STATUS"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_REPOSITORY = "repository"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

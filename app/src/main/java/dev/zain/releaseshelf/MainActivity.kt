package dev.zain.releaseshelf

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.zain.releaseshelf.ui.ReleaseShelfApp
import dev.zain.releaseshelf.ui.theme.ReleaseShelfTheme
import dev.zain.releaseshelf.updater.ApkInstaller
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: ReleaseShelfViewModel
    private lateinit var installer: ApkInstaller

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional; downloads/installs still run without the progress notification */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[ReleaseShelfViewModel::class.java]
        installer = ApkInstaller(this)
        maybeRequestNotificationPermission()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.installRequests.collect { request ->
                    installer.begin(request.file, request.release).onFailure { error ->
                        viewModel.onInstallStartFailed(request.release, error.message)
                        showError(error)
                    }
                }
            }
        }

        setContent {
            ReleaseShelfTheme {
                ReleaseShelfApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::installer.isInitialized) {
            installer.resumePending().onFailure(::showError)
            // Do not auto-refresh on every resume while a download is in flight;
            // still refresh so installed versions update after the system installer.
            viewModel.refresh()
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showError(error: Throwable) {
        Toast.makeText(this, error.message ?: "Could not start the install", Toast.LENGTH_LONG).show()
    }
}

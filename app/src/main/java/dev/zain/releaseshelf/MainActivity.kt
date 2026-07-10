package dev.zain.releaseshelf

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this)[ReleaseShelfViewModel::class.java]
        installer = ApkInstaller(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.installRequests.collect { request ->
                    installer.begin(request.file, request.release).onFailure(::showError)
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
            viewModel.refresh()
        }
    }

    private fun showError(error: Throwable) {
        Toast.makeText(this, error.message ?: "Could not open the installer", Toast.LENGTH_LONG).show()
    }
}

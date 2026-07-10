package dev.zain.releaseshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zain.releaseshelf.data.ReleaseInfo
import dev.zain.releaseshelf.data.ReleaseRepository
import dev.zain.releaseshelf.data.RepositoryId
import dev.zain.releaseshelf.data.TrackedRelease
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReleaseShelfState(
    val sources: List<RepositoryId> = emptyList(),
    val releases: List<TrackedRelease> = emptyList(),
    val refreshing: Boolean = false,
    val tokenConfigured: Boolean = false,
    val lastChecked: Instant? = null,
    val message: String? = null,
)

data class InstallRequest(val file: File, val release: ReleaseInfo)

class ReleaseShelfViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ReleaseRepository(application)
    private val mutableState = MutableStateFlow(
        ReleaseShelfState(
            sources = repository.sources(),
            tokenConfigured = repository.hasToken(),
        ),
    )
    val state = mutableState.asStateFlow()

    private val mutableInstallRequests = MutableSharedFlow<InstallRequest>(extraBufferCapacity = 1)
    val installRequests = mutableInstallRequests.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (mutableState.value.refreshing) return
        viewModelScope.launch {
            val sources = repository.sources()
            mutableState.value = mutableState.value.copy(
                sources = sources,
                refreshing = true,
                message = null,
            )
            val releases = withContext(Dispatchers.IO) {
                coroutineScope {
                    sources.map { source -> async { repository.load(source) } }.awaitAll()
                }
            }
            mutableState.value = mutableState.value.copy(
                releases = releases,
                refreshing = false,
                tokenConfigured = repository.hasToken(),
                lastChecked = Instant.now(),
            )
        }
    }

    fun saveToken(token: String) {
        repository.saveToken(token)
        mutableState.value = mutableState.value.copy(
            tokenConfigured = repository.hasToken(),
            message = if (token.isBlank()) "GitHub token removed" else "GitHub token saved securely",
        )
        refresh()
    }

    fun addSource(value: String): Boolean {
        val source = RepositoryId.parse(value) ?: run {
            mutableState.value = mutableState.value.copy(message = "Use the format owner/repository")
            return false
        }
        val sources = repository.addSource(source)
        mutableState.value = mutableState.value.copy(sources = sources, message = "Source added")
        refresh()
        return true
    }

    fun removeSource(source: RepositoryId) {
        val sources = repository.removeSource(source)
        mutableState.value = mutableState.value.copy(
            sources = sources,
            releases = mutableState.value.releases.filterNot { it.repository == source },
            message = "Source removed",
        )
    }

    fun download(release: ReleaseInfo) {
        val key = release.repository.fullName
        if (mutableState.value.releases.firstOrNull { it.repository.fullName == key }?.downloadProgress != null) return
        updateProgress(key, 0f)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    repository.download(release) { progress -> updateProgress(key, progress) }
                }
            }
            updateProgress(key, null)
            result.onSuccess { file ->
                mutableState.value = mutableState.value.copy(message = "Download verified")
                mutableInstallRequests.emit(InstallRequest(file, release))
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    message = error.message ?: "Could not download this update",
                )
            }
        }
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun updateProgress(repositoryName: String, progress: Float?) {
        mutableState.value = mutableState.value.copy(
            releases = mutableState.value.releases.map { item ->
                if (item.repository.fullName == repositoryName) item.copy(downloadProgress = progress) else item
            },
        )
    }
}

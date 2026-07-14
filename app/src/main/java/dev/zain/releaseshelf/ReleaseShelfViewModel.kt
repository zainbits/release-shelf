package dev.zain.releaseshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.zain.releaseshelf.data.ReleaseInfo
import dev.zain.releaseshelf.data.ReleaseRepository
import dev.zain.releaseshelf.data.RepositoryId
import dev.zain.releaseshelf.data.TrackedRelease
import dev.zain.releaseshelf.data.UpdateStatus
import dev.zain.releaseshelf.updater.ApkDownloadWorker
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
import kotlinx.coroutines.flow.collectLatest
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
    private val workManager = WorkManager.getInstance(application)
    private val handledSuccessIds = mutableSetOf<String>()

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
        observeDownloads()
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
                releases = mergeDownloadState(releases),
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
        ApkDownloadWorker.cancel(getApplication(), source.fullName)
        repository.removeCachedForSource(source)
        val sources = repository.removeSource(source)
        mutableState.value = mutableState.value.copy(
            sources = sources,
            releases = mutableState.value.releases.filterNot { it.repository == source },
            message = "Source removed",
        )
    }

    /** Queue a background download and open the installer when it finishes. */
    fun downloadAndInstall(release: ReleaseInfo) {
        startDownload(release, installWhenReady = true, force = false)
    }

    /** Queue a background download and keep the APK cached for later install. */
    fun downloadOnly(release: ReleaseInfo, force: Boolean = false) {
        startDownload(release, installWhenReady = false, force = force)
    }

    /** Install a previously downloaded and verified APK without re-downloading. */
    fun installCached(release: ReleaseInfo) {
        val file = repository.cachedApk(release)
        if (file == null) {
            mutableState.value = mutableState.value.copy(
                message = "No cached APK for this release. Download it first.",
            )
            return
        }
        viewModelScope.launch {
            mutableInstallRequests.emit(InstallRequest(file, release))
        }
    }

    fun cancelDownload(repositoryFullName: String) {
        ApkDownloadWorker.cancel(getApplication(), repositoryFullName)
        updateRelease(repositoryFullName) { it.copy(downloadProgress = null) }
        mutableState.value = mutableState.value.copy(message = "Download cancelled")
    }

    fun removeCached(release: ReleaseInfo) {
        repository.removeCached(release)
        updateRelease(release.repository.fullName) { it.copy(isCached = false) }
        mutableState.value = mutableState.value.copy(message = "Cached APK removed")
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun startDownload(
        release: ReleaseInfo,
        installWhenReady: Boolean,
        force: Boolean,
    ) {
        val key = release.repository.fullName
        val existing = mutableState.value.releases.firstOrNull { it.repository.fullName == key }
        if (existing?.downloadProgress != null && !force) return

        if (force) {
            ApkDownloadWorker.cancel(getApplication(), key)
            repository.removeCached(release)
            updateRelease(key) { it.copy(isCached = false) }
        } else {
            repository.cachedApk(release)?.let { file ->
                updateRelease(key) { it.copy(isCached = true, downloadProgress = null) }
                if (installWhenReady) {
                    viewModelScope.launch {
                        mutableInstallRequests.emit(InstallRequest(file, release))
                    }
                    mutableState.value = mutableState.value.copy(message = "Using cached APK")
                } else {
                    mutableState.value = mutableState.value.copy(message = "APK already cached")
                }
                return
            }
        }

        updateRelease(key) { it.copy(downloadProgress = 0f, isCached = false) }
        ApkDownloadWorker.enqueue(
            context = getApplication(),
            release = release,
            installWhenReady = installWhenReady,
            replaceExisting = force,
        )
        mutableState.value = mutableState.value.copy(
            message = if (installWhenReady) {
                "Downloading in the background…"
            } else {
                "Downloading for later install…"
            },
        )
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(ApkDownloadWorker.WORK_TAG).collectLatest { infos ->
                applyWorkInfos(infos)
            }
        }
    }

    private fun applyWorkInfos(infos: List<WorkInfo>) {
        val activeByRepo = mutableMapOf<String, WorkInfo>()
        for (info in infos) {
            val repo = info.progress.getString(ApkDownloadWorker.KEY_REPOSITORY)
                ?: info.outputData.getString(ApkDownloadWorker.KEY_REPOSITORY)
                ?: info.tags
                    .firstOrNull { it.startsWith(ApkDownloadWorker.UNIQUE_PREFIX) }
                    ?.removePrefix(ApkDownloadWorker.UNIQUE_PREFIX)
                ?: continue
            val existing = activeByRepo[repo]
            if (existing == null || info.state.isFinished.not() || existing.state.isFinished) {
                // Prefer non-finished work; otherwise keep the newest finished result.
                if (existing == null ||
                    (!info.state.isFinished && existing.state.isFinished) ||
                    info.id.toString() > existing.id.toString()
                ) {
                    activeByRepo[repo] = info
                }
            }
        }

        var releases = mutableState.value.releases
        var message: String? = null

        for ((repo, info) in activeByRepo) {
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    releases = releases.map { item ->
                        if (item.repository.fullName == repo && item.downloadProgress == null) {
                            item.copy(downloadProgress = 0f)
                        } else {
                            item
                        }
                    }
                }
                WorkInfo.State.RUNNING -> {
                    val progress = info.progress.getFloat(ApkDownloadWorker.KEY_PROGRESS, 0f)
                    releases = releases.map { item ->
                        if (item.repository.fullName == repo) {
                            item.copy(downloadProgress = progress.coerceIn(0f, 1f))
                        } else {
                            item
                        }
                    }
                }
                WorkInfo.State.SUCCEEDED -> {
                    val workId = info.id.toString()
                    val file = ApkDownloadWorker.cachedFileFromOutput(info.outputData)
                    val installWhenReady = info.outputData.getBoolean(
                        ApkDownloadWorker.KEY_INSTALL_WHEN_READY,
                        false,
                    )
                    releases = releases.map { item ->
                        if (item.repository.fullName == repo) {
                            item.copy(
                                downloadProgress = null,
                                isCached = file != null || item.isCached || item.release?.let {
                                    repository.isCached(it)
                                } == true,
                            )
                        } else {
                            item
                        }
                    }
                    if (workId !in handledSuccessIds) {
                        handledSuccessIds.add(workId)
                        message = "Download verified and cached"
                        if (installWhenReady && file != null) {
                            val release = releases.firstOrNull { it.repository.fullName == repo }?.release
                            if (release != null) {
                                viewModelScope.launch {
                                    mutableInstallRequests.emit(InstallRequest(file, release))
                                }
                            }
                        }
                    }
                }
                WorkInfo.State.FAILED -> {
                    val workId = info.id.toString()
                    val error = info.outputData.getString(ApkDownloadWorker.KEY_ERROR)
                        ?: "Could not download this update"
                    releases = releases.map { item ->
                        if (item.repository.fullName == repo) {
                            item.copy(downloadProgress = null)
                        } else {
                            item
                        }
                    }
                    if (workId !in handledSuccessIds) {
                        handledSuccessIds.add(workId)
                        message = error
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    releases = releases.map { item ->
                        if (item.repository.fullName == repo) {
                            item.copy(downloadProgress = null)
                        } else {
                            item
                        }
                    }
                }
            }
        }

        // Clear progress for repos that no longer have active work.
        val activeRepos = activeByRepo.keys
        releases = releases.map { item ->
            if (item.downloadProgress != null && item.repository.fullName !in activeRepos) {
                val stillRunning = infos.any { info ->
                    !info.state.isFinished && info.tags.contains(
                        ApkDownloadWorker.uniqueWorkName(item.repository.fullName),
                    )
                }
                if (!stillRunning) item.copy(downloadProgress = null) else item
            } else {
                item
            }
        }

        mutableState.value = mutableState.value.copy(
            releases = releases,
            message = message ?: mutableState.value.message,
        )
    }

    private fun mergeDownloadState(releases: List<TrackedRelease>): List<TrackedRelease> {
        val current = mutableState.value.releases.associateBy { it.repository.fullName }
        return releases.map { loaded ->
            val previous = current[loaded.repository.fullName]
            val alreadyInstalled = loaded.status == UpdateStatus.CURRENT ||
                loaded.status == UpdateStatus.INSTALLED_NEWER
            loaded.copy(
                downloadProgress = if (alreadyInstalled) null else previous?.downloadProgress,
                // Never keep a stale "cached" flag for an install that is already current.
                isCached = if (alreadyInstalled) false else {
                    loaded.isCached || (previous?.isCached == true && loaded.release != null &&
                        previous.release?.let { repository.isCached(it) } == true)
                },
            )
        }
    }

    private fun updateRelease(repositoryFullName: String, transform: (TrackedRelease) -> TrackedRelease) {
        mutableState.value = mutableState.value.copy(
            releases = mutableState.value.releases.map { item ->
                if (item.repository.fullName == repositoryFullName) transform(item) else item
            },
        )
    }
}

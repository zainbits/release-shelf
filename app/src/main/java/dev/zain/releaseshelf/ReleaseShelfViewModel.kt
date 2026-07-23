package dev.zain.releaseshelf

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.zain.releaseshelf.data.HostGitClient
import dev.zain.releaseshelf.data.LlmChatClient
import dev.zain.releaseshelf.data.LlmModelOption
import dev.zain.releaseshelf.data.LlmProfile
import dev.zain.releaseshelf.data.LlmProfileStore
import dev.zain.releaseshelf.data.LlmProviderKind
import dev.zain.releaseshelf.data.OpenRouterProviderOption
import dev.zain.releaseshelf.data.ReleaseInfo
import dev.zain.releaseshelf.data.ReleaseRepository
import dev.zain.releaseshelf.data.RepoWorkingTree
import dev.zain.releaseshelf.data.RepositoryId
import dev.zain.releaseshelf.data.SshSettings
import dev.zain.releaseshelf.data.SshSettingsStore
import dev.zain.releaseshelf.data.TrackedRelease
import dev.zain.releaseshelf.data.UpdateStatus
import dev.zain.releaseshelf.data.VersionBump
import dev.zain.releaseshelf.updater.ApkDownloadWorker
import dev.zain.releaseshelf.updater.InstallStatusBus
import java.io.File
import java.time.Instant
import java.util.UUID
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
    val scanningHost: Boolean = false,
    val tokenConfigured: Boolean = false,
    val sshConfigured: Boolean = false,
    val llmConfigured: Boolean = false,
    val lastChecked: Instant? = null,
    val message: String? = null,
    val sshSettings: SshSettings = SshSettings(),
    val llmProfiles: List<LlmProfile> = emptyList(),
    val activeLlmProfileId: String = "",
    val publishDraft: PublishDraft? = null,
    val modelOptions: List<LlmModelOption> = emptyList(),
    val modelLoadStatus: String? = null,
    val openRouterProviders: List<OpenRouterProviderOption> = emptyList(),
    val openRouterProviderLoadStatus: String? = null,
)

data class PublishDraft(
    val repository: RepositoryId,
    val generating: Boolean = false,
    val publishing: Boolean = false,
    val commitMessage: String = "",
    val bump: VersionBump = VersionBump.Patch,
    val bumpRationale: String = "",
    val statusPorcelain: String = "",
    val error: String? = null,
    val logTail: String = "",
)

data class InstallRequest(val file: File, val release: ReleaseInfo)

class ReleaseShelfViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ReleaseRepository(application)
    private val sshStore = SshSettingsStore(application)
    private val llmStore = LlmProfileStore(application).also { it.ensureDefaults() }
    private val hostGit = HostGitClient()
    private val llmClient = LlmChatClient(application)
    private val workManager = WorkManager.getInstance(application)
    private val handledSuccessIds = mutableSetOf<String>()

    private val mutableState = MutableStateFlow(
        ReleaseShelfState(
            sources = repository.sources(),
            tokenConfigured = repository.hasToken(),
            sshConfigured = sshStore.get().isConfigured,
            llmConfigured = llmStore.activeProfile()?.isConfigured == true,
            sshSettings = sshStore.get().copy(password = if (sshStore.get().password.isNotBlank()) "••••••••" else ""),
            llmProfiles = llmStore.profiles().map { it.redacted() },
            activeLlmProfileId = llmStore.activeProfileId(),
        ),
    )
    val state = mutableState.asStateFlow()

    private val mutableInstallRequests = MutableSharedFlow<InstallRequest>(extraBufferCapacity = 1)
    val installRequests = mutableInstallRequests.asSharedFlow()

    init {
        observeDownloads()
        observeInstallStatus()
        refreshSettingsFromDisk()
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
                releases = mergeTransientState(releases),
                refreshing = false,
                tokenConfigured = repository.hasToken(),
                lastChecked = Instant.now(),
            )
            scanHostWorkingTrees()
        }
    }

    fun scanHostWorkingTrees() {
        val settings = sshStore.get()
        if (!settings.isConfigured) {
            mutableState.value = mutableState.value.copy(
                sshConfigured = false,
                scanningHost = false,
                releases = mutableState.value.releases.map {
                    it.copy(hasUncommittedChanges = false, dirtySummary = null, hostError = null)
                },
            )
            return
        }
        if (mutableState.value.scanningHost) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(scanningHost = true)
            val trees = withContext(Dispatchers.IO) {
                runCatching {
                    hostGit.scanWorkingTrees(settings, mutableState.value.sources)
                }.getOrElse { error ->
                    mutableState.value.sources.map {
                        RepoWorkingTree(
                            repository = it,
                            localPath = "",
                            isDirty = false,
                            statusPorcelain = "",
                            error = error.message ?: "SSH scan failed",
                        )
                    }
                }
            }
            val byRepo = trees.associateBy { it.repository.fullName.lowercase() }
            mutableState.value = mutableState.value.copy(
                scanningHost = false,
                sshConfigured = true,
                releases = mutableState.value.releases.map { item ->
                    val tree = byRepo[item.repository.fullName.lowercase()]
                    item.copy(
                        hasUncommittedChanges = tree?.isDirty == true,
                        dirtySummary = tree?.statusPorcelain
                            ?.lineSequence()
                            ?.filter { it.isNotBlank() }
                            ?.take(3)
                            ?.joinToString(" · ")
                            ?.take(120),
                        hostError = tree?.error,
                    )
                },
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

    fun saveSshSettings(settings: SshSettings) {
        val existing = sshStore.get()
        val password = when {
            settings.password.isBlank() -> existing.password
            settings.password == MASKED_SECRET && existing.password.isNotBlank() -> existing.password
            else -> settings.password
        }
        val toSave = settings.copy(password = password)
        sshStore.save(toSave)
        refreshSettingsFromDisk()
        mutableState.value = mutableState.value.copy(
            message = if (toSave.isConfigured) "SSH settings saved" else "SSH settings saved (incomplete)",
        )
        scanHostWorkingTrees()
    }

    fun testSshConnection(settings: SshSettings) {
        viewModelScope.launch {
            val existing = sshStore.get()
            val password = when {
                settings.password.isBlank() -> existing.password
                settings.password == MASKED_SECRET && existing.password.isNotBlank() -> existing.password
                else -> settings.password
            }
            val result = withContext(Dispatchers.IO) {
                hostGit.testConnection(settings.copy(password = password))
            }
            mutableState.value = mutableState.value.copy(
                message = result.fold(
                    onSuccess = { "SSH OK: ${it.lineSequence().firstOrNull() ?: "connected"}" },
                    onFailure = { it.message ?: "SSH connection failed" },
                ),
            )
        }
    }

    fun setActiveLlmProfile(id: String) {
        llmStore.setActiveProfileId(id)
        refreshSettingsFromDisk()
    }

    fun saveLlmProfile(profile: LlmProfile) {
        val existing = llmStore.profiles().firstOrNull { it.id == profile.id }
        val apiKey = when {
            profile.apiKey.isBlank() -> existing?.apiKey.orEmpty()
            profile.apiKey == MASKED_SECRET -> existing?.apiKey.orEmpty()
            else -> profile.apiKey
        }
        llmStore.upsert(profile.copy(apiKey = apiKey))
        llmStore.setActiveProfileId(profile.id)
        refreshSettingsFromDisk()
        mutableState.value = mutableState.value.copy(message = "LLM profile saved")
    }

    fun addLlmPreset(kind: LlmProviderKind) {
        val profile = LlmProfile.preset(kind).copy(
            id = UUID.randomUUID().toString(),
            name = kind.label,
        )
        llmStore.upsert(profile)
        llmStore.setActiveProfileId(profile.id)
        refreshSettingsFromDisk()
        mutableState.value = mutableState.value.copy(message = "Added ${kind.label} profile")
    }

    fun deleteLlmProfile(id: String) {
        llmStore.delete(id)
        refreshSettingsFromDisk()
        mutableState.value = mutableState.value.copy(message = "LLM profile removed")
    }

    fun loadModelsForActiveProfile() {
        val profile = llmStore.activeProfile() ?: return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(modelLoadStatus = "Loading models…")
            val result = withContext(Dispatchers.IO) {
                runCatching { llmClient.fetchModels(profile) }
            }
            mutableState.value = result.fold(
                onSuccess = {
                    mutableState.value.copy(
                        modelOptions = it,
                        modelLoadStatus = if (it.isEmpty()) "No models returned" else "Loaded ${it.size} models",
                    )
                },
                onFailure = {
                    mutableState.value.copy(
                        modelOptions = emptyList(),
                        modelLoadStatus = it.message ?: "Could not load models",
                    )
                },
            )
        }
    }

    fun loadOpenRouterProviders(modelId: String) {
        val profile = llmStore.activeProfile() ?: return
        if (profile.kind != LlmProviderKind.OpenRouter) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                openRouterProviderLoadStatus = "Loading providers…",
            )
            val result = withContext(Dispatchers.IO) {
                runCatching { llmClient.fetchOpenRouterProviders(profile, modelId) }
            }
            mutableState.value = result.fold(
                onSuccess = {
                    mutableState.value.copy(
                        openRouterProviders = it,
                        openRouterProviderLoadStatus = if (it.isEmpty()) {
                            "No provider pins for this model"
                        } else {
                            "Loaded ${it.size} providers"
                        },
                    )
                },
                onFailure = {
                    mutableState.value.copy(
                        openRouterProviders = emptyList(),
                        openRouterProviderLoadStatus = it.message ?: "Could not load providers",
                    )
                },
            )
        }
    }

    fun startPublishFlow(repository: RepositoryId) {
        val ssh = sshStore.get()
        val profile = llmStore.activeProfile()
        when {
            !ssh.isConfigured -> {
                mutableState.value = mutableState.value.copy(message = "Configure SSH in Settings first")
                return
            }
            profile?.isConfigured != true -> {
                mutableState.value = mutableState.value.copy(message = "Configure an LLM profile in Settings first")
                return
            }
            mutableState.value.publishDraft != null -> {
                mutableState.value = mutableState.value.copy(message = "Finish or dismiss the open publish draft first")
                return
            }
        }
        mutableState.value = mutableState.value.copy(
            publishDraft = PublishDraft(repository = repository, generating = true),
        )
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val diff = hostGit.fetchDiff(ssh, repository)
                    val suggestion = llmClient.suggestCommit(profile!!, repository, diff)
                    suggestion to diff.statusPorcelain
                }
            }
            outcome.fold(
                onSuccess = { (suggestion, status) ->
                    mutableState.value = mutableState.value.copy(
                        publishDraft = PublishDraft(
                            repository = repository,
                            generating = false,
                            commitMessage = suggestion.commitMessage,
                            bump = suggestion.bump,
                            bumpRationale = suggestion.bumpRationale,
                            statusPorcelain = status,
                        ),
                    )
                },
                onFailure = { error ->
                    mutableState.value = mutableState.value.copy(
                        publishDraft = PublishDraft(
                            repository = repository,
                            generating = false,
                            error = error.message ?: "Could not prepare publish",
                        ),
                    )
                },
            )
        }
    }

    fun updatePublishDraftMessage(message: String) {
        val draft = mutableState.value.publishDraft ?: return
        if (draft.publishing || draft.generating) return
        mutableState.value = mutableState.value.copy(publishDraft = draft.copy(commitMessage = message))
    }

    fun updatePublishDraftBump(bump: VersionBump) {
        val draft = mutableState.value.publishDraft ?: return
        if (draft.publishing || draft.generating) return
        mutableState.value = mutableState.value.copy(publishDraft = draft.copy(bump = bump))
    }

    fun dismissPublishDraft() {
        val draft = mutableState.value.publishDraft
        if (draft?.publishing == true) return
        mutableState.value = mutableState.value.copy(publishDraft = null)
    }

    fun confirmPublish() {
        val draft = mutableState.value.publishDraft ?: return
        if (draft.generating || draft.publishing) return
        if (draft.commitMessage.isBlank()) {
            mutableState.value = mutableState.value.copy(
                publishDraft = draft.copy(error = "Commit message is required"),
            )
            return
        }
        val ssh = sshStore.get()
        if (!ssh.isConfigured) {
            mutableState.value = mutableState.value.copy(message = "SSH is not configured")
            return
        }
        mutableState.value = mutableState.value.copy(
            publishDraft = draft.copy(publishing = true, error = null, logTail = "Starting remote publish…"),
        )
        updateRelease(draft.repository.fullName) {
            it.copy(publishPhase = "Publishing…")
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    hostGit.publish(
                        settings = ssh,
                        repository = draft.repository,
                        commitMessage = draft.commitMessage,
                        bump = draft.bump,
                    )
                }
            }
            result.fold(
                onSuccess = { publishResult ->
                    updateRelease(draft.repository.fullName) {
                        it.copy(
                            publishPhase = null,
                            hasUncommittedChanges = if (publishResult.success) false else it.hasUncommittedChanges,
                            dirtySummary = if (publishResult.success) null else it.dirtySummary,
                        )
                    }
                    mutableState.value = mutableState.value.copy(
                        publishDraft = if (publishResult.success) {
                            null
                        } else {
                            draft.copy(
                                publishing = false,
                                error = publishResult.message,
                                logTail = publishResult.log.takeLast(1_500),
                            )
                        },
                        message = publishResult.message,
                    )
                    if (publishResult.success) {
                        refresh()
                    }
                },
                onFailure = { error ->
                    updateRelease(draft.repository.fullName) { it.copy(publishPhase = null) }
                    mutableState.value = mutableState.value.copy(
                        publishDraft = draft.copy(
                            publishing = false,
                            error = error.message ?: "Publish failed",
                        ),
                        message = error.message ?: "Publish failed",
                    )
                },
            )
        }
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

    fun downloadAndInstall(release: ReleaseInfo) {
        startDownload(release, installWhenReady = true, force = false)
    }

    fun downloadOnly(release: ReleaseInfo, force: Boolean = false) {
        startDownload(release, installWhenReady = false, force = force)
    }

    fun installCached(release: ReleaseInfo) {
        val file = repository.cachedApk(release)
        if (file == null) {
            mutableState.value = mutableState.value.copy(
                message = "No cached APK for this release. Download it first.",
            )
            return
        }
        requestInstall(file, release)
    }

    fun onInstallStartFailed(release: ReleaseInfo, message: String?) {
        updateRelease(release.repository.fullName) { it.copy(installProgress = null) }
        mutableState.value = mutableState.value.copy(
            message = message ?: "Could not start the install",
        )
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

    fun activeProfileForEdit(): LlmProfile? = llmStore.activeProfile()?.copy(
        apiKey = if (llmStore.activeProfile()?.apiKey?.isNotBlank() == true) MASKED_SECRET else "",
    )

    private fun refreshSettingsFromDisk() {
        val ssh = sshStore.get()
        val profiles = llmStore.profiles()
        mutableState.value = mutableState.value.copy(
            sshConfigured = ssh.isConfigured,
            llmConfigured = llmStore.activeProfile()?.isConfigured == true,
            sshSettings = ssh.copy(
                password = if (ssh.password.isNotBlank()) MASKED_SECRET else "",
            ),
            llmProfiles = profiles.map { it.redacted() },
            activeLlmProfileId = llmStore.activeProfileId(),
        )
    }

    private fun requestInstall(file: File, release: ReleaseInfo) {
        val key = release.repository.fullName
        val existing = mutableState.value.releases.firstOrNull { it.repository.fullName == key }
        if (existing?.installProgress != null) return

        updateRelease(key) { it.copy(installProgress = 0f, downloadProgress = null) }
        mutableState.value = mutableState.value.copy(
            message = "Installing ${release.displayName}…",
        )
        viewModelScope.launch {
            mutableInstallRequests.emit(InstallRequest(file, release))
        }
    }

    private fun startDownload(
        release: ReleaseInfo,
        installWhenReady: Boolean,
        force: Boolean,
    ) {
        val key = release.repository.fullName
        val existing = mutableState.value.releases.firstOrNull { it.repository.fullName == key }
        if (existing?.downloadProgress != null && !force) return
        if (existing?.installProgress != null && !force) return

        if (force) {
            ApkDownloadWorker.cancel(getApplication(), key)
            repository.removeCached(release)
            updateRelease(key) { it.copy(isCached = false) }
        } else {
            repository.cachedApk(release)?.let { file ->
                updateRelease(key) { it.copy(isCached = true, downloadProgress = null) }
                if (installWhenReady) {
                    requestInstall(file, release)
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

    private fun observeInstallStatus() {
        viewModelScope.launch {
            InstallStatusBus.events.collect { event ->
                when (event) {
                    is InstallStatusBus.Event.Started -> {
                        updateRelease(event.repositoryFullName) {
                            it.copy(installProgress = 0f, downloadProgress = null)
                        }
                    }
                    is InstallStatusBus.Event.Progress -> {
                        updateRelease(event.repositoryFullName) {
                            it.copy(
                                installProgress = event.progress.coerceIn(0f, 1f),
                                downloadProgress = null,
                            )
                        }
                    }
                    is InstallStatusBus.Event.Finished -> {
                        applyInstallFinished(event)
                    }
                }
            }
        }
    }

    private fun applyInstallFinished(event: InstallStatusBus.Event.Finished) {
        val repoKey = event.repositoryFullName
            ?: event.packageName?.let { pkg ->
                mutableState.value.releases.firstOrNull { it.release?.packageName == pkg }
                    ?.repository?.fullName
            }
            ?: mutableState.value.releases.firstOrNull {
                it.release?.displayName == event.displayName && it.installProgress != null
            }?.repository?.fullName

        if (repoKey != null) {
            updateRelease(repoKey) { item ->
                item.copy(
                    installProgress = null,
                    isCached = if (event.success) false else item.isCached,
                )
            }
        } else {
            mutableState.value = mutableState.value.copy(
                releases = mutableState.value.releases.map { item ->
                    if (item.installProgress != null) item.copy(installProgress = null) else item
                },
            )
        }

        mutableState.value = mutableState.value.copy(
            message = when {
                event.success -> "${event.displayName} installed"
                event.message != null -> event.message
                else -> "Could not install ${event.displayName}"
            },
        )
        if (event.success) {
            refresh()
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
                        if (item.repository.fullName == repo &&
                            item.downloadProgress == null &&
                            item.installProgress == null
                        ) {
                            item.copy(downloadProgress = 0f)
                        } else {
                            item
                        }
                    }
                }
                WorkInfo.State.RUNNING -> {
                    val progress = info.progress.getFloat(ApkDownloadWorker.KEY_PROGRESS, 0f)
                    releases = releases.map { item ->
                        if (item.repository.fullName == repo && item.installProgress == null) {
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
                                mutableState.value = mutableState.value.copy(releases = releases)
                                requestInstall(file, release)
                                return
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

    private fun mergeTransientState(releases: List<TrackedRelease>): List<TrackedRelease> {
        val current = mutableState.value.releases.associateBy { it.repository.fullName }
        return releases.map { loaded ->
            val previous = current[loaded.repository.fullName]
            val alreadyInstalled = loaded.status == UpdateStatus.CURRENT ||
                loaded.status == UpdateStatus.INSTALLED_NEWER
            loaded.copy(
                downloadProgress = if (alreadyInstalled) null else previous?.downloadProgress,
                installProgress = if (alreadyInstalled) null else previous?.installProgress,
                isCached = if (alreadyInstalled) {
                    false
                } else {
                    loaded.isCached || (previous?.isCached == true && loaded.release != null &&
                        previous.release?.let { repository.isCached(it) } == true)
                },
                hasUncommittedChanges = previous?.hasUncommittedChanges == true,
                dirtySummary = previous?.dirtySummary,
                hostError = previous?.hostError,
                publishPhase = previous?.publishPhase,
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

    private fun LlmProfile.redacted(): LlmProfile = copy(
        apiKey = if (apiKey.isNotBlank()) MASKED_SECRET else "",
    )

    companion object {
        const val MASKED_SECRET = "••••••••"
    }
}

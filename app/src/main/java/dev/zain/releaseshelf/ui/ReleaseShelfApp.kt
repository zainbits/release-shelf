package dev.zain.releaseshelf.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.zain.releaseshelf.PublishDraft
import dev.zain.releaseshelf.ReleaseShelfState
import dev.zain.releaseshelf.ReleaseShelfViewModel
import dev.zain.releaseshelf.data.ReleaseInfo
import dev.zain.releaseshelf.data.RepositoryId
import dev.zain.releaseshelf.data.TrackedRelease
import dev.zain.releaseshelf.data.UpdateStatus
import dev.zain.releaseshelf.data.VersionBump
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Destination { UPDATES, SOURCES, SETTINGS }
private enum class ReleaseFilter { ALL, UPDATES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseShelfApp(viewModel: ReleaseShelfViewModel) {
    val state by viewModel.state.collectAsState()
    var destination by rememberSaveable { mutableStateOf(Destination.UPDATES) }
    var addDialogVisible by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            when (destination) {
                                Destination.UPDATES -> "ReleaseShelf"
                                Destination.SOURCES -> "Sources"
                                Destination.SETTINGS -> "Settings"
                            },
                        )
                        Text(
                            text = when (destination) {
                                Destination.UPDATES -> "Your apps, directly from GitHub"
                                Destination.SOURCES -> "Repositories and access"
                                Destination.SETTINGS -> "SSH host and LLM profiles"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (destination == Destination.UPDATES) {
                        IconButton(
                            onClick = {
                                viewModel.refresh()
                            },
                            enabled = !state.refreshing && !state.scanningHost,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Check for updates")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = destination == Destination.UPDATES,
                    onClick = { destination = Destination.UPDATES },
                    icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    label = { Text("Updates") },
                )
                NavigationBarItem(
                    selected = destination == Destination.SOURCES,
                    onClick = { destination = Destination.SOURCES },
                    icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
                    label = { Text("Sources") },
                )
                NavigationBarItem(
                    selected = destination == Destination.SETTINGS,
                    onClick = { destination = Destination.SETTINGS },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
        floatingActionButton = {
            if (destination == Destination.SOURCES) {
                FloatingActionButton(onClick = { addDialogVisible = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add repository")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (destination) {
            Destination.UPDATES -> UpdatesScreen(
                state = state,
                contentPadding = padding,
                onRefresh = viewModel::refresh,
                onDownloadAndInstall = viewModel::downloadAndInstall,
                onDownloadOnly = viewModel::downloadOnly,
                onInstallCached = viewModel::installCached,
                onCancelDownload = viewModel::cancelDownload,
                onRemoveCached = viewModel::removeCached,
                onPublishLocal = viewModel::startPublishFlow,
                onOpenSources = { destination = Destination.SOURCES },
                onOpenSettings = { destination = Destination.SETTINGS },
            )
            Destination.SOURCES -> SourcesScreen(
                state = state,
                contentPadding = padding,
                onSaveToken = viewModel::saveToken,
                onRemove = viewModel::removeSource,
            )
            Destination.SETTINGS -> SettingsScreen(
                state = state,
                contentPadding = padding,
                onSaveSsh = viewModel::saveSshSettings,
                onTestSsh = viewModel::testSshConnection,
                onSelectProfile = viewModel::setActiveLlmProfile,
                onSaveProfile = viewModel::saveLlmProfile,
                onAddPreset = viewModel::addLlmPreset,
                onDeleteProfile = viewModel::deleteLlmProfile,
                onLoadModels = viewModel::loadModelsForActiveProfile,
                onLoadProviders = viewModel::loadOpenRouterProviders,
                activeProfile = viewModel.activeProfileForEdit(),
            )
        }
    }

    if (addDialogVisible) {
        AddSourceDialog(
            onDismiss = { addDialogVisible = false },
            onAdd = { value ->
                if (viewModel.addSource(value)) addDialogVisible = false
            },
        )
    }

    state.publishDraft?.let { draft ->
        PublishDraftDialog(
            draft = draft,
            onMessageChange = viewModel::updatePublishDraftMessage,
            onBumpChange = viewModel::updatePublishDraftBump,
            onConfirm = viewModel::confirmPublish,
            onDismiss = viewModel::dismissPublishDraft,
        )
    }
}

@Composable
private fun UpdatesScreen(
    state: ReleaseShelfState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onDownloadAndInstall: (ReleaseInfo) -> Unit,
    onDownloadOnly: (ReleaseInfo, Boolean) -> Unit,
    onInstallCached: (ReleaseInfo) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveCached: (ReleaseInfo) -> Unit,
    onPublishLocal: (RepositoryId) -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ReleaseFilter.ALL) }
    val updateCount = state.releases.count { it.status == UpdateStatus.UPDATE_AVAILABLE }
    val cachedCount = state.releases.count { it.isCached }
    val dirtyCount = state.releases.count { it.hasUncommittedChanges }
    val visibleReleases = state.releases.filter {
        filter == ReleaseFilter.ALL || it.status == UpdateStatus.UPDATE_AVAILABLE
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            UpdateSummaryCard(
                updateCount = updateCount,
                sourceCount = state.sources.size,
                cachedCount = cachedCount,
                dirtyCount = dirtyCount,
                refreshing = state.refreshing || state.scanningHost,
                lastChecked = state.lastChecked,
                onRefresh = onRefresh,
            )
        }
        if (!state.tokenConfigured) {
            item { AccessBanner(onOpenSources) }
        }
        if (!state.sshConfigured) {
            item {
                HostSetupBanner(
                    title = "Build host not configured",
                    body = "Add SSH password access to scan uncommitted changes and publish from each card.",
                    actionLabel = "Settings",
                    onAction = onOpenSettings,
                )
            }
        } else if (!state.llmConfigured) {
            item {
                HostSetupBanner(
                    title = "LLM profile incomplete",
                    body = "Add an API key under Settings so dirty cards can generate commit messages.",
                    actionLabel = "Settings",
                    onAction = onOpenSettings,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == ReleaseFilter.ALL,
                    onClick = { filter = ReleaseFilter.ALL },
                    label = { Text("All ${state.sources.size}") },
                    leadingIcon = if (filter == ReleaseFilter.ALL) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
                FilterChip(
                    selected = filter == ReleaseFilter.UPDATES,
                    onClick = { filter = ReleaseFilter.UPDATES },
                    label = { Text("Updates $updateCount") },
                    leadingIcon = if (filter == ReleaseFilter.UPDATES) {
                        { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        if (!state.refreshing && visibleReleases.isEmpty()) {
            item {
                EmptyState(
                    title = if (filter == ReleaseFilter.UPDATES) "Everything is current" else "No releases yet",
                    body = if (filter == ReleaseFilter.UPDATES) {
                        "There are no newer builds on your shelf."
                    } else {
                        "Add a source or publish an APK release to get started."
                    },
                        icon = if (filter == ReleaseFilter.UPDATES) Icons.Outlined.CheckCircle else Icons.AutoMirrored.Outlined.List,
                )
            }
        }
        items(visibleReleases, key = { it.repository.fullName }) { item ->
            ReleaseCard(
                item = item,
                onDownloadAndInstall = onDownloadAndInstall,
                onDownloadOnly = onDownloadOnly,
                onInstallCached = onInstallCached,
                onCancelDownload = onCancelDownload,
                onRemoveCached = onRemoveCached,
                onPublishLocal = onPublishLocal,
            )
        }
        if (state.refreshing && state.releases.isEmpty()) {
            items(3) { LoadingCard() }
        }
    }
}

@Composable
private fun UpdateSummaryCard(
    updateCount: Int,
    sourceCount: Int,
    cachedCount: Int,
    dirtyCount: Int,
    refreshing: Boolean,
    lastChecked: Instant?,
    onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val title = when {
        refreshing -> "Checking your shelf…"
        updateCount == 0 && dirtyCount == 0 -> "You’re up to date"
        updateCount == 0 && dirtyCount > 0 -> "$dirtyCount local change${if (dirtyCount == 1) "" else "s"}"
        updateCount == 1 -> "1 update is ready"
        else -> "$updateCount updates are ready"
    }
    val subtitle = buildString {
        if (lastChecked == null) {
            append("$sourceCount sources tracked")
        } else {
            append("$sourceCount sources · checked ${formatTime(lastChecked)}")
        }
        if (cachedCount > 0) {
            append(" · $cachedCount cached")
        }
        if (dirtyCount > 0) {
            append(" · $dirtyCount dirty")
        }
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = Color.Transparent),
    ) {
        // fillMaxWidth keeps the gradient edge-to-edge. Single-row layout + always-present
        // action button keep checking and idle states the same height.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(colors.primaryContainer, colors.tertiaryContainer),
                    ),
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = colors.surface.copy(alpha = 0.82f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary,
                        )
                    } else {
                        Icon(
                            imageVector = if (updateCount > 0) {
                                Icons.Outlined.Refresh
                            } else {
                                Icons.Outlined.CheckCircle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = colors.primary,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.76f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !refreshing,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                    .defaultMinSize(minWidth = 96.dp)
                    .height(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Check", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun AccessBanner(onOpenSources: () -> Unit) {
    HostSetupBanner(
        title = "Private repository access",
        body = "Add a read-only GitHub token to check private releases.",
        actionLabel = "Set up",
        onAction = onOpenSources,
    )
}

@Composable
private fun HostSetupBanner(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun ReleaseCard(
    item: TrackedRelease,
    onDownloadAndInstall: (ReleaseInfo) -> Unit,
    onDownloadOnly: (ReleaseInfo, Boolean) -> Unit,
    onInstallCached: (ReleaseInfo) -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveCached: (ReleaseInfo) -> Unit,
    onPublishLocal: (RepositoryId) -> Unit,
) {
    val context = LocalContext.current
    val release = item.release
    val downloading = item.downloadProgress != null
    val installing = item.installProgress != null
    val publishing = item.publishPhase != null
    val busy = downloading || installing || publishing
    // Only offer install/download when the shelf version is not already installed.
    val needsInstall = item.status == UpdateStatus.UPDATE_AVAILABLE ||
        item.status == UpdateStatus.NOT_INSTALLED
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMonogram(item.repository.name)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release?.displayName ?: item.repository.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.repository.fullName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (release?.releaseUrl?.isNotBlank() == true) {
                    IconButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, release.releaseUrl.toUri()))
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ExitToApp, contentDescription = "Open GitHub release")
                    }
                }
            }

            if (item.error != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        item.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (release != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(statusTitle(item), style = MaterialTheme.typography.labelLarge)
                        Text(
                            versionSummary(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        release.apk.sizeBytes.takeIf { it > 0 }?.let { bytes ->
                            Text(
                                "APK ${formatFileSize(bytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (item.isCached && needsInstall) {
                        val scheme = MaterialTheme.colorScheme
                        AssistChip(
                            onClick = {},
                            label = { Text("Cached") },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = scheme.tertiaryContainer,
                                labelColor = scheme.onTertiaryContainer,
                                leadingIconContentColor = scheme.onTertiaryContainer,
                            ),
                            border = null,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    StatusChip(item.status)
                }
                if (item.hasUncommittedChanges || item.hostError != null || publishing) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                when {
                                    publishing -> item.publishPhase ?: "Publishing…"
                                    item.hostError != null -> "Host: ${item.hostError}"
                                    else -> "Uncommitted changes on build host"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = if (item.hostError != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            item.dirtySummary?.takeIf { it.isNotBlank() }?.let { summary ->
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (item.hasUncommittedChanges && !busy) {
                                Button(
                                    onClick = { onPublishLocal(item.repository) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Publish local changes")
                                }
                            }
                            if (publishing) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = downloading && needsInstall) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = { item.downloadProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                buildString {
                                    append("Downloading ${(100 * (item.downloadProgress ?: 0f)).toInt()}%")
                                    release.apk.sizeBytes.takeIf { it > 0 }?.let { bytes ->
                                        append(" · ${formatFileSize(bytes)}")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onCancelDownload(item.repository.fullName) }) {
                                Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel")
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = installing && needsInstall) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val progress = item.installProgress ?: 0f
                        if (progress <= 0f) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            buildString {
                                append("Installing")
                                if (progress > 0f) {
                                    append(" ${(100 * progress).toInt()}%")
                                } else {
                                    append("…")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (needsInstall && !busy) {
                    if (item.isCached) {
                        Button(
                            onClick = { onInstallCached(release) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Install cached APK")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onDownloadOnly(release, true) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Re-download")
                            }
                            TextButton(onClick = { onRemoveCached(release) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Remove")
                            }
                        }
                    } else {
                        Button(
                            onClick = { onDownloadAndInstall(release) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Download & install")
                        }
                        OutlinedButton(
                            onClick = { onDownloadOnly(release, false) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download only")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppMonogram(name: String) {
    Surface(
        modifier = Modifier.size(52.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "A",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun StatusChip(status: UpdateStatus) {
    val (label, icon) = when (status) {
        UpdateStatus.UPDATE_AVAILABLE -> "Update" to Icons.Outlined.Refresh
        UpdateStatus.CURRENT -> "Current" to Icons.Outlined.CheckCircle
        UpdateStatus.NOT_INSTALLED -> "Available" to Icons.AutoMirrored.Outlined.List
        UpdateStatus.INSTALLED_NEWER -> "Ahead" to Icons.Outlined.CheckCircle
        UpdateStatus.UNKNOWN -> "Unknown" to Icons.Outlined.Warning
    }
    // Update uses filled primary so it stays high-contrast (primaryContainer alone looked ghostly).
    val isUpdate = status == UpdateStatus.UPDATE_AVAILABLE
    val colors = MaterialTheme.colorScheme
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (isUpdate) colors.primary else colors.surfaceContainerHigh,
            labelColor = if (isUpdate) colors.onPrimary else colors.onSurface,
            leadingIconContentColor = if (isUpdate) colors.onPrimary else colors.onSurfaceVariant,
        ),
        border = null,
    )
}

@Composable
private fun SourcesScreen(
    state: ReleaseShelfState,
    contentPadding: PaddingValues,
    onSaveToken: (String) -> Unit,
    onRemove: (RepositoryId) -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<RepositoryId?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 96.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Lock, contentDescription = null)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("GitHub access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (state.tokenConfigured) "Token configured" else "Required for private repositories",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.tokenConfigured) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = "Configured", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "Use a fine-grained token with read-only Contents access for only the repositories on this shelf. It is encrypted by Android Keystore and excluded from backups.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.tokenConfigured) "Replace token" else "Fine-grained token") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSaveToken(token)
                                token = ""
                            },
                            enabled = token.isNotBlank(),
                        ) { Text("Save access") }
                        if (state.tokenConfigured) {
                            TextButton(onClick = { onSaveToken("") }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Tracked repositories",
                modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(state.sources, key = { it.fullName }) { source ->
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppMonogram(source.name)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            source.owner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { pendingRemoval = source }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove ${source.name}")
                    }
                }
            }
        }
    }

    pendingRemoval?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Remove ${source.name}?") },
            text = { Text("ReleaseShelf will stop checking this repository. No installed app will be changed.") },
            confirmButton = {
                Button(onClick = {
                    onRemove(source)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PublishDraftDialog(
    draft: PublishDraft,
    onMessageChange: (String) -> Unit,
    onBumpChange: (VersionBump) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!draft.publishing && !draft.generating) onDismiss()
        },
        icon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
        title = { Text("Publish ${draft.repository.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    draft.generating -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("Fetching diff and generating commit message…")
                        }
                    }
                    draft.publishing -> {
                        Text("Running remote commit, version bump, push, and androidrun --publish. This can take several minutes.")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (draft.logTail.isNotBlank()) {
                            Text(
                                draft.logTail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    else -> {
                        draft.statusPorcelain.takeIf { it.isNotBlank() }?.let { status ->
                            Text(
                                status.lineSequence().take(8).joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedTextField(
                            value = draft.commitMessage,
                            onValueChange = onMessageChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            label = { Text("Commit message") },
                        )
                        Text("Version bump", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            VersionBump.entries.forEach { level ->
                                FilterChip(
                                    selected = draft.bump == level,
                                    onClick = { onBumpChange(level) },
                                    label = { Text(level.cli) },
                                )
                            }
                        }
                        if (draft.bumpRationale.isNotBlank()) {
                            Text(
                                "Suggested ${draft.bump.cli}: ${draft.bumpRationale}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "Confirms: git commit → androidrun --bump → bump commit → git push → androidrun --publish",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        draft.error?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (draft.logTail.isNotBlank()) {
                            Text(
                                draft.logTail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                draft.generating -> {}
                draft.publishing -> {}
                else -> Button(
                    onClick = onConfirm,
                    enabled = draft.commitMessage.isNotBlank(),
                ) { Text("Commit & publish") }
            }
        },
        dismissButton = {
            if (!draft.publishing) {
                TextButton(onClick = onDismiss, enabled = !draft.generating) {
                    Text(if (draft.generating) "Working…" else "Cancel")
                }
            }
        },
    )
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = null) },
        title = { Text("Add repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Track the latest published APK from a GitHub repository.")
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("owner/repository") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(value) }, enabled = value.isNotBlank()) { Text("Add source") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EmptyState(title: String, body: String, icon: ImageVector) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                }
            }
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun statusTitle(item: TrackedRelease): String = when {
    item.installProgress != null -> "Installing ${item.release?.versionName ?: "update"}…"
    item.downloadProgress != null -> "Downloading ${item.release?.versionName ?: "update"}…"
    item.status == UpdateStatus.CURRENT -> "Latest version installed"
    item.status == UpdateStatus.INSTALLED_NEWER -> "Installed build is newer"
    item.isCached && item.status == UpdateStatus.UPDATE_AVAILABLE ->
        "Version ${item.release?.versionName} downloaded"
    item.isCached && item.status == UpdateStatus.NOT_INSTALLED ->
        "Downloaded and ready to install"
    item.status == UpdateStatus.UPDATE_AVAILABLE -> "Version ${item.release?.versionName} is ready"
    item.status == UpdateStatus.NOT_INSTALLED -> "Ready to install"
    else -> "Version status unavailable"
}

private fun versionSummary(item: TrackedRelease): String {
    val installed = item.installed?.versionName
    val latest = item.release?.versionName ?: "Unknown"
    return if (installed == null) "Latest $latest" else "Installed $installed · latest $latest"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return String.format("%.0f KB", kib)
    val mib = kib / 1024.0
    return if (mib < 10) {
        String.format("%.1f MB", mib)
    } else {
        String.format("%.0f MB", mib)
    }
}

private fun formatTime(instant: Instant): String = DateTimeFormatter.ofPattern("h:mm a")
    .withZone(ZoneId.systemDefault())
    .format(instant)

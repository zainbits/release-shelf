package dev.zain.releaseshelf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zain.releaseshelf.ReleaseShelfState
import dev.zain.releaseshelf.ReleaseShelfViewModel
import dev.zain.releaseshelf.data.LlmProfile
import dev.zain.releaseshelf.data.LlmProviderKind
import dev.zain.releaseshelf.data.OpenRouterProviderOption
import dev.zain.releaseshelf.data.ReasoningEffort
import dev.zain.releaseshelf.data.SshSettings
import dev.zain.releaseshelf.data.SshSettingsStore

@Composable
fun SettingsScreen(
    state: ReleaseShelfState,
    contentPadding: PaddingValues,
    onSaveSsh: (SshSettings) -> Unit,
    onTestSsh: (SshSettings) -> Unit,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (LlmProfile) -> Unit,
    onAddPreset: (LlmProviderKind) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onLoadModels: () -> Unit,
    onLoadProviders: (String) -> Unit,
    activeProfile: LlmProfile?,
) {
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
            SshSettingsCard(
                initial = state.sshSettings,
                configured = state.sshConfigured,
                onSave = onSaveSsh,
                onTest = onTestSsh,
            )
        }
        item {
            Text(
                "LLM profiles",
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAddPreset(LlmProviderKind.OpenRouter) }) {
                    Text("Add OpenRouter")
                }
                OutlinedButton(onClick = { onAddPreset(LlmProviderKind.Cerebras) }) {
                    Text("Add Cerebras")
                }
            }
        }
        items(state.llmProfiles, key = { it.id }) { profile ->
            FilterChip(
                selected = profile.id == state.activeLlmProfileId,
                onClick = { onSelectProfile(profile.id) },
                label = {
                    Text(
                        buildString {
                            append(profile.name)
                            if (profile.apiKey.isNotBlank()) append(" · key set")
                        },
                    )
                },
                leadingIcon = if (profile.id == state.activeLlmProfileId) {
                    {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
        item {
            LlmProfileEditorCard(
                profile = activeProfile,
                modelOptions = state.modelOptions.map { it.id },
                modelLoadStatus = state.modelLoadStatus,
                openRouterProviders = state.openRouterProviders,
                openRouterProviderLoadStatus = state.openRouterProviderLoadStatus,
                canDelete = state.llmProfiles.size > 1,
                onSave = onSaveProfile,
                onDelete = { id -> onDeleteProfile(id) },
                onLoadModels = onLoadModels,
                onLoadProviders = onLoadProviders,
            )
        }
    }
}

@Composable
private fun SshSettingsCard(
    initial: SshSettings,
    configured: Boolean,
    onSave: (SshSettings) -> Unit,
    onTest: (SshSettings) -> Unit,
) {
    var host by rememberSaveable(initial.host) { mutableStateOf(initial.host) }
    var port by rememberSaveable(initial.port) { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable(initial.username) { mutableStateOf(initial.username) }
    var password by rememberSaveable(initial.password) { mutableStateOf(initial.password) }
    var projectsRoot by rememberSaveable(initial.projectsRoot) {
        mutableStateOf(initial.projectsRoot.ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT })
    }

    LaunchedEffect(initial) {
        host = initial.host
        port = initial.port.toString()
        username = initial.username
        password = initial.password
        projectsRoot = initial.projectsRoot.ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT }
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Build host (SSH)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (configured) "Password auth configured" else "Required to scan dirty repos and publish",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (configured) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = "Configured",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "ReleaseShelf connects over SSH with a password, checks git status under the projects root, and runs commit/bump/publish on the host.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host") },
                singleLine = true,
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Port") },
                singleLine = true,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (password == ReleaseShelfViewModel.MASKED_SECRET) "Password (saved)" else "Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            )
            OutlinedTextField(
                value = projectsRoot,
                onValueChange = { projectsRoot = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Projects root") },
                supportingText = { Text("Repos are matched as \$root/<repositoryName>") },
                singleLine = true,
            )
            val draft = SshSettings(
                host = host.trim(),
                port = port.toIntOrNull() ?: 22,
                username = username.trim(),
                password = password,
                projectsRoot = projectsRoot.trim().ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }) { Text("Save SSH") }
                OutlinedButton(onClick = { onTest(draft) }) { Text("Test") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LlmProfileEditorCard(
    profile: LlmProfile?,
    modelOptions: List<String>,
    modelLoadStatus: String?,
    openRouterProviders: List<OpenRouterProviderOption>,
    openRouterProviderLoadStatus: String?,
    canDelete: Boolean,
    onSave: (LlmProfile) -> Unit,
    onDelete: (String) -> Unit,
    onLoadModels: () -> Unit,
    onLoadProviders: (String) -> Unit,
) {
    if (profile == null) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                "No LLM profile selected",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var kind by remember(profile.id) { mutableStateOf(profile.kind) }
    var baseUrl by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var model by remember(profile.id) { mutableStateOf(profile.model) }
    var apiKey by remember(profile.id) { mutableStateOf(profile.apiKey) }
    var providerSlug by remember(profile.id) { mutableStateOf(profile.openRouterProviderSlug) }
    var reasoning by remember(profile.id) { mutableStateOf(profile.reasoningEffort) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(profile.id, profile.name, profile.model, profile.apiKey, revision) {
        name = profile.name
        kind = profile.kind
        baseUrl = profile.baseUrl
        model = profile.model
        apiKey = profile.apiKey
        providerSlug = profile.openRouterProviderSlug
        reasoning = profile.reasoningEffort
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Edit profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "OpenAI-compatible chat completions from the app. Reasoning defaults to None so models that reject reasoning fields still work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Profile name") },
                singleLine = true,
            )
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LlmProviderKind.entries.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = {
                            kind = option
                            if (baseUrl.isBlank() || baseUrl == profile.kind.defaultBaseUrl) {
                                baseUrl = option.defaultBaseUrl
                            }
                            if (model.isBlank() || model == profile.kind.defaultModel) {
                                model = option.defaultModel
                            }
                        },
                        label = { Text(option.label) },
                    )
                }
            }
            if (kind != LlmProviderKind.OpenRouter) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API base URL") },
                    singleLine = true,
                    enabled = kind == LlmProviderKind.Custom || kind == LlmProviderKind.Cerebras,
                )
            } else {
                Text(
                    "OpenRouter base URL is fixed. Pick a model, then optionally pin a provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (apiKey == ReleaseShelfViewModel.MASKED_SECRET) "API key (saved)" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Model") },
                    singleLine = true,
                )
                OutlinedButton(onClick = onLoadModels) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Load")
                }
            }
            if (modelOptions.isNotEmpty()) {
                Box {
                    OutlinedButton(onClick = { modelMenuOpen = true }) {
                        Text("Pick loaded model (${modelOptions.size})")
                    }
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        modelOptions.take(80).forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    model = option
                                    modelMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            modelLoadStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == LlmProviderKind.OpenRouter) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = providerSlug,
                        onValueChange = { providerSlug = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("OpenRouter provider pin") },
                        supportingText = { Text("Optional slug; leave blank for auto routing") },
                        singleLine = true,
                    )
                    OutlinedButton(
                        onClick = { onLoadProviders(model) },
                        enabled = model.isNotBlank(),
                    ) { Text("Load") }
                }
                if (openRouterProviders.isNotEmpty()) {
                    Box {
                        OutlinedButton(onClick = { providerMenuOpen = true }) {
                            Text(
                                openRouterProviders.firstOrNull { it.slug == providerSlug }?.name
                                    ?: if (providerSlug.isBlank()) "Pick provider" else providerSlug,
                            )
                        }
                        DropdownMenu(
                            expanded = providerMenuOpen,
                            onDismissRequest = { providerMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Automatic routing") },
                                onClick = {
                                    providerSlug = ""
                                    providerMenuOpen = false
                                },
                            )
                            openRouterProviders.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name) },
                                    onClick = {
                                        providerSlug = option.slug
                                        providerMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                openRouterProviderLoadStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Reasoning effort", style = MaterialTheme.typography.labelLarge)
            Text(
                "Default None — not all models support reasoning fields.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningEffort.entries.forEach { option ->
                    FilterChip(
                        selected = reasoning == option,
                        onClick = { reasoning = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            profile.copy(
                                name = name,
                                kind = kind,
                                baseUrl = when (kind) {
                                    LlmProviderKind.OpenRouter -> LlmProviderKind.OpenRouter.defaultBaseUrl
                                    else -> baseUrl
                                },
                                model = model,
                                apiKey = apiKey,
                                openRouterProviderSlug = if (kind == LlmProviderKind.OpenRouter) providerSlug else "",
                                reasoningEffort = reasoning,
                            ),
                        )
                        revision++
                    },
                ) { Text("Save profile") }
                if (canDelete) {
                    TextButton(onClick = { onDelete(profile.id) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
            }
        }
    }
}

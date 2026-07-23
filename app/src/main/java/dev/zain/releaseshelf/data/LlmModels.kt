package dev.zain.releaseshelf.data

import java.util.UUID

enum class LlmProviderKind(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    OpenRouter(
        id = "openrouter",
        label = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
    ),
    Cerebras(
        id = "cerebras",
        label = "Cerebras",
        defaultBaseUrl = "https://api.cerebras.ai/v1",
        defaultModel = "llama-3.3-70b",
    ),
    Custom(
        id = "custom",
        label = "Custom OpenAI-compatible",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
    ),
    ;

    companion object {
        fun fromId(id: String): LlmProviderKind =
            entries.firstOrNull { it.id == id } ?: Custom
    }
}

/**
 * Reasoning effort for OpenAI-compatible / OpenRouter calls.
 * [None] is the default because many models reject reasoning fields.
 */
enum class ReasoningEffort(
    val id: String,
    val label: String,
    val effortValue: String?,
) {
    None("none", "None", null),
    Auto("auto", "Provider default", null),
    Minimal("minimal", "Minimal", "minimal"),
    Low("low", "Low", "low"),
    Medium("medium", "Medium", "medium"),
    High("high", "High", "high"),
    XHigh("xhigh", "X-high", "xhigh"),
    ;

    companion object {
        fun fromId(id: String): ReasoningEffort =
            entries.firstOrNull { it.id == id } ?: None
    }
}

data class LlmProfile(
    val id: String,
    val name: String,
    val kind: LlmProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val openRouterProviderSlug: String = "",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.None,
) {
    val isConfigured: Boolean
        get() = effectiveBaseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    val effectiveBaseUrl: String
        get() = when (kind) {
            LlmProviderKind.OpenRouter -> LlmProviderKind.OpenRouter.defaultBaseUrl
            LlmProviderKind.Cerebras -> baseUrl.ifBlank { LlmProviderKind.Cerebras.defaultBaseUrl }
            LlmProviderKind.Custom -> baseUrl
        }.trim().trimEnd('/')

    companion object {
        fun preset(kind: LlmProviderKind): LlmProfile = LlmProfile(
            id = UUID.randomUUID().toString(),
            name = kind.label,
            kind = kind,
            baseUrl = kind.defaultBaseUrl,
            model = kind.defaultModel,
            reasoningEffort = ReasoningEffort.None,
        )
    }
}

data class CommitSuggestion(
    val commitMessage: String,
    val bump: VersionBump,
    val bumpRationale: String,
)

data class LlmModelOption(
    val id: String,
    val name: String?,
)

data class OpenRouterProviderOption(
    val slug: String,
    val name: String,
)

package dev.zain.releaseshelf.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object CommitResponseFormat {
    fun forProvider(provider: LlmProviderKind): JSONObject {
        if (provider != LlmProviderKind.Cerebras) {
            return JSONObject().put("type", "json_object")
        }
        val schema = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("commit_message", JSONObject().put("type", "string"))
                    .put(
                        "bump",
                        JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(listOf("patch", "minor", "major"))),
                    )
                    .put("bump_rationale", JSONObject().put("type", "string")),
            )
            .put(
                "required",
                JSONArray(listOf("commit_message", "bump", "bump_rationale")),
            )
            .put("additionalProperties", false)
        return JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "commit_suggestion")
                    .put("strict", true)
                    .put("schema", schema),
            )
    }
}

class LlmChatClient(private val appContext: Context) {
    fun suggestCommit(profile: LlmProfile, repository: RepositoryId, bundle: RepoDiffBundle): CommitSuggestion {
        require(profile.isConfigured) { "Configure an LLM profile in Settings first" }
        val systemPrompt = loadSystemPrompt()
        val userPrompt = buildString {
            appendLine("Repository: ${repository.fullName}")
            appendLine("Local path: ${bundle.localPath}")
            appendLine()
            appendLine("git status --porcelain:")
            appendLine(truncate(bundle.statusPorcelain.ifBlank { "(empty)" }, MAX_STATUS_CHARS))
            appendLine()
            appendLine("Diff / untracked summary (truncated; do NOT echo this back):")
            append(truncate(bundle.diff.ifBlank { "(no diff text)" }, MAX_DIFF_CHARS))
            appendLine()
            appendLine("Respond with a small JSON object only. Do not repeat the diff.")
        }
        return try {
            val content = chatCompletion(profile, systemPrompt, userPrompt)
            CommitSuggestionParser.parse(content)
        } catch (_: InvalidCommitSuggestionException) {
            val retryPrompt = buildString {
                append(userPrompt)
                appendLine()
                appendLine()
                appendLine("RETRY: The previous response was empty or missing commit_message.")
                append("Return all three required fields now; commit_message must start with a Conventional Commit type.")
            }
            val content = chatCompletion(profile, systemPrompt, retryPrompt)
            CommitSuggestionParser.parse(content)
        }
    }

    fun fetchModels(profile: LlmProfile): List<LlmModelOption> {
        require(profile.apiKey.isNotBlank()) { "API key required" }
        val endpoint = buildUrl(profile.effectiveBaseUrl, "models")
        val data = getJson(endpoint, profile.apiKey).getJSONArray("data")
        return buildList {
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id").trim()
                if (id.isNotBlank()) {
                    add(
                        LlmModelOption(
                            id = id,
                            name = model.optString("name").trim().takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.distinctBy { it.id }.sortedBy { it.id.lowercase(Locale.US) }
    }

    fun fetchOpenRouterProviders(profile: LlmProfile, modelId: String): List<OpenRouterProviderOption> {
        require(profile.kind == LlmProviderKind.OpenRouter) { "Only OpenRouter supports provider pins" }
        require(profile.apiKey.isNotBlank()) { "API key required" }
        val directory = fetchOpenRouterProviderDirectory(profile)
            .associateBy { normalizeProviderName(it.name) }
        val endpoint = buildOpenRouterModelEndpointsUrl(profile.effectiveBaseUrl, modelId)
        val endpoints = getJson(endpoint, profile.apiKey)
            .getJSONObject("data")
            .getJSONArray("endpoints")
        return buildList {
            for (index in 0 until endpoints.length()) {
                val item = endpoints.optJSONObject(index) ?: continue
                val status = item.optString("status").trim()
                if (status.isNotBlank() && status != "0") continue
                val providerName = item.optString("provider_name").trim()
                val provider = directory[normalizeProviderName(providerName)] ?: continue
                add(OpenRouterProviderOption(slug = provider.slug, name = provider.name))
            }
        }.distinctBy { it.slug }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun chatCompletion(
        profile: LlmProfile,
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val body = JSONObject()
            .put("model", profile.model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
            .put("temperature", 0.2)
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("response_format", CommitResponseFormat.forProvider(profile.kind))
            .applyReasoning(profile)
            .applyOpenRouterProvider(profile)

        val endpoint = buildUrl(profile.effectiveBaseUrl, "chat/completions")
        val response = postJson(endpoint, profile.apiKey, body, profile)
        val choices = response.optJSONArray("choices")
            ?: throw IOException("LLM response missing choices")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw IOException("LLM response missing message")
        val content = message.optString("content").trim()
        if (content.isBlank()) {
            throw InvalidCommitSuggestionException("LLM returned an empty message")
        }
        return content
    }

    private fun JSONObject.applyReasoning(profile: LlmProfile): JSONObject {
        val effort = profile.reasoningEffort
        when (profile.kind) {
            LlmProviderKind.OpenRouter -> {
                when (effort) {
                    ReasoningEffort.None -> put(
                        "reasoning",
                        JSONObject().put("effort", "none").put("exclude", true),
                    )
                    ReasoningEffort.Auto -> put(
                        "reasoning",
                        JSONObject().put("enabled", true).put("exclude", true),
                    )
                    else -> put(
                        "reasoning",
                        JSONObject()
                            .put("effort", effort.effortValue)
                            .put("exclude", true),
                    )
                }
            }
            LlmProviderKind.Cerebras, LlmProviderKind.Custom -> {
                // Only send reasoning_effort when explicitly requested; many models reject it.
                if (effort != ReasoningEffort.None && effort != ReasoningEffort.Auto) {
                    effort.effortValue?.let { put("reasoning_effort", it) }
                }
            }
        }
        return this
    }

    private fun JSONObject.applyOpenRouterProvider(profile: LlmProfile): JSONObject {
        if (profile.kind != LlmProviderKind.OpenRouter) return this
        val slug = profile.openRouterProviderSlug.trim()
        if (slug.isBlank()) return this
        put(
            "provider",
            JSONObject()
                .put("only", JSONArray().put(slug))
                .put("allow_fallbacks", false),
        )
        return this
    }

    private fun loadSystemPrompt(): String {
        return runCatching {
            appContext.assets.open(PROMPT_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault(FALLBACK_PROMPT)
    }

    private fun fetchOpenRouterProviderDirectory(profile: LlmProfile): List<OpenRouterProviderOption> {
        val endpoint = buildUrl(profile.effectiveBaseUrl, "providers")
        val data = getJson(endpoint, profile.apiKey).getJSONArray("data")
        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val slug = item.optString("slug").ifBlank { item.optString("id") }.trim()
                val name = item.optString("name").trim().ifBlank { slug }
                if (slug.isNotBlank()) add(OpenRouterProviderOption(slug = slug, name = name))
            }
        }
    }

    private fun getJson(endpoint: URL, apiKey: String): JSONObject {
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ReleaseShelf-Android")
        }
        return readJson(connection, "Request failed")
    }

    private fun postJson(
        endpoint: URL,
        apiKey: String,
        body: JSONObject,
        profile: LlmProfile,
    ): JSONObject {
        val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = CHAT_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ReleaseShelf-Android")
            if (profile.kind == LlmProviderKind.OpenRouter) {
                setRequestProperty("HTTP-Referer", "https://github.com/zainbits/release-shelf")
                setRequestProperty("X-Title", "ReleaseShelf")
            }
            setFixedLengthStreamingMode(payload.size)
        }
        connection.outputStream.use { it.write(payload) }
        return readJson(connection, "LLM call failed")
    }

    private fun readJson(connection: HttpURLConnection, failureMessage: String): JSONObject {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = text.take(400).ifBlank { connection.responseMessage.orEmpty() }
            throw IOException("$failureMessage (HTTP $code): $detail")
        }
        return JSONObject(text)
    }

    private fun buildUrl(baseUrl: String, path: String): URL {
        val normalized = baseUrl.trim().trimEnd('/')
        val endpoint = URL("$normalized/$path")
        if (endpoint.protocol != "https") {
            throw IOException("Use an HTTPS OpenAI-compatible endpoint")
        }
        return endpoint
    }

    private fun buildOpenRouterModelEndpointsUrl(baseUrl: String, modelId: String): URL {
        val parts = modelId.trim().split("/", limit = 2)
        val author = parts.getOrNull(0).orEmpty()
        val slug = parts.getOrNull(1).orEmpty()
        if (author.isBlank() || slug.isBlank()) {
            throw IOException("Use an OpenRouter model ID like openai/gpt-4o")
        }
        val encodedAuthor = URLEncoder.encode(author, "UTF-8").replace("+", "%20")
        val encodedSlug = URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
        return buildUrl(baseUrl, "models/$encodedAuthor/$encodedSlug/endpoints")
    }

    private fun normalizeProviderName(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")

    private fun truncate(value: String, max: Int): String =
        if (value.length <= max) value else value.take(max) + "\n…[truncated]"

    companion object {
        private const val PROMPT_ASSET = "prompts/commit_message.md"
        private const val MAX_STATUS_CHARS = 4_000
        private const val MAX_DIFF_CHARS = 24_000
        private const val MAX_OUTPUT_TOKENS = 700
        private const val TIMEOUT_MS = 30_000
        private const val CHAT_TIMEOUT_MS = 120_000

        private val FALLBACK_PROMPT = """
            You generate git commit metadata for personal Android apps.
            Return ONLY a small valid JSON object (no markdown, no diff echo):
            {"commit_message":"type: summary","bump":"patch|minor|major","bump_rationale":"why"}
            Use Conventional Commits (feat/fix/perf/refactor/chore), optional - bullets after a blank line.
            Keep commit_message under 1200 characters. Prefer patch when unsure. Never include secrets.
        """.trimIndent()
    }
}

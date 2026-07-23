package dev.zain.releaseshelf.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LlmProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = EncryptedStringStore(
        context = context,
        preferencesName = SECRET_PREFERENCES_NAME,
        keyAlias = KEY_ALIAS,
    )

    fun profiles(): List<LlmProfile> {
        val raw = preferences.getString(KEY_PROFILES, null)
        if (raw.isNullOrBlank()) return defaultProfiles().also { saveAll(it, activeId = it.first().id) }
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val obj = array.getJSONObject(index)
                    val id = obj.getString("id")
                    add(
                        LlmProfile(
                            id = id,
                            name = obj.optString("name").ifBlank { "Profile" },
                            kind = LlmProviderKind.fromId(obj.optString("kind")),
                            baseUrl = obj.optString("baseUrl"),
                            model = obj.optString("model"),
                            apiKey = secrets.get(secretKey(id)),
                            openRouterProviderSlug = obj.optString("openRouterProviderSlug"),
                            reasoningEffort = ReasoningEffort.fromId(obj.optString("reasoningEffort")),
                        ),
                    )
                }
            }
        }.getOrElse { defaultProfiles().also { saveAll(it, activeId = it.first().id) } }
    }

    fun activeProfileId(): String {
        val stored = preferences.getString(KEY_ACTIVE_ID, null)
        val all = profiles()
        if (stored != null && all.any { it.id == stored }) return stored
        return all.firstOrNull()?.id.orEmpty()
    }

    fun activeProfile(): LlmProfile? {
        val id = activeProfileId()
        return profiles().firstOrNull { it.id == id } ?: profiles().firstOrNull()
    }

    fun setActiveProfileId(id: String) {
        preferences.edit { putString(KEY_ACTIVE_ID, id) }
    }

    fun upsert(profile: LlmProfile): List<LlmProfile> {
        val normalized = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString() },
            name = profile.name.trim().ifBlank { profile.kind.label },
            baseUrl = profile.baseUrl.trim(),
            model = profile.model.trim(),
            openRouterProviderSlug = profile.openRouterProviderSlug.trim(),
        )
        val current = profiles().toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        if (index >= 0) current[index] = normalized else current += normalized
        saveAll(current, activeId = activeProfileId().ifBlank { normalized.id })
        secrets.set(secretKey(normalized.id), normalized.apiKey)
        return current
    }

    fun delete(id: String): List<LlmProfile> {
        val remaining = profiles().filterNot { it.id == id }.ifEmpty { defaultProfiles() }
        secrets.clear(secretKey(id))
        val active = activeProfileId().let { current ->
            if (remaining.any { it.id == current }) current else remaining.first().id
        }
        saveAll(remaining, activeId = active)
        return remaining
    }

    fun ensureDefaults() {
        if (preferences.getString(KEY_PROFILES, null).isNullOrBlank()) {
            val defaults = defaultProfiles()
            saveAll(defaults, activeId = defaults.first().id)
        }
    }

    private fun saveAll(profiles: List<LlmProfile>, activeId: String) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("kind", profile.kind.id)
                    .put("baseUrl", profile.baseUrl)
                    .put("model", profile.model)
                    .put("openRouterProviderSlug", profile.openRouterProviderSlug)
                    .put("reasoningEffort", profile.reasoningEffort.id),
            )
            // Keep encrypted keys aligned when rewriting the list.
            if (profile.apiKey.isNotBlank()) {
                secrets.set(secretKey(profile.id), profile.apiKey)
            }
        }
        preferences.edit {
            putString(KEY_PROFILES, array.toString())
            putString(KEY_ACTIVE_ID, activeId)
        }
    }

    private fun defaultProfiles(): List<LlmProfile> = listOf(
        LlmProfile.preset(LlmProviderKind.OpenRouter).copy(
            id = "preset-openrouter",
            name = "OpenRouter",
        ),
        LlmProfile.preset(LlmProviderKind.Cerebras).copy(
            id = "preset-cerebras",
            name = "Cerebras",
        ),
    )

    private fun secretKey(profileId: String): String = "llm_key_$profileId"

    companion object {
        const val PREFERENCES_NAME = "release_shelf_llm"
        const val SECRET_PREFERENCES_NAME = "release_shelf_llm_secret"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_ALIAS = "release_shelf_llm_secret"
    }
}

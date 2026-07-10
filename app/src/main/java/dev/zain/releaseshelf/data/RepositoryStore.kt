package dev.zain.releaseshelf.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class RepositoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("release_shelf_sources", Context.MODE_PRIVATE)

    fun get(): List<RepositoryId> {
        val raw = preferences.getString(KEY_REPOSITORIES, null)
        if (raw == null) return DEFAULT_REPOSITORIES
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(RepositoryId(item.getString("owner"), item.getString("name")))
                }
            }
        }.getOrDefault(DEFAULT_REPOSITORIES)
    }

    fun add(repository: RepositoryId): List<RepositoryId> =
        save((get() + repository).distinctBy { it.fullName.lowercase() })

    fun remove(repository: RepositoryId): List<RepositoryId> =
        save(get().filterNot { it.fullName.equals(repository.fullName, ignoreCase = true) })

    private fun save(repositories: List<RepositoryId>): List<RepositoryId> {
        val array = JSONArray()
        repositories.forEach { repository ->
            array.put(
                JSONObject()
                    .put("owner", repository.owner)
                    .put("name", repository.name),
            )
        }
        preferences.edit { putString(KEY_REPOSITORIES, array.toString()) }
        return repositories
    }

    private companion object {
        const val KEY_REPOSITORIES = "repositories"

        val DEFAULT_REPOSITORIES = listOf(
            RepositoryId("zainbits", "AgentRemote"),
            RepositoryId("zainbits", "Messages"),
            RepositoryId("zainbits", "NotiTriage"),
            RepositoryId("zainbits", "PrivateCallGuard"),
            RepositoryId("zainbits", "ReleaseShelf"),
            RepositoryId("zainbits", "ZnKeyboard"),
        )
    }
}

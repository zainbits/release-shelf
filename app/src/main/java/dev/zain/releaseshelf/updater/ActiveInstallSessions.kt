package dev.zain.releaseshelf.updater

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps active [android.content.pm.PackageInstaller] session IDs to shelf metadata
 * so session callbacks and status broadcasts can update the correct release card.
 */
object ActiveInstallSessions {
    data class Entry(
        val sessionId: Int,
        val repositoryFullName: String,
        val displayName: String,
        val packageName: String?,
    )

    private val bySession = ConcurrentHashMap<Int, Entry>()
    private val byRepository = ConcurrentHashMap<String, Int>()

    fun put(entry: Entry) {
        bySession[entry.sessionId] = entry
        byRepository[entry.repositoryFullName] = entry.sessionId
    }

    fun get(sessionId: Int): Entry? = bySession[sessionId]

    fun getByRepository(repositoryFullName: String): Entry? =
        byRepository[repositoryFullName]?.let { bySession[it] }

    fun getByPackageName(packageName: String): Entry? =
        bySession.values.firstOrNull { it.packageName == packageName }

    fun remove(sessionId: Int): Entry? {
        val entry = bySession.remove(sessionId) ?: return null
        byRepository.remove(entry.repositoryFullName, sessionId)
        return entry
    }

    fun removeByRepository(repositoryFullName: String): Entry? {
        val sessionId = byRepository.remove(repositoryFullName) ?: return null
        return bySession.remove(sessionId)
    }
}

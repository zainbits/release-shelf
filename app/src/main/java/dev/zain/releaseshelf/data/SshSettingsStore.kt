package dev.zain.releaseshelf.data

import android.content.Context
import androidx.core.content.edit

data class SshSettings(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val projectsRoot: String = SshSettingsStore.DEFAULT_PROJECTS_ROOT,
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank() && port in 1..65535
}

class SshSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = EncryptedStringStore(
        context = context,
        preferencesName = SECRET_PREFERENCES_NAME,
        keyAlias = KEY_ALIAS,
    )

    fun get(): SshSettings = SshSettings(
        host = preferences.getString(KEY_HOST, "").orEmpty(),
        port = preferences.getInt(KEY_PORT, 22).coerceIn(1, 65535),
        username = preferences.getString(KEY_USERNAME, "").orEmpty(),
        password = secrets.get(KEY_PASSWORD),
        projectsRoot = preferences.getString(KEY_PROJECTS_ROOT, DEFAULT_PROJECTS_ROOT)
            .orEmpty()
            .ifBlank { DEFAULT_PROJECTS_ROOT },
    )

    fun save(settings: SshSettings) {
        preferences.edit {
            putString(KEY_HOST, settings.host.trim())
            putInt(KEY_PORT, settings.port.coerceIn(1, 65535))
            putString(KEY_USERNAME, settings.username.trim())
            putString(
                KEY_PROJECTS_ROOT,
                settings.projectsRoot.trim().ifBlank { DEFAULT_PROJECTS_ROOT },
            )
        }
        secrets.set(KEY_PASSWORD, settings.password)
    }

    fun clearPassword() {
        secrets.clear(KEY_PASSWORD)
    }

    companion object {
        const val DEFAULT_PROJECTS_ROOT = "~/AndroidStudioProjects"
        const val PREFERENCES_NAME = "release_shelf_ssh"
        const val SECRET_PREFERENCES_NAME = "release_shelf_ssh_secret"

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PROJECTS_ROOT = "projects_root"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ALIAS = "release_shelf_ssh_secret"
    }
}

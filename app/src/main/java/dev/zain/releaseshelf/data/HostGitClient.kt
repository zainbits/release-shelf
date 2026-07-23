package dev.zain.releaseshelf.data

import android.util.Base64
import java.util.Locale

data class RepoWorkingTree(
    val repository: RepositoryId,
    val localPath: String,
    val isDirty: Boolean,
    val statusPorcelain: String,
    val error: String? = null,
)

data class RepoDiffBundle(
    val repository: RepositoryId,
    val localPath: String,
    val statusPorcelain: String,
    val diff: String,
)

data class HostPublishResult(
    val success: Boolean,
    val log: String,
    val message: String,
)

enum class VersionBump(val cli: String) {
    Patch("patch"),
    Minor("minor"),
    Major("major"),
    ;

    companion object {
        fun fromCli(value: String): VersionBump =
            entries.firstOrNull { it.cli.equals(value.trim(), ignoreCase = true) } ?: Patch
    }
}

class HostGitClient(
    private val ssh: SshExecClient = SshExecClient(),
) {
    fun testConnection(settings: SshSettings): Result<String> = runCatching {
        val result = ssh.exec(
            settings = settings,
            command = "zsh -lc 'printf OK; whoami; hostname; pwd'",
            timeoutSeconds = 20,
        )
        if (!result.isSuccess) {
            error(result.combinedOutput.ifBlank { "SSH command failed (${result.exitStatus})" })
        }
        result.combinedOutput.ifBlank { "Connected" }
    }

    fun scanWorkingTrees(
        settings: SshSettings,
        repositories: List<RepositoryId>,
    ): List<RepoWorkingTree> {
        if (!settings.isConfigured || repositories.isEmpty()) {
            return repositories.map {
                RepoWorkingTree(
                    repository = it,
                    localPath = localPath(settings, it),
                    isDirty = false,
                    statusPorcelain = "",
                    error = if (!settings.isConfigured) "SSH not configured" else null,
                )
            }
        }

        val script = buildString {
            appendLine("set -euo pipefail")
            appendLine("ROOT=${shellSingleQuote(settings.projectsRoot.trim().ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT })}")
            appendLine("ROOT=\"\${ROOT/#\\~/\$HOME}\"")
            for (repo in repositories) {
                val name = repo.name
                appendLine("REPO_PATH=\"\$ROOT\"/${shellSingleQuote(name)}")
                appendLine("echo 'RS_REPO_BEGIN ${repo.fullName}'")
                appendLine("if [ ! -d \"\$REPO_PATH/.git\" ]; then")
                appendLine("  echo 'RS_ERROR missing_git'")
                appendLine("else")
                appendLine("  STATUS=\$(git -C \"\$REPO_PATH\" status --porcelain 2>&1) || true")
                appendLine("  if [ -n \"\$STATUS\" ]; then")
                appendLine("    echo 'RS_DIRTY'")
                appendLine("    printf '%s\\n' \"\$STATUS\"")
                appendLine("  else")
                appendLine("    echo 'RS_CLEAN'")
                appendLine("  fi")
                appendLine("fi")
                appendLine("echo 'RS_REPO_END'")
            }
        }

        val result = ssh.exec(
            settings = settings,
            command = "zsh -lc ${shellSingleQuote(script)}",
            timeoutSeconds = 60,
        )
        if (!result.isSuccess && result.stdout.isBlank()) {
            return repositories.map {
                RepoWorkingTree(
                    repository = it,
                    localPath = localPath(settings, it),
                    isDirty = false,
                    statusPorcelain = "",
                    error = result.combinedOutput.ifBlank { "SSH scan failed" },
                )
            }
        }
        return parseScanOutput(repositories, settings, result.stdout)
    }

    fun fetchDiff(settings: SshSettings, repository: RepositoryId): RepoDiffBundle {
        val path = localPath(settings, repository)
        val script = """
            set -euo pipefail
            ROOT=${shellSingleQuote(settings.projectsRoot.trim().ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT })}
            ROOT="${'$'}{ROOT/#\~/${'$'}HOME}"
            REPO_PATH="${'$'}ROOT"/${shellSingleQuote(repository.name)}
            test -d "${'$'}REPO_PATH/.git"
            echo 'RS_STATUS_BEGIN'
            git -C "${'$'}REPO_PATH" status --porcelain
            echo 'RS_STATUS_END'
            echo 'RS_STAT_BEGIN'
            git -C "${'$'}REPO_PATH" diff HEAD --stat
            echo 'RS_STAT_END'
            echo 'RS_DIFF_BEGIN'
            # Cap patch size on the host so phones/models are not flooded.
            git -C "${'$'}REPO_PATH" diff HEAD | head -c 40000
            echo
            echo 'RS_DIFF_END'
            echo 'RS_UNTRACKED_BEGIN'
            git -C "${'$'}REPO_PATH" ls-files --others --exclude-standard | head -n 40
            echo 'RS_UNTRACKED_END'
        """.trimIndent()

        val result = ssh.exec(
            settings = settings,
            command = "zsh -lc ${shellSingleQuote(script)}",
            timeoutSeconds = 90,
        )
        if (!result.isSuccess && !result.stdout.contains("RS_STATUS_BEGIN")) {
            error(result.combinedOutput.ifBlank { "Could not fetch git diff" })
        }
        val status = section(result.stdout, "RS_STATUS_BEGIN", "RS_STATUS_END")
        val stat = section(result.stdout, "RS_STAT_BEGIN", "RS_STAT_END")
        val diff = section(result.stdout, "RS_DIFF_BEGIN", "RS_DIFF_END")
        val untracked = section(result.stdout, "RS_UNTRACKED_BEGIN", "RS_UNTRACKED_END")
        val combinedDiff = buildString {
            if (stat.isNotBlank()) {
                append("Diff stat:\n")
                append(stat.trim())
            }
            if (diff.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Patch excerpt:\n")
                append(diff.trim())
            }
            if (untracked.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Untracked files:\n")
                append(untracked.trim())
            }
        }
        if (status.isBlank() && combinedDiff.isBlank()) {
            error("Working tree is clean")
        }
        return RepoDiffBundle(
            repository = repository,
            localPath = path,
            statusPorcelain = status,
            diff = combinedDiff,
        )
    }

    fun publish(
        settings: SshSettings,
        repository: RepositoryId,
        commitMessage: String,
        bump: VersionBump,
    ): HostPublishResult {
        val message = commitMessage.trim()
        require(message.isNotBlank()) { "Commit message is blank" }
        val encoded = Base64.encodeToString(message.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val script = """
            set -euo pipefail
            export ANDROID_HOME="${'$'}{ANDROID_HOME:-${'$'}HOME/Android/Sdk}"
            export ANDROID_SDK_ROOT="${'$'}{ANDROID_SDK_ROOT:-${'$'}ANDROID_HOME}"
            export PATH="${'$'}HOME/.local/bin:/home/linuxbrew/.linuxbrew/bin:/usr/local/bin:${'$'}PATH"
            if [ -z "${'$'}{JAVA_HOME:-}" ] || [ ! -x "${'$'}JAVA_HOME/bin/java" ]; then
              for JAVA_CANDIDATE in \
                "${'$'}HOME/.local/share/jdks/temurin-21" \
                "${'$'}HOME/.local/share/jdks/temurin-17" \
                "${'$'}HOME/.jdks/temurin-21" \
                "${'$'}HOME/.jdks/temurin-17" \
                "${'$'}HOME/android-studio/jbr" \
                "/opt/android-studio/jbr"; do
                if [ -x "${'$'}JAVA_CANDIDATE/bin/java" ]; then
                  export JAVA_HOME="${'$'}JAVA_CANDIDATE"
                  break
                fi
              done
            fi
            if [ -n "${'$'}{JAVA_HOME:-}" ] && [ -x "${'$'}JAVA_HOME/bin/java" ]; then
              export PATH="${'$'}JAVA_HOME/bin:${'$'}PATH"
            elif command -v java >/dev/null 2>&1; then
              JAVA_BIN="${'$'}(command -v java)"
              JAVA_BIN="${'$'}(readlink -f "${'$'}JAVA_BIN" 2>/dev/null || printf '%s' "${'$'}JAVA_BIN")"
              export JAVA_HOME="${'$'}{JAVA_BIN%/bin/java}"
            else
              echo "RS_JAVA_MISSING: Java 17 or newer was not found on the build host" >&2
              exit 3
            fi
            command -v androidrun >/dev/null 2>&1 || {
              echo "RS_ANDROIDRUN_MISSING: androidrun was not found on the build host" >&2
              exit 3
            }
            command -v gh >/dev/null 2>&1 || {
              echo "RS_GH_MISSING: GitHub CLI was not found on the build host" >&2
              exit 3
            }
            gh auth status >/dev/null 2>&1 || {
              echo "RS_GH_AUTH_MISSING: GitHub CLI is not authenticated on the build host" >&2
              exit 3
            }
            ROOT=${shellSingleQuote(settings.projectsRoot.trim().ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT })}
            ROOT="${'$'}{ROOT/#\~/${'$'}HOME}"
            REPO_PATH="${'$'}ROOT"/${shellSingleQuote(repository.name)}
            test -d "${'$'}REPO_PATH/.git"
            cd "${'$'}REPO_PATH"
            if ! ./gradlew :app:assembleRelease; then
              echo "RS_PREFLIGHT_BUILD_FAILED: Release build failed before any commit or push" >&2
              exit 3
            fi
            MSG_FILE="${'$'}(mktemp)"
            trap 'rm -f "${'$'}MSG_FILE"' EXIT
            printf '%s' ${shellSingleQuote(encoded)} | base64 -d > "${'$'}MSG_FILE"
            if git status --porcelain | grep -q .; then
              git add -A
              git commit -F "${'$'}MSG_FILE"
              NEW_NAME="${'$'}(androidrun --bump ${bump.cli} | tail -n 1 | tr -d '\r')"
              if [ -n "${'$'}NEW_NAME" ]; then
                git add -A
                git commit -m "chore: bump version to ${'$'}NEW_NAME"
              else
                git add -A
                git commit -m "chore: bump version (${bump.cli})"
              fi
            else
              echo "RS_PUBLISH_RESUME: Working tree is clean; retrying the pending release"
            fi
            git push
            androidrun --publish
            echo 'RS_PUBLISH_OK'
        """.trimIndent()

        val result = ssh.exec(
            settings = settings,
            command = "zsh -lc ${shellSingleQuote(script)}",
            timeoutSeconds = SshExecClient.PUBLISH_TIMEOUT_SECONDS,
        )
        val log = result.combinedOutput
        val success = result.isSuccess && log.contains("RS_PUBLISH_OK")
        return HostPublishResult(
            success = success,
            log = log,
            message = when {
                success -> "Published ${repository.name}"
                log.contains("RS_JAVA_MISSING") -> "Java 17 or newer is not configured on the build host"
                log.contains("RS_ANDROIDRUN_MISSING") -> "androidrun is not installed on the build host"
                log.contains("RS_GH_MISSING") -> "GitHub CLI is not installed on the build host"
                log.contains("RS_GH_AUTH_MISSING") -> "GitHub CLI is not authenticated on the build host"
                log.contains("RS_PREFLIGHT_BUILD_FAILED") -> "Release build failed before any commit or push"
                else -> log.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .lastOrNull()
                    ?.take(240)
                    ?: "Publish failed"
            },
        )
    }

    private fun parseScanOutput(
        repositories: List<RepositoryId>,
        settings: SshSettings,
        stdout: String,
    ): List<RepoWorkingTree> {
        val byName = linkedMapOf<String, RepoWorkingTree>()
        var current: RepositoryId? = null
        var dirty = false
        var error: String? = null
        val statusLines = mutableListOf<String>()

        fun flush() {
            val repo = current ?: return
            byName[repo.fullName.lowercase(Locale.US)] = RepoWorkingTree(
                repository = repo,
                localPath = localPath(settings, repo),
                isDirty = dirty && error == null,
                statusPorcelain = statusLines.joinToString("\n"),
                error = error,
            )
            current = null
            dirty = false
            error = null
            statusLines.clear()
        }

        for (raw in stdout.lineSequence()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("RS_REPO_BEGIN ") -> {
                    flush()
                    val fullName = line.removePrefix("RS_REPO_BEGIN ").trim()
                    current = RepositoryId.parse(fullName)
                        ?: repositories.firstOrNull {
                            it.fullName.equals(fullName, ignoreCase = true)
                        }
                }
                line == "RS_REPO_END" -> flush()
                line == "RS_DIRTY" -> dirty = true
                line == "RS_CLEAN" -> dirty = false
                line.startsWith("RS_ERROR") -> {
                    error = when {
                        line.contains("missing_git") -> "No git repo at host path"
                        else -> line.removePrefix("RS_ERROR").trim().ifBlank { "Scan error" }
                    }
                }
                current != null && (dirty || error != null) && !line.startsWith("RS_") -> {
                    if (statusLines.size < 40) statusLines += line
                }
            }
        }
        flush()

        return repositories.map { repo ->
            byName[repo.fullName.lowercase(Locale.US)]
                ?: RepoWorkingTree(
                    repository = repo,
                    localPath = localPath(settings, repo),
                    isDirty = false,
                    statusPorcelain = "",
                    error = "Missing from SSH scan",
                )
        }
    }

    private fun localPath(settings: SshSettings, repository: RepositoryId): String {
        val root = settings.projectsRoot.trim().ifBlank { SshSettingsStore.DEFAULT_PROJECTS_ROOT }
            .trimEnd('/')
        return "$root/${repository.name}"
    }

    private fun section(text: String, begin: String, end: String): String {
        val start = text.indexOf(begin)
        if (start < 0) return ""
        val from = start + begin.length
        val stop = text.indexOf(end, from)
        val body = if (stop < 0) text.substring(from) else text.substring(from, stop)
        return body.trim('\n', '\r')
    }

    companion object {
        fun shellSingleQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

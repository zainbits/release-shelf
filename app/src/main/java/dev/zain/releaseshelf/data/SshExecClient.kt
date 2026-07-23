package dev.zain.releaseshelf.data

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.util.concurrent.TimeUnit

data class SshCommandResult(
    val exitStatus: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitStatus == 0
    val combinedOutput: String
        get() = listOf(stdout, stderr)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
}

class SshExecClient {
    fun exec(
        settings: SshSettings,
        command: String,
        timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    ): SshCommandResult {
        require(settings.isConfigured) { "SSH host, username, and password are required" }
        val client = SSHClient()
        // Personal build host on LAN/Tailscale; first-version password auth without TOFU UI.
        client.addHostKeyVerifier(PromiscuousVerifier())
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.timeout = timeoutSeconds * 1000
        try {
            client.connect(settings.host.trim(), settings.port)
            client.authPassword(settings.username.trim(), settings.password)
            client.startSession().use { session ->
                val cmd: Session.Command = session.exec(command)
                val stdout = String(IOUtils.readFully(cmd.inputStream).toByteArray(), Charsets.UTF_8)
                val stderr = String(IOUtils.readFully(cmd.errorStream).toByteArray(), Charsets.UTF_8)
                cmd.join(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                val status = cmd.exitStatus ?: -1
                return SshCommandResult(exitStatus = status, stdout = stdout, stderr = stderr)
            }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 45
        const val PUBLISH_TIMEOUT_SECONDS = 60 * 45
        private const val CONNECT_TIMEOUT_MS = 15_000
    }
}

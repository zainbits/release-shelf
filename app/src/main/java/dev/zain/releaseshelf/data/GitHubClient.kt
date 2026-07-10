package dev.zain.releaseshelf.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

class GitHubClient {
    fun latestRelease(repository: RepositoryId, token: String): ReleaseInfo {
        val releaseJson = getJson(
            "https://api.github.com/repos/${repository.owner}/${repository.name}/releases/latest",
            token,
        )
        val assetsJson = releaseJson.getJSONArray("assets")
        val assets = buildList {
            repeat(assetsJson.length()) { index ->
                val asset = assetsJson.getJSONObject(index)
                add(
                    ReleaseAsset(
                        name = asset.getString("name"),
                        apiUrl = asset.getString("url"),
                        browserUrl = asset.getString("browser_download_url"),
                        sizeBytes = asset.optLong("size", 0L),
                    ),
                )
            }
        }

        val metadataAsset = assets.firstOrNull {
            it.name == METADATA_ASSET_NAME || it.name.endsWith("-androidrun.json")
        }
        val metadata = metadataAsset?.let { asset ->
            runCatching { JSONObject(getText(asset.apiUrl, token, OCTET_STREAM)) }.getOrNull()
        }
        val body = releaseJson.optString("body")
        val apkName = metadata?.optString("apkAsset")?.takeIf { it.isNotBlank() }
        val apk = assets.firstOrNull { it.name == apkName }
            ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: error("Latest release has no APK asset")

        val versionName = metadata?.optString("versionName")?.takeIf { it.isNotBlank() }
            ?: bodyField(body, "versionName")
            ?: releaseJson.getString("tag_name").removePrefix("v").substringBeforeLast('-', missingDelimiterValue = releaseJson.getString("tag_name").removePrefix("v"))
        val versionCode = metadata?.optLongOrNull("versionCode")
            ?: bodyField(body, "versionCode")?.toLongOrNull()
        val packageName = metadata?.optString("packageName")?.takeIf { it.isNotBlank() }
            ?: bodyField(body, "package")

        return ReleaseInfo(
            repository = repository,
            displayName = metadata?.optString("displayName")?.takeIf { it.isNotBlank() } ?: repository.name,
            tag = releaseJson.getString("tag_name"),
            versionName = versionName,
            versionCode = versionCode,
            packageName = packageName,
            minSdk = metadata?.optIntOrNull("minSdk"),
            publishedAt = releaseJson.optString("published_at"),
            releaseUrl = releaseJson.optString("html_url"),
            notes = body,
            apk = apk,
            sha256 = metadata?.optString("sha256")?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) },
        )
    }

    fun download(
        asset: ReleaseAsset,
        token: String,
        destination: File,
        onProgress: (Float) -> Unit,
    ): String {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        partial.delete()
        val connection = open(asset.apiUrl, token, OCTET_STREAM)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                        if (asset.sizeBytes > 0) {
                            onProgress((total.toFloat() / asset.sizeBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (!partial.renameTo(destination)) {
            partial.copyTo(destination, overwrite = true)
            partial.delete()
        }
        onProgress(1f)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getJson(url: String, token: String): JSONObject = JSONObject(getText(url, token, GITHUB_JSON))

    private fun getText(url: String, token: String, accept: String): String {
        val connection = open(url, token, accept)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(initialUrl: String, token: String, accept: String): HttpURLConnection {
        var current = initialUrl
        repeat(6) {
            val url = URI(current).toURL()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "ReleaseShelf-Android")
                if (token.isNotBlank() && url.host == "api.github.com") {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw GitHubException(status, "GitHub returned an invalid redirect")
                connection.disconnect()
                current = URL(url, location).toString()
            } else if (status in 200..299) {
                return connection
            } else {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.let { runCatching { JSONObject(it).optString("message") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                    ?: "GitHub request failed"
                connection.disconnect()
                throw GitHubException(status, message)
            }
        }
        throw GitHubException(310, "Too many redirects")
    }

    private fun bodyField(body: String, name: String): String? {
        val escapedName = Regex.escape(name)
        return Regex("(?m)^- \\*\\*$escapedName:\\*\\*\\s+`?([^`\\r\\n]+)`?\\s*$")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) optLong(name) else null

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private companion object {
        const val METADATA_ASSET_NAME = "androidrun-release.json"
        const val GITHUB_JSON = "application/vnd.github+json"
        const val OCTET_STREAM = "application/octet-stream"
    }
}

class GitHubException(val statusCode: Int, override val message: String) : Exception(message)

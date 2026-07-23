package dev.zain.releaseshelf.data

import org.json.JSONObject
import java.io.IOException

/**
 * Parses LLM commit-suggestion payloads. Models often return nearly-valid JSON
 * with unescaped newlines, trailing garbage, or truncated strings — especially
 * after large diffs — so strict JSONObject parsing is not enough alone.
 */
object CommitSuggestionParser {
    fun parse(content: String): CommitSuggestion {
        val cleaned = stripFences(content).trim()
        if (cleaned.isBlank()) throw IOException("LLM returned an empty message")

        parseStrictJson(cleaned)?.let { return it }
        parseLenientFields(cleaned)?.let { return it }
        parseHeuristic(cleaned)?.let { return it }

        throw IOException(
            "Could not parse LLM commit suggestion. " +
                "Try again, switch model, or write the message manually. " +
                "Preview: ${cleaned.take(180).replace('\n', ' ')}",
        )
    }

    private fun parseStrictJson(content: String): CommitSuggestion? {
        val candidates = jsonCandidates(content)
        for (candidate in candidates) {
            val suggestion = runCatching {
                val json = JSONObject(candidate)
                val message = json.optString("commit_message")
                    .ifBlank { json.optString("message") }
                    .trim()
                if (message.isBlank()) return@runCatching null
                val bump = VersionBump.fromCli(json.optString("bump").ifBlank { "patch" })
                val rationale = json.optString("bump_rationale").trim()
                CommitSuggestion(
                    commitMessage = sanitizeMessage(message),
                    bump = bump,
                    bumpRationale = rationale.take(500),
                )
            }.getOrNull()
            if (suggestion != null) return suggestion
        }
        return null
    }

    private fun parseLenientFields(content: String): CommitSuggestion? {
        val message = extractJsonStringField(content, "commit_message")
            ?: extractJsonStringField(content, "message")
            ?: return null
        val bump = extractJsonStringField(content, "bump")
            ?.let(VersionBump::fromCli)
            ?: VersionBump.Patch
        val rationale = extractJsonStringField(content, "bump_rationale").orEmpty()
        return CommitSuggestion(
            commitMessage = sanitizeMessage(message),
            bump = bump,
            bumpRationale = rationale.take(500),
        )
    }

    private fun parseHeuristic(content: String): CommitSuggestion? {
        // Recover Conventional Commit text even when JSON is destroyed.
        val messageMatch = CONVENTIONAL_BLOCK.find(content) ?: return null
        val message = sanitizeMessage(messageMatch.value.trim())
        if (message.isBlank()) return null
        val bump = extractJsonStringField(content, "bump")
            ?.let(VersionBump::fromCli)
            ?: when {
                message.startsWith("feat") -> VersionBump.Minor
                message.startsWith("fix") || message.startsWith("perf") -> VersionBump.Patch
                else -> VersionBump.Patch
            }
        val rationale = extractJsonStringField(content, "bump_rationale").orEmpty()
        return CommitSuggestion(
            commitMessage = message,
            bump = bump,
            bumpRationale = rationale.take(500).ifBlank { "Recovered from partial LLM output" },
        )
    }

    private fun jsonCandidates(content: String): List<String> {
        val trimmed = content.trim()
        val out = linkedSetOf<String>()
        if (trimmed.startsWith("{")) out += trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            out += trimmed.substring(start, end + 1)
        }
        // First balanced object (stops before trailing garbage).
        extractBalancedObject(trimmed)?.let { out += it }
        return out.toList()
    }

    private fun extractBalancedObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Reads a JSON string field even when the value contains raw newlines
     * or the overall document is truncated / garbage-tailed.
     */
    internal fun extractJsonStringField(content: String, field: String): String? {
        val key = Regex(
            pattern = """"$field"\s*:\s*"""",
            option = RegexOption.IGNORE_CASE,
        )
        val match = key.find(content) ?: return null
        val start = match.range.last + 1
        if (start >= content.length) return null
        val out = StringBuilder()
        var i = start
        while (i < content.length) {
            val ch = content[i]
            when {
                ch == '\\' && i + 1 < content.length -> {
                    val next = content[i + 1]
                    when (next) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        'u' -> {
                            val hex = content.substring(i + 2, (i + 6).coerceAtMost(content.length))
                            val code = hex.toIntOrNull(16)
                            if (code != null && hex.length == 4) {
                                out.append(code.toChar())
                                i += 5
                            } else {
                                out.append(next)
                                i += 1
                            }
                        }
                        else -> out.append(next)
                    }
                    i += 2
                }
                ch == '"' -> return out.toString().trim().ifBlank { null }
                // Some models emit raw newlines inside the string without escaping.
                ch == '\n' || ch == '\r' -> {
                    out.append(ch)
                    i++
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
            // Guard against runaway garbage after a missing closer.
            if (out.length > MAX_MESSAGE_CHARS) {
                return out.toString().trim().take(MAX_MESSAGE_CHARS)
            }
        }
        // Truncated string — still useful if we captured a subject line.
        val partial = out.toString().trim()
        return partial.takeIf { it.isNotBlank() && CONVENTIONAL_LINE.containsMatchIn(it) }
    }

    private fun sanitizeMessage(message: String): String {
        val normalized = message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
            .trim('"', '\'', '`', '@', '{', '}')
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
        // Drop trailing garbage lines full of punctuation.
        val useful = normalized.lineSequence()
            .takeWhile { line ->
                line.isBlank() ||
                    CONVENTIONAL_LINE.containsMatchIn(line) ||
                    line.startsWith("- ") ||
                    line.startsWith("* ") ||
                    (line.length < 120 && line.any { it.isLetterOrDigit() })
            }
            .joinToString("\n")
            .trim()
        return useful.take(MAX_MESSAGE_CHARS)
    }

    private fun stripFences(content: String): String {
        val trimmed = content.trim()
        val fenced = Regex(
            pattern = """^```(?:json)?\s*([\s\S]*?)\s*```$""",
            option = RegexOption.IGNORE_CASE,
        ).find(trimmed)
        return fenced?.groupValues?.getOrNull(1)?.trim() ?: trimmed
    }

    private val CONVENTIONAL_LINE = Regex(
        """^(feat|fix|perf|refactor|chore|docs|test|build|ci)(\([^)]+\))?!?:\s+\S+""",
        RegexOption.IGNORE_CASE,
    )
    private val CONVENTIONAL_BLOCK = Regex(
        """(?m)^(feat|fix|perf|refactor|chore|docs|test|build|ci)(\([^)]+\))?!?:\s+.+(?:\n(?:-\s+.+)*)?""",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_MESSAGE_CHARS = 4_000
}

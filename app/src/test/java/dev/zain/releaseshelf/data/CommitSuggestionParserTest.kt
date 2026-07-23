package dev.zain.releaseshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitSuggestionParserTest {
    @Test
    fun parsesStrictJson() {
        val suggestion = CommitSuggestionParser.parse(
            """
            {
              "commit_message": "feat: add error panel\n\n- Show errors in review",
              "bump": "minor",
              "bump_rationale": "new capability"
            }
            """.trimIndent(),
        )
        assertEquals(VersionBump.Minor, suggestion.bump)
        assertTrue(suggestion.commitMessage.startsWith("feat: add error panel"))
        assertEquals("new capability", suggestion.bumpRationale)
    }

    @Test
    fun recoversFromUnescapedNewlinesAndTrailingGarbage() {
        val suggestion = CommitSuggestionParser.parse(
            """
            {"commit_message": "feat: add error state to agent review panel
- Replace toast notifications
- Add clipboard copy",
            "bump": "minor",
            "bump_rationale": "new UX"}
            @{}>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
            """.trimIndent(),
        )
        assertEquals(VersionBump.Minor, suggestion.bump)
        assertTrue(suggestion.commitMessage.contains("feat: add error state"))
        assertTrue(suggestion.commitMessage.contains("Replace toast"))
    }

    @Test
    fun recoversConventionalCommitWithoutValidJson() {
        val suggestion = CommitSuggestionParser.parse(
            """
            noise {"],[ " garbage
            feat: show install progress

            - Track installProgress on cards
            "bump": "patch"
            @{}>>>>>>>>
            """.trimIndent(),
        )
        assertTrue(suggestion.commitMessage.startsWith("feat: show install progress"))
        assertEquals(VersionBump.Patch, suggestion.bump)
    }

    @Test
    fun extractJsonStringField_handlesEscapes() {
        val value = CommitSuggestionParser.extractJsonStringField(
            """{"commit_message":"fix: keep frame\n\n- during seek","bump":"patch"}""",
            "commit_message",
        )
        assertEquals("fix: keep frame\n\n- during seek", value)
    }
}

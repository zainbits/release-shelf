package dev.zain.releaseshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostGitClientTest {
    @Test
    fun shellSingleQuote_escapesEmbeddedQuotes() {
        assertEquals("'hello'", HostGitClient.shellSingleQuote("hello"))
        assertEquals("'it'\"'\"'s fine'", HostGitClient.shellSingleQuote("it's fine"))
    }

    @Test
    fun versionBump_parsesCliValues() {
        assertEquals(VersionBump.Patch, VersionBump.fromCli("patch"))
        assertEquals(VersionBump.Minor, VersionBump.fromCli("MINOR"))
        assertEquals(VersionBump.Major, VersionBump.fromCli("major"))
        assertEquals(VersionBump.Patch, VersionBump.fromCli("unknown"))
    }

    @Test
    fun llmProfile_openRouterUsesFixedBaseUrl() {
        val profile = LlmProfile(
            id = "1",
            name = "OR",
            kind = LlmProviderKind.OpenRouter,
            baseUrl = "https://example.com/v1",
            model = "openai/gpt-4o-mini",
            apiKey = "sk-test",
        )
        assertEquals(LlmProviderKind.OpenRouter.defaultBaseUrl, profile.effectiveBaseUrl)
        assertTrue(profile.isConfigured)
        assertFalse(profile.copy(apiKey = "").isConfigured)
    }

    @Test
    fun reasoningEffort_defaultsToNone() {
        assertEquals(ReasoningEffort.None, ReasoningEffort.fromId("missing"))
        assertEquals(null, ReasoningEffort.None.effortValue)
        assertEquals("high", ReasoningEffort.High.effortValue)
    }
}

package dev.zain.releaseshelf.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitResponseFormatTest {
    @Test
    fun usesStrictRequiredSchemaForCerebras() {
        val format = CommitResponseFormat.forProvider(LlmProviderKind.Cerebras)
        val structuredOutput = format.getJSONObject("json_schema")
        val schema = structuredOutput.getJSONObject("schema")
        val required = schema.getJSONArray("required")
        val requiredFields = buildList {
            for (index in 0 until required.length()) {
                add(required.getString(index))
            }
        }

        assertEquals("json_schema", format.getString("type"))
        assertEquals("commit_suggestion", structuredOutput.getString("name"))
        assertTrue(structuredOutput.getBoolean("strict"))
        assertTrue(requiredFields.containsAll(listOf("commit_message", "bump", "bump_rationale")))
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test
    fun keepsPortableJsonModeForOtherProviders() {
        listOf(LlmProviderKind.OpenRouter, LlmProviderKind.Custom).forEach { provider ->
            val format = CommitResponseFormat.forProvider(provider)
            assertEquals("json_object", format.getString("type"))
            assertFalse(format.has("json_schema"))
        }
    }
}

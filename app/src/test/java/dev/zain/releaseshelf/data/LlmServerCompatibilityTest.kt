package dev.zain.releaseshelf.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmServerCompatibilityTest {
    @Test
    fun detectsLlamaCppFromModelOwnership() {
        val response = JSONObject(
            """
            {
              "data": [
                {
                  "id": "local-model",
                  "object": "model",
                  "owned_by": "llamacpp"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(OpenAiServerFlavor.LlamaCpp, LlmServerCompatibility.detect(response))
    }

    @Test
    fun usesRequiredSchemaForLlamaCpp() {
        val format = LlmServerCompatibility.responseFormat(OpenAiServerFlavor.LlamaCpp)
        val schema = format.getJSONObject("schema")
        val required = schema.getJSONArray("required")
        val requiredFields = buildList {
            for (index in 0 until required.length()) {
                add(required.getString(index))
            }
        }

        assertEquals("json_schema", format.getString("type"))
        assertTrue(requiredFields.containsAll(listOf("commit_message", "bump", "bump_rationale")))
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test
    fun disablesLlamaCppThinkingWhenReasoningIsNone() {
        assertTrue(
            LlmServerCompatibility.shouldDisableThinking(
                OpenAiServerFlavor.LlamaCpp,
                ReasoningEffort.None,
            ),
        )
        assertFalse(
            LlmServerCompatibility.shouldDisableThinking(
                OpenAiServerFlavor.LlamaCpp,
                ReasoningEffort.Auto,
            ),
        )
        assertFalse(
            LlmServerCompatibility.shouldDisableThinking(
                OpenAiServerFlavor.Generic,
                ReasoningEffort.None,
            ),
        )
    }

    @Test
    fun keepsPortableJsonModeForOtherProviders() {
        val format = LlmServerCompatibility.responseFormat(OpenAiServerFlavor.Generic)

        assertEquals("json_object", format.getString("type"))
        assertFalse(format.has("schema"))
    }
}

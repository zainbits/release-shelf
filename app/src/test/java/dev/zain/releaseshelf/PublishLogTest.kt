package dev.zain.releaseshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublishLogTest {
    @Test
    fun keepsOnlyTheLastTwelveNonBlankLines() {
        val log = (1..15).joinToString("\n") { "line $it" }

        val compact = compactPublishLog(log)

        assertFalse(compact.contains("line 3\n"))
        assertTrue(compact.startsWith("line 4\n"))
        assertTrue(compact.endsWith("line 15"))
        assertEquals(12, compact.lines().size)
    }

    @Test
    fun capsVeryLongFailureOutput() {
        val compact = compactPublishLog("x".repeat(1_500))

        assertEquals(1_000, compact.length)
    }
}

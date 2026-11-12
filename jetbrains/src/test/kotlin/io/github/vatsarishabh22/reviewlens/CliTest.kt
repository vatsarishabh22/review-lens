package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** A command with a time limit is stopped when it passes it, and says so; within it, it answers as any command. */
class CliTest {
    private val dir = Files.createTempDirectory("review-lens-cli")

    @Test
    fun `a command past its time limit is stopped and throws`() {
        val start = System.currentTimeMillis()
        val e = assertThrows(CliException::class.java) { runCli(dir, "sleep", "10", timeoutMs = 1_000) }
        assertEquals("sleep didn't answer in 1 s", e.message)
        assertEquals("the reason survives a caller that rewords from stderr", e.message, e.stderr)
        assertTrue("stopped at the limit, not after the command", System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun `a command within its time limit answers`() {
        assertEquals("hi", runCli(dir, "echo", "hi", timeoutMs = 5_000))
    }
}

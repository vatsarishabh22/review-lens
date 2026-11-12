package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** A command with a time limit is stopped when it passes it, and says so; within it, it answers as any command. */
class CliTest {
    private val dir = Files.createTempDirectory("review-lens-cli")
    // a command every OS has: the JVM running the tests, printing its argument or sleeping (echo and sleep aren't on Windows)
    private val jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString()
    private val program = dir.resolve("Say.java").also {
        Files.writeString(it, "class Say { public static void main(String[] a) throws Exception { if (a[0].equals(\"sleep\")) Thread.sleep(10_000); else System.out.print(a[0]); } }")
    }.toString()

    @Test
    fun `a command past its time limit is stopped and throws`() {
        val start = System.currentTimeMillis()
        val e = assertThrows(CliException::class.java) { runCli(dir, jvm, program, "sleep", timeoutMs = 1_000) }
        assertEquals("$jvm didn't answer in 1 s", e.message)
        assertEquals("the reason survives a caller that rewords from stderr", e.message, e.stderr)
        assertTrue("stopped at the limit, not after the command", System.currentTimeMillis() - start < 5_000)
    }

    @Test
    fun `a command within its time limit answers`() {
        assertEquals("hi", runCli(dir, jvm, program, "hi", timeoutMs = 20_000))
    }

    @Test
    fun `asking all at once keeps every answer, leaves out a refusal or a null, and runs the calls side by side`() {
        val bothRunning = java.util.concurrent.CountDownLatch(2)
        val answers = askAll(listOf(1, 2, 3, 4)) { n ->
            when (n) {
                2 -> throw CliException("no")
                4 -> null
                else -> { bothRunning.countDown(); check(bothRunning.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "one call waited on the other" }; n * 10 }
            }
        }
        assertEquals(mapOf(1 to 10, 3 to 30), answers)
    }
}

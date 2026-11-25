package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** GitHub's calls stay bounded; which files may reach a host's package is LayersTest's. */
class HostBoundaryTest {
    private val shared = Path.of("src/main/kotlin/io/github/vatsarishabh22/reviewlens")

    @Test
    fun `every read from GitHub goes through the one helper that bounds it`() {
        val calls = Files.readAllLines(shared.resolve("host/github/GhClient.kt")).filter { "runCli(" in it }.map { it.trim() }
        // the bounded read helper, and the GraphQL write path with its own limit
        assertEquals(2, calls.size)
        check(calls.any { it.startsWith("private fun gh(") && "timeoutMs = timeoutMs" in it })
    }
}

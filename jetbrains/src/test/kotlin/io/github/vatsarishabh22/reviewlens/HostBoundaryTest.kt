package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Shared code talks to a host only through ReviewHost: the one shared file that may name a host's package is ReviewHost.kt, which picks it. */
class HostBoundaryTest {
    private val shared = Path.of("src/main/kotlin/io/github/vatsarishabh22/reviewlens")

    @Test
    fun `only ReviewHost reaches into a host's package`() {
        val reaching = Files.list(shared).use { files ->
            files.filter { it.toString().endsWith(".kt") }
                .filter { file -> Files.readAllLines(file).any { Regex("^import io\\.github\\.vatsarishabh22\\.reviewlens\\.(github|gitlab)\\.").containsMatchIn(it) } }
                .map { it.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("ReviewHost.kt"), reaching)
    }

    @Test
    fun `every read from GitHub goes through the one helper that bounds it`() {
        val calls = Files.readAllLines(shared.resolve("github/GhClient.kt")).filter { "runCli(" in it }.map { it.trim() }
        // the bounded read helper, and the GraphQL write path with its own limit
        assertEquals(2, calls.size)
        check(calls.any { it.startsWith("private fun gh(") && "timeoutMs = timeoutMs" in it })
    }
}

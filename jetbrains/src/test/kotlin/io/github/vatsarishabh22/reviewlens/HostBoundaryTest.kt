package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** GitHub's calls stay bounded; which files may reach a host's package is LayersTest's. */
class HostBoundaryTest {
    private val github = Path.of("src/main/kotlin/io/github/vatsarishabh22/reviewlens/host/github")

    @Test
    fun `every call to GitHub goes through the one send that bounds it`() {
        val client = Files.readString(github.resolve("GitHubClient.kt"))
        assertEquals("the client runs no CLI and opens no connection of its own", listOf(false, false), listOf("runCli(" in client, "HttpClient" in client))
        val api = Files.readAllLines(github.resolve("GitHubApi.kt")).map { it.trim() }
        assertEquals(1, api.count { "sendAsync(" in it })
        check(api.any { it.startsWith("call.get(waitMs") }) // a wall-clock stop, however the answer trickles in
        assertEquals("the token goes into the Authorization header and nowhere else", 1, api.count { "\$token" in it })
    }
}

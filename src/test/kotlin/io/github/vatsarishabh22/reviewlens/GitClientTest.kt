package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

/** macOS opens /tmp/x as the IDE's path while git reports /private/tmp/x; the plugin must use the IDE's spelling. */
class GitClientTest {

    private val real = Files.createTempDirectory("review-lens-real").toRealPath()
    private val link = Files.createSymbolicLink(Files.createTempDirectory("review-lens-link").resolve("repo"), real)

    init {
        check(ProcessBuilder("git", "init", "-q").directory(real.toFile()).start().waitFor() == 0)
        Files.createDirectories(real.resolve("sub"))
    }

    @Test
    fun `a project opened through a symlink keeps the symlinked root`() =
        assertEquals(link, GitClient.forProject(link.toString()).root)

    @Test
    fun `a project opened at a subfolder gets the repo root in the same spelling`() =
        assertEquals(link, GitClient.forProject(link.resolve("sub").toString()).root)
}

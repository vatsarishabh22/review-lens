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

    @Test
    fun `the remote's HEAD pointer is not listed as a branch`() {
        fun git(vararg args: String) = check(ProcessBuilder("git", *args).directory(real.toFile()).start().waitFor() == 0)
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "c")
        git("update-ref", "refs/remotes/origin/main", "HEAD")
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        assertEquals(listOf("origin/main"), GitClient(real).remoteBranches())
    }
}

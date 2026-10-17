package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Runs the lifecycle against a real throwaway git repo: the reviewer's work must survive every path. */
class ReviewLifecycleTest {

    private val repo: Path = Files.createTempDirectory("review-lens-test")
    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).directory(repo.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}: $out" }
        return out.trim()
    }
    private fun git(vararg args: String) = sh("git", "-c", "user.name=t", "-c", "user.email=t@t", *args)
    private fun read(name: String) = Files.readString(repo.resolve(name))
    private fun write(name: String, text: String) = Files.writeString(repo.resolve(name), text)

    private val target = ReviewTarget("pr-7", "#7 add feature", 7, "feat", "origin/main")

    /** main: app.py=v1. PR head: app.py=v2 plus new.py. Reviewer is on feat/my-work. */
    private fun setUp(): String {
        git("init", "-q", "-b", "main")
        write("app.py", "v1\n"); git("add", "."); git("commit", "-qm", "base")
        git("checkout", "-qb", "pr"); write("app.py", "v2\n"); write("new.py", "n\n"); git("add", "."); git("commit", "-qm", "pr")
        val head = git("rev-parse", "HEAD")
        git("checkout", "-q", "main"); git("checkout", "-qb", "feat/my-work")
        return head
    }

    @Test
    fun `dirty work is parked for the review and restored exactly on exit`() {
        val head = setUp()
        write("app.py", "my edit\n"); git("add", "app.py") // staged
        write("notes.md", "mine\n") // untracked
        Files.createDirectories(repo.resolve(".idea")); write(".idea/workspace.xml", "ide") // the open IDE's own files
        val lifecycle = ReviewLifecycle(GitClient(repo))

        val review = lifecycle.start(target, head)
        assertEquals("review/pr-7", git("branch", "--show-current"))
        assertEquals("v2\n", read("app.py"))
        assertFalse(Files.exists(repo.resolve("notes.md")))
        assertEquals("ide", read(".idea/workspace.xml")) // never parked
        assertEquals("feat/my-work", review.originalRef)

        assertEquals(Restored.RESTORED, lifecycle.exit())
        assertEquals("feat/my-work", git("branch", "--show-current"))
        assertEquals("my edit\n", read("app.py"))
        assertEquals("mine\n", read("notes.md"))
        assertEquals("M  app.py", git("status", "--porcelain").lines().first()) // still staged
        assertEquals("ide", read(".idea/workspace.xml"))
        assertEquals("", git("stash", "list"))
        assertNull(lifecycle.active())
    }

    @Test
    fun `a clean checkout reviews without stashing`() {
        val head = setUp()
        Files.createDirectories(repo.resolve(".idea")); write(".idea/workspace.xml", "ide") // IDE files alone are not "work"
        val lifecycle = ReviewLifecycle(GitClient(repo))
        assertNull(lifecycle.start(target, head).stashMessage)
        assertEquals(Restored.NOTHING_TO_RESTORE, lifecycle.exit())
        assertEquals("feat/my-work", git("branch", "--show-current"))
    }

    @Test
    fun `a failed checkout puts the reviewer back where they were with their work`() {
        setUp()
        write("app.py", "my edit\n")
        val lifecycle = ReviewLifecycle(GitClient(repo))
        try {
            lifecycle.start(target, "0000000000000000000000000000000000000bad")
            fail("expected the checkout to fail")
        } catch (_: CliException) {
        }
        assertEquals("feat/my-work", git("branch", "--show-current"))
        assertEquals("my edit\n", read("app.py"))
        assertEquals("", git("stash", "list"))
        assertNull(lifecycle.active())
    }

    @Test
    fun `a second start is refused while a review is active`() {
        val head = setUp()
        val lifecycle = ReviewLifecycle(GitClient(repo))
        lifecycle.start(target, head)
        val error = runCatching { lifecycle.start(target, head) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().startsWith("Already reviewing #7"))
        assertEquals("review/pr-7", git("branch", "--show-current"))
    }
}

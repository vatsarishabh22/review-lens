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

    @Test
    fun `start reports parking before checkout, and only parks dirty work`() {
        val head = setUp()
        write("app.py", "mine\n")
        val steps = mutableListOf<StartStep>()
        ReviewLifecycle(GitClient(repo)).start(target, head) { steps += it }
        assertEquals(listOf(StartStep.PARK, StartStep.CHECKOUT), steps)
    }

    @Test
    fun `dirty files list tracked edits and untracked files but not the IDE folder`() {
        setUp()
        write("app.py", "mine\n"); write("notes.md", "n\n")
        Files.createDirectories(repo.resolve(".idea")); write(".idea/workspace.xml", "x")
        assertEquals(listOf(" M app.py", "?? notes.md"), GitClient(repo).dirtyFiles())
    }

    @Test
    fun `commits carry author and time, newest first`() {
        val head = setUp()
        val base = git("rev-parse", "main")
        val commits = GitClient(repo).commits(base, head)
        assertEquals(1, commits.size)
        assertEquals("pr", commits[0].subject)
        assertEquals("t", commits[0].author)
        assertTrue(commits[0].time > 1_700_000_000)
    }

    @Test
    fun `parking works when the repo ignores the IDE folder but tracks files in it`() {
        setUp()
        // The JetBrains plugin template's shape: `.idea` in .gitignore, yet some .idea files committed (on main, so the PR has them too).
        git("checkout", "-q", "main")
        Files.createDirectories(repo.resolve(".idea")); write(".idea/gradle.xml", "g1\n"); write("keep.txt", "k\n")
        write(".gitignore", ".idea\n"); git("add", ".gitignore", "keep.txt"); git("add", "-f", ".idea/gradle.xml"); git("commit", "-qm", "ide")
        git("checkout", "-qb", "pr2"); write("app.py", "v2\n"); git("commit", "-qam", "pr2")
        val head = git("rev-parse", "HEAD")
        git("checkout", "-q", "-B", "feat/my-work", "main")
        write(".idea/gradle.xml", "g2\n"); write(".idea/workspace.xml", "w\n")
        write("app.py", "mine\n"); write("notes.md", "n\n"); git("mv", "keep.txt", "kept.txt")
        val lifecycle = ReviewLifecycle(GitClient(repo))
        lifecycle.start(target, head)
        assertEquals("v2\n", read("app.py"))
        assertEquals("g2\n", read(".idea/gradle.xml")) // the IDE's own file never moves
        assertEquals(Restored.RESTORED, lifecycle.exit())
        assertEquals("mine\n", read("app.py"))
        assertEquals("n\n", read("notes.md"))
        assertTrue(git("status", "--short").contains("R  keep.txt -> kept.txt"))
        assertEquals("", git("stash", "list"))
    }

    @Test
    fun `resume progress counts viewed files in the review's own scope, at their current version`() {
        val head = setUp()
        val git = GitClient(repo)
        val range = ScopeRange(git("rev-parse", "main"), head)
        val app = git.changes(range).first { it.path == "app.py" }
        ReviewMeta.save(git, ReviewMeta(target, range, "All commits (1)", mapOf("app.py" to app.newBlob, "gone.py" to "x")))
        assertEquals(ReviewProgress(1, 2), ReviewTargets(git).progress(target)) // app.py of {app.py, new.py}; a stale path does not count
        ReviewMeta.save(git, ReviewMeta(target, range, "All commits (1)", mapOf("app.py" to "old-blob")))
        assertEquals(ReviewProgress(0, 2), ReviewTargets(git).progress(target)) // changed since viewed
    }

    @Test
    fun `starting a review remembers the head it started on, for the next since-your-last-review`() {
        val head = setUp()
        val session = ReviewSession(GitClient(repo))
        val local = target.copy(base = "main") // the fixture repo has no remote
        val loaded = session.local(local, head)
        session.begin(loaded, resolveScope(loaded.mergeBase, loaded.commits, emptyList()), "All commits (1)")
        assertEquals(head, ReviewMeta.load(GitClient(repo), local.key)!!.seenHead)
    }

    @Test
    fun `re-scoping keeps the ticks and the ones GitHub has not confirmed yet`() {
        val head = setUp()
        val git = GitClient(repo)
        val session = ReviewSession(git)
        val local = target.copy(base = "main")
        val loaded = session.local(local, head)
        val range = resolveScope(loaded.mergeBase, loaded.commits, emptyList())
        session.begin(loaded, range, "All commits (1)")
        ReviewMeta.save(git, ReviewMeta.load(git, local.key)!!.copy(reviewed = mapOf("app.py" to "b"), unsent = mapOf("app.py" to true)))
        session.begin(loaded, range, "Commit abc1234")
        val meta = ReviewMeta.load(git, local.key)!!
        assertEquals("Commit abc1234" to mapOf("app.py" to "b"), meta.scopeLabel to meta.reviewed)
        assertEquals(mapOf("app.py" to true), meta.unsent)
    }
}

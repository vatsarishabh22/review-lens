package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** A stacked PR against a real throwaway repo: it shows what it adds on top of its parent (spec/README.md, Stacks). */
class StackLoadTest {

    private val repo: Path = Files.createTempDirectory("review-lens-stack")
    private val git = GitClient(repo)
    private val session = ReviewSession(git)
    private val child = ReviewTarget("pr-2", "#2 child", 2, "child", "main")

    private fun sh(vararg args: String): String {
        val p = ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@t", *args).directory(repo.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }
    private fun commit(file: String, text: String): String {
        Files.writeString(repo.resolve(file), text); sh("add", "."); sh("commit", "-qm", file); return sh("rev-parse", "HEAD")
    }
    private fun paths(loaded: LoadedTarget) = git.changes(ScopeRange(loaded.start, loaded.head)).map { it.path }.sorted()

    /** main: base.txt. parent (#1): p.py. child (#2) on top of it: c.py. Returns the parent's head. */
    private fun stack(): String {
        sh("init", "-q", "-b", "main"); commit("base.txt", "b\n")
        sh("checkout", "-qb", "parent"); val parent = commit("p.py", "p\n")
        sh("checkout", "-qb", "child"); commit("c.py", "c\n")
        return parent
    }

    @Test
    fun `a PR built on its parent shows only what it adds, and says which PR it sits on`() {
        val parent = stack()
        val loaded = session.loaded(child, sh("rev-parse", "child"), StackParent(1), parent)
        assertEquals(parent, loaded.start)
        assertEquals(listOf("c.py"), paths(loaded))
        assertEquals(listOf("c.py"), loaded.commits.map { it.subject })
        assertEquals(1 to null, loaded.parent to loaded.note)
    }

    @Test
    fun `a PR that merged a newer main than its parent still shows only its own changes`() {
        val parent = stack()
        sh("checkout", "-q", "main"); commit("m.py", "m\n")
        sh("checkout", "-q", "child"); sh("merge", "-q", "--no-edit", "main")
        val loaded = session.loaded(child, sh("rev-parse", "child"), StackParent(1), parent)
        assertEquals(listOf("c.py"), paths(loaded)) // neither main's m.py nor the parent's p.py
        assertEquals(listOf("Merge branch 'main' into child", "c.py"), loaded.commits.map { it.subject })
        assertEquals(1, loaded.parent)
    }

    @Test
    fun `a newer main that conflicts with the parent compares with main and says so`() {
        sh("init", "-q", "-b", "main"); commit("x.txt", "base\n")
        sh("checkout", "-qb", "parent"); val parent = commit("x.txt", "parent\n")
        sh("checkout", "-qb", "child"); commit("c.py", "c\n")
        sh("checkout", "-q", "main"); commit("x.txt", "main\n")
        sh("checkout", "-q", "child")
        runCatching { sh("merge", "-q", "--no-edit", "main") }
        commit("x.txt", "resolved\n")
        val loaded = session.loaded(child, sh("rev-parse", "child"), StackParent(1), parent)
        assertEquals(git.mergeBase("main", loaded.head), loaded.start)
        assertNull(loaded.parent)
        assertTrue(loaded.note!!, loaded.note.startsWith("This PR took a newer main than #1, and the two don't merge cleanly"))
    }

    @Test
    fun `a parent that moved after the PR was built on it leaves the PR comparing with main`() {
        stack()
        sh("checkout", "-q", "parent"); val moved = commit("p2.py", "later\n")
        for ((parent, head) in listOf(StackParent(1, moved = true) to null, StackParent(1) to moved)) {
            val loaded = session.loaded(child, sh("rev-parse", "child"), parent, head)
            assertEquals(listOf("c.py", "p.py"), paths(loaded))
            assertNull(loaded.parent)
            assertEquals("#1 changed after this PR was built on it, so for now it is compared with main: #1's changes show here too.", loaded.note)
        }
    }

    @Test
    fun `the scope menu starts from the saved base while it still leads to the head, else from the merge base`() {
        val parent = stack()
        val head = sh("rev-parse", "child")
        assertEquals(parent, session.local(child, head, parent).start)
        assertEquals(sh("rev-parse", "main"), session.local(child, head, sh("rev-parse", "main~0")).start)
        sh("checkout", "-q", "main"); val elsewhere = commit("z.txt", "z\n")
        assertEquals(git.mergeBase("main", head), session.local(child, head, elsewhere).start)
    }

    @Test
    fun `starting a stacked review saves where it starts, and a plain one saves none`() {
        val parent = stack()
        sh("checkout", "-q", "main")
        val head = sh("rev-parse", "child")
        fun saved(loaded: LoadedTarget): String? {
            session.begin(loaded, resolveScope(loaded.start, loaded.commits, emptyList()), "All commits")
            return ReviewMeta.load(git, child.key)!!.parentHead.also { session.lifecycle.exit() }
        }
        assertEquals(parent, saved(session.loaded(child, head, StackParent(1), parent)))
        assertNull(saved(session.loaded(child, head, null, null)))
    }

    @Test
    fun `a PR that merged a newer main keeps its own start in the scope menu and weeks later, the merge kept under a ref`() {
        val parent = stack()
        sh("checkout", "-q", "main"); commit("m.py", "m\n")
        sh("checkout", "-q", "child"); sh("merge", "-q", "--no-edit", "main")
        val head = sh("rev-parse", "child")
        val loaded = session.loaded(child, head, StackParent(1), parent)
        assertEquals(parent, loaded.parentHead) // what begin saves: the parent's head, never the merge made from it
        assertEquals(loaded.start, sh("rev-parse", "refs/review-lens/pr-2-base"))
        sh("gc", "-q", "--prune=now")
        val menu = session.local(child, head, loaded.parentHead)
        assertEquals(loaded.start to loaded.commits, menu.start to menu.commits) // the same merge, reused, not made again
        assertEquals(listOf("c.py"), paths(menu))
        session.begin(menu, resolveScope(menu.start, menu.commits, emptyList()), "All commits")
        assertEquals("the menu's next load starts from the parent's head", parent, ReviewMeta.load(git, child.key)!!.parentHead)
        session.lifecycle.exit()
    }

    @Test
    fun `stacked the host's way, the PR compares from its parent's branch as any PR does, and never warns`() {
        sh("init", "-q", "-b", "main"); commit("base.txt", "b\n")
        sh("checkout", "-qb", "parent"); commit("p.py", "p\n")
        sh("checkout", "-qb", "child"); commit("c.py", "c\n")
        sh("checkout", "-q", "parent"); commit("p2.py", "later\n") // the parent is pushed to; the PR's base is its branch
        val onBranch = ReviewTarget("pr-2", "#2 child", 2, "child", "parent")
        val loaded = session.loaded(onBranch, sh("rev-parse", "child"), StackParent(1, onBranch = true), null)
        assertEquals(listOf("c.py"), paths(loaded))
        assertEquals(1 to null, loaded.parent to loaded.note)
    }

    @Test
    fun `a review's start outlives a new merge of main and git's cleanup`() {
        val parent = stack()
        sh("checkout", "-q", "main"); commit("m.py", "m\n")
        sh("checkout", "-q", "child"); sh("merge", "-q", "--no-edit", "main")
        val loaded = session.loaded(child, sh("rev-parse", "child"), StackParent(1), parent)
        val range = resolveScope(loaded.start, loaded.commits, emptyList())
        session.begin(loaded, range, "All commits"); session.lifecycle.exit()
        // the author merges main again: the next load makes a new merge and moves <key>-base off the one this review saw
        sh("checkout", "-q", "main"); commit("m2.py", "m2\n")
        sh("checkout", "-q", "child"); sh("merge", "-q", "--no-edit", "main")
        session.loaded(child, sh("rev-parse", "child"), StackParent(1), parent)
        sh("gc", "-q", "--prune=now")
        assertEquals(listOf("c.py"), git.changes(ReviewMeta.load(git, child.key)!!.range).map { it.path })
    }

    @Test
    fun `with no host to ask, the parent the last list wrote down is used, still stacked the host's way`() {
        stack()
        fun pr(n: Int, head: String, base: String, sha: String) = PullRequest(n, "t", head, sha, base, Person("a", "a"), commits = listOf(sha))
        StackStore(git).observe(listOf(pr(1, "parent", "main", "p1"), pr(2, "child", "parent", "c1")))
        assertEquals(child to StackParent(1, onBranch = true), session.current(child)) // this repo has no host: the note answers
    }

    @Test
    fun `Check for new commits takes the PR's base branch as the host has it now, and keeps the saved one when it isn't listed`() {
        val onParent = ReviewTarget("pr-2", "#2 child", 2, "child", "origin/parent")
        val moved = PullRequest(2, "t", "child", "c1", "main", Person("a", "a")) // its parent merged: the host moved it onto main
        assertEquals("origin/main", rebased(onParent, listOf(moved)).base)
        assertEquals("origin/parent", rebased(onParent, emptyList()).base)
    }
}

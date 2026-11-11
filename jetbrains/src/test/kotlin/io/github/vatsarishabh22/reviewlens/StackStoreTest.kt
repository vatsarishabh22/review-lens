package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.Collections

/** The list's counts for stacked PRs: kept by two commit ids, asked of the host only when new (spec/README.md, Stacks). */
class StackStoreTest {
    private val repo = Files.createTempDirectory("review-lens-counts").also {
        check(ProcessBuilder("git", "init", "-q").directory(it.toFile()).start().waitFor() == 0)
    }
    private val counts = StackStore(GitClient(repo))
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private fun host(from: String, to: String): Int { asked += "$from..$to"; return to.length }

    @Test
    fun `a new key is asked once, then kept across loads`() {
        assertEquals(mapOf("a..b" to 1, "b..cc" to 2), counts.fill(listOf("a..b", "b..cc"), ::host))
        assertEquals(mapOf("a..b" to 1, "b..cc" to 2), StackStore(GitClient(repo)).fill(listOf("a..b", "b..cc"), ::host))
        assertEquals(listOf("a..b", "b..cc"), asked.sorted())
    }

    @Test
    fun `a push asks only the keys it made, and the keys no PR uses any more drop out`() {
        counts.fill(listOf("a..b", "b..c"), ::host)
        asked.clear()
        counts.fill(listOf("a..b2", "b2..c"), ::host) // a push to the middle PR moves its head: its key and its child's change
        assertEquals(listOf("a..b2", "b2..c"), asked.sorted())
        assertEquals(setOf("a..b2", "b2..c"), counts.counts().keys)
    }

    @Test
    fun `a key the host can't answer is left out and asked again next time`() {
        assertEquals(mapOf("a..b" to 1), counts.fill(listOf("a..b", "x..y")) { from, to -> if (from == "x") error("offline") else host(from, to) })
        asked.clear()
        counts.fill(listOf("a..b", "x..y"), ::host)
        assertEquals(listOf("x..y"), asked)
    }

    @Test
    fun `writing the parents keeps the counts, and filling the counts keeps the parents`() {
        counts.fill(listOf("a..b"), ::host)
        fun pr(n: Int, commits: List<String>) = PullRequest(n, "t", "b$n", commits.last(), "main", Person("a", "a"), commits = commits)
        counts.observe(listOf(pr(1, listOf("a")), pr(2, listOf("a", "c"))))
        asked.clear()
        counts.fill(listOf("a..b"), ::host)
        assertEquals("the count was kept, not asked again", emptyList<String>(), asked)
        assertEquals(mapOf(2 to SeenParent(1, "a")) to setOf("a..b"), counts.parents() to counts.counts().keys)
    }

    @Test
    fun `a list and a count fill writing at once both land`() {
        fun pr(n: Int, commits: List<String>) = PullRequest(n, "t", "b$n", commits.last(), "main", Person("a", "a"), commits = commits)
        val prs = listOf(pr(1, listOf("a")), pr(2, listOf("a", "c")))
        val keys = (1..40).map { "k$it..h$it" }
        val fill = Thread { counts.fill(keys) { _, _ -> Thread.sleep(1); 1 } }
        val lists = Thread { repeat(200) { counts.observe(prs) } }
        fill.start(); lists.start(); fill.join(); lists.join()
        assertEquals(mapOf(2 to SeenParent(1, "a")) to keys.toSet(), counts.parents() to counts.counts().keys)
    }

    @Test
    fun `only PRs still built on their parent get a key`() {
        fun pr(n: Int) = PullRequest(n, "t", "b$n", "h$n", "main", Person("a", "a"))
        assertEquals(mapOf(2 to "h1..h2"), StackStore.keys(listOf(pr(1), pr(2), pr(3), pr(4)), mapOf(2 to StackParent(1), 3 to StackParent(2, moved = true), 4 to StackParent(1, onBranch = true)))) // a moved PR and one stacked the host's way take the host's count
    }
}

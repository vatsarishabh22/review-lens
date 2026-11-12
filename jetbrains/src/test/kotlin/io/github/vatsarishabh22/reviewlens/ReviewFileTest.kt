package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/** The review's saved file has one writer: each change builds on what it holds now, so none writes over one made meanwhile. */
class ReviewFileTest {
    private val git = GitClient(Files.createTempDirectory("review-lens-file").also {
        check(ProcessBuilder("git", "init", "-q").directory(it.toFile()).start().waitFor() == 0)
    })
    private val target = ReviewTarget.forPr(7, "t", "h", "origin/main")
    private val start = ReviewMeta(target, ScopeRange("f", "t1"), "All commits (2)")

    @Test
    fun `a re-scope keeps a tick saved while it worked`() {
        ReviewMeta.change(git, target.key) { start }
        // a tick lands after the re-scope began, before it saves
        ReviewMeta.change(git, target.key) { it!!.copy(reviewed = mapOf("a.py" to "b1")) }
        val rescoped = ReviewMeta.change(git, target.key) { it!!.copy(range = ScopeRange("t1", "t2"), scopeLabel = "1 commit") }
        assertEquals(mapOf("a.py" to "b1"), rescoped.reviewed)
        assertEquals(ScopeRange("t1", "t2"), ReviewMeta.load(git, target.key)!!.range)
    }

    @Test
    fun `a load's answer, built on what it read before asking the host, never writes over a tick made since`() {
        val read = ReviewMeta.change(git, target.key) { start }
        val folded = read.copy(reviewed = mapOf("b.py" to "c1")) // the host's ticks, folded into what was read
        ReviewMeta.change(git, target.key) { it!!.copy(reviewed = mapOf("a.py" to "b1")) } // the reviewer ticks meanwhile
        val written = ReviewMeta.foldIn(git, read, folded)
        assertEquals("the newer tick wins; the next load folds the host in again", mapOf("a.py" to "b1"), written.reviewed)
        val quiet = ReviewMeta.foldIn(git, written, folded)
        assertEquals("with nothing in between, the load's answer is written", folded, quiet)
    }

    @Test
    fun `a reader never sees the file half written`() {
        val big = start.copy(reviewed = (1..4000).associate { "src/file$it.py" to "blob$it" })
        ReviewMeta.change(git, target.key) { big }
        var failed: Throwable? = null
        val firstRead = CountDownLatch(1)
        val writer = Thread { runCatching { firstRead.await(); repeat(150) { n -> ReviewMeta.change(git, target.key) { big.copy(scopeLabel = "take $n") } } }.onFailure { failed = it } }
        writer.start()
        do {
            assertEquals(4000, ReviewMeta.load(git, target.key)!!.reviewed.size)
            firstRead.countDown() // the writes start after a read, so every run reads while they run
        } while (writer.isAlive)
        writer.join()
        failed?.let { throw it } // a write that failed must fail the test, not pass on the reads alone
        assertEquals("take 149", ReviewMeta.load(git, target.key)!!.scopeLabel)
    }

    @Test
    fun `the host's answer confirms ticks only in the review they were sent for`() {
        val a = start.copy(unsent = mapOf("x.py" to true))
        assertEquals(null, confirmedIn(a, target.key, mapOf("x.py" to true)).unsent)
        val other = a.copy(target = ReviewTarget.forPr(8, "t", "h", "origin/main"))
        assertEquals("a late answer for review 7 never clears review 8's queue", mapOf("x.py" to true), confirmedIn(other, target.key, mapOf("x.py" to true)).unsent)
    }
}

package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

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
}

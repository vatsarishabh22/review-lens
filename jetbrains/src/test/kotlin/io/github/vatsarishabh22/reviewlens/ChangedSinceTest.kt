package io.github.vatsarishabh22.reviewlens

import io.github.vatsarishabh22.reviewlens.ViewedState.DISMISSED
import io.github.vatsarishabh22.reviewlens.ViewedState.UNVIEWED
import io.github.vatsarishabh22.reviewlens.ViewedState.VIEWED
import org.junit.Assert.assertEquals
import org.junit.Test

/** "🔄 k files changed since your review" (spec/README.md, the list's table): k from the host, only when it can say. */
class ChangedSinceTest {
    private val viewedThen = listOf("a.py", "b.py")
    private val scope = listOf("a.py", "b.py", "c.py")

    @Test
    fun `counts the files you viewed that the host un-marked, and none for a rebase`() {
        assertEquals(1, changedSince(viewedThen, scope, mapOf("a.py" to DISMISSED, "b.py" to VIEWED), null))
        assertEquals(0, changedSince(viewedThen, scope, mapOf("a.py" to VIEWED, "b.py" to VIEWED), null))
    }

    @Test
    fun `a file you didn't view at your last review never counts, whatever the host says`() =
        assertEquals(0, changedSince(viewedThen, scope, mapOf("a.py" to VIEWED, "b.py" to VIEWED, "c.py" to DISMISSED), null))

    @Test
    fun `a push to files outside your review is never taken for a rebase, but a change you viewed still counts`() {
        val newFile = mapOf("a.py" to VIEWED, "b.py" to VIEWED, "c.py" to UNVIEWED, "new.py" to UNVIEWED)
        assertEquals(null, changedSince(viewedThen, scope, newFile, null))
        assertEquals(1, changedSince(viewedThen, scope, newFile + ("a.py" to DISMISSED), null))
    }

    @Test
    fun `the host cannot say while it lacks your ticks, queued or none of them on it`() {
        assertEquals(null, changedSince(viewedThen, scope, mapOf("a.py" to VIEWED, "b.py" to VIEWED), mapOf("a.py" to true)))
        assertEquals(null, changedSince(viewedThen, scope, mapOf("a.py" to UNVIEWED, "b.py" to UNVIEWED), null))
        assertEquals(null, changedSince(emptyList(), scope, mapOf("a.py" to DISMISSED), null))
    }

    @Test
    fun `only a moved head on a review with files viewed asks the host`() {
        assertEquals(true, ReviewProgress(1, 3, headMoved = true).asksHost)
        assertEquals(false, ReviewProgress(1, 3, headMoved = false).asksHost)
        assertEquals(false, ReviewProgress(0, 3, Verdict.APPROVE, headMoved = true, ticked = true).asksHost) // ticks all on older versions
    }
}

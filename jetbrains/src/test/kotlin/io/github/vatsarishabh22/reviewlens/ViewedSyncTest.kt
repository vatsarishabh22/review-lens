package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The two checklists (this laptop's, GitHub's) and "Since your last review". Lines below are `gh`'s real output shape (PR #579). */
class ViewedSyncTest {

    private fun change(path: String, blob: String) = FileChange(path, null, 'M', blob, 1, 1)
    private val a = change("a.kt", "a2")
    private val b = change("b.kt", "b2")
    private val c = change("c.kt", "c2")
    private val d = change("d.kt", "d2")

    @Test
    fun `GitHub wins for its files`() = assertEquals(
        mapOf("a.kt" to "a2", "c.kt" to DISMISSED_BLOB, "d.kt" to "d1"),
        mergeViewed(
            local = mapOf("b.kt" to "b2", "d.kt" to "d1"),
            github = mapOf("a.kt" to ViewedState.VIEWED, "b.kt" to ViewedState.UNVIEWED, "c.kt" to ViewedState.DISMISSED, "d.kt" to ViewedState.DISMISSED),
            changes = listOf(a, b, c, d),
        ),
    )

    @Test
    fun `a tick GitHub has not confirmed is never erased`() = assertEquals(
        mapOf("a.kt" to "a2"),
        mergeViewed(local = mapOf("a.kt" to "a2"), github = mapOf("a.kt" to ViewedState.UNVIEWED, "b.kt" to ViewedState.VIEWED),
            changes = listOf(a, b), unsent = mapOf("a.kt" to true, "b.kt" to false)),
    )

    private val meta = ReviewMeta(ReviewTarget("pr-1", "#1", 1, "h", "main"), ScopeRange("f", "t"), "All", mapOf("a.kt" to "a2", "b.kt" to "b2"),
        unsent = mapOf("a.kt" to true, "b.kt" to true))

    @Test
    fun `a tick sent on reconnect is kept, not erased by the answer read before it went out`() {
        val synced = syncViewed(meta, mapOf("a.kt" to ViewedState.UNVIEWED, "b.kt" to ViewedState.UNVIEWED), listOf(a, b)) { path, _ -> path == "a.kt" }
        assertEquals(mapOf("a.kt" to "a2", "b.kt" to "b2"), synced.reviewed)
        assertEquals(mapOf("b.kt" to true), synced.unsent)
    }

    @Test
    fun `a queued tick for a file outside the PR on GitHub is dropped, never re-sent, and stays ticked here`() {
        val c = change("c.kt", "c2")
        val stuck = meta.copy(reviewed = meta.reviewed + ("c.kt" to "c2"), unsent = mapOf("a.kt" to true, "c.kt" to true))
        val sent = mutableListOf<String>()
        val synced = syncViewed(stuck, mapOf("a.kt" to ViewedState.UNVIEWED, "b.kt" to ViewedState.VIEWED), listOf(a, b, c)) { path, _ -> sent += path; true }
        assertEquals(listOf("a.kt"), sent) // c.kt has no Viewed box on GitHub: sending it is refused every load
        assertNull(synced.unsent)
        assertEquals("c2", synced.reviewed["c.kt"])
    }

    @Test
    fun `files GitHub does not list keep this laptop's answer`() =
        assertEquals(mapOf("x.kt" to "x1"), mergeViewed(mapOf("x.kt" to "x1"), emptyMap(), listOf(change("x.kt", "x2"))))

    private val commits = listOf(Commit("h3", "newest"), Commit("h2", "middle"), Commit("h1", "oldest"))

    @Test
    fun `since your last review covers only the commits pushed after it`() =
        assertEquals(ScopeRange("h1", "h3") to "Since your last review (2)", sinceLastReview("h3", commits, "h1"))

    @Test
    fun `nothing new, never reviewed, or a rewritten history offers no since scope`() {
        assertNull(sinceLastReview("h3", commits, "h3"))
        assertNull(sinceLastReview("h3", commits, null))
        assertNull(sinceLastReview("h3", commits, "gone-after-force-push"))
    }
}

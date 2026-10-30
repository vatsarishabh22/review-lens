package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReviewScopeTest {
    private val c3 = Commit("c3", "newest")
    private val c2 = Commit("c2", "middle")
    private val c1 = Commit("c1", "oldest")
    private val commits = listOf(c3, c2, c1)

    @Test
    fun `no pick or every commit is the whole PR`() {
        assertEquals(ScopeRange("base", "c3"), resolveScope("base", commits, emptyList()))
        assertEquals(ScopeRange("base", "c3"), resolveScope("base", commits, commits))
    }

    @Test
    fun `one commit is its own diff`() = assertEquals(ScopeRange("c2^", "c2"), resolveScope("base", commits, listOf(c2)))

    @Test
    fun `a range spans oldest parent to newest`() =
        assertEquals(ScopeRange("c1^", "c2"), resolveScope("base", commits, setOf(c2, c1)))
}

class ToggleScopeTest {
    @Test
    fun `checking fills the gap to the other picks`() = assertEquals(setOf(1, 2, 3), toggleScope(setOf(1), 3))

    @Test
    fun `unchecking a middle commit keeps the newer part`() = assertEquals(setOf(1), toggleScope(setOf(1, 2, 3), 2))

    @Test
    fun `unchecking the newest keeps the rest`() = assertEquals(setOf(2, 3), toggleScope(setOf(1, 2, 3), 1))

    @Test
    fun `unchecking the only pick empties the scope`() = assertEquals(emptySet<Int>(), toggleScope(setOf(4), 4))
}

class PreviousIndexTest {
    @Test fun `steps back one`() = assertEquals(1, previousIndex(3, 2))
    @Test fun `wraps from the first to the last`() = assertEquals(2, previousIndex(3, 0))
    @Test fun `a file outside the list starts from the last`() = assertEquals(2, previousIndex(3, -1))
    @Test fun `an empty list has no previous`() = assertEquals(-1, previousIndex(0, -1))

    // A branch key must never name another state file: active.json is the review in progress, pr-N.json a PR's state.
    @Test fun `a branch named active can't overwrite the review in progress`() =
        assertNotEquals("active", ReviewTarget.forBranch("origin/active", "origin/main").key)
    @Test fun `a branch named like a PR doesn't share that PR's state`() =
        assertNotEquals(ReviewTarget.forPr(7, "t", "h", "origin/main").key, ReviewTarget.forBranch("origin/pr-7", "origin/main").key)
    @Test fun `branch keys keep only file-safe characters`() =
        assertEquals("branch-feat_csv_export", ReviewTarget.forBranch("origin/feat/csv export", "origin/main").key)

    // Every "#7" the UI shows comes from ref; the stored label keeps its old text so existing state files still read.
    @Test fun `a PR is named by its number and a branch by nothing`() {
        val pr = ReviewTarget.forPr(7, "Add CSV export", "h", "origin/main")
        assertEquals("#7", pr.ref)
        assertEquals("#7 Add CSV export", pr.label)
        assertEquals(null, ReviewTarget.forBranch("origin/feat", "origin/main").ref)
    }
}

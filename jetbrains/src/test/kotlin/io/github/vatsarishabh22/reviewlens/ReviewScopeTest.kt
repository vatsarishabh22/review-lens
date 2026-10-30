package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

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

    // Every "#7" the UI shows comes from ref; the stored label keeps its old text so existing state files still read.
    @Test fun `a PR is named by its number and a branch by nothing`() {
        val pr = ReviewTarget.forPr(7, "Add CSV export", "h", "origin/main")
        assertEquals("#7", pr.ref)
        assertEquals("#7 Add CSV export", pr.label)
        assertEquals(null, ReviewTarget.forBranch("origin/feat", "origin/main").ref)
    }
}

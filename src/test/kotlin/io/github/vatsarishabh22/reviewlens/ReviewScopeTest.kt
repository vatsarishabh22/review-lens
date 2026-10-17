package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
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

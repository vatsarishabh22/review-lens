package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the PR list shows and where (spec/README.md, Stacks): whole stacks, the request pulling one in, Next, "on #p". */
class ListSectionsTest {
    private fun t(n: Int, title: String = "PR $n", requested: Boolean = false, parent: Int? = null) =
        ListedTarget(ReviewTarget.forPr(n, title, "b$n", "origin/main"), title, PullRequest(n, title, "b$n", "h$n", "main", Person("a", "a"), requested = requested), parent?.let { StackParent(it) })
    private val stack = listOf(t(1, "parse"), t(2, "validate", requested = true, parent = 1), t(3, "report", parent = 2))
    private val lone = t(9, "docs")
    private fun shape(sections: List<ListSection>) = sections.map { s -> s.title to (s.stacks.map { st -> st.map { it.pr!!.number } } to s.rows.map { it.pr!!.number }) }

    @Test
    fun `one PR asking for you brings its whole stack into Waiting on you`() =
        assertEquals(listOf("Waiting on you" to (listOf(listOf(1, 2, 3)) to emptyList<Int>()), "Other open" to (emptyList<List<Int>>() to listOf(9))),
            shape(listSections(stack + lone, listOf(stack), "")))

    @Test
    fun `a search matching one PR of a stack shows the stack whole, and empty sections go`() =
        assertEquals(listOf("Waiting on you" to (listOf(listOf(1, 2, 3)) to emptyList<Int>())), shape(listSections(stack + lone, listOf(stack), "REPORT")))

    @Test
    fun `without the host, branches are listed alone`() {
        val branch = ListedTarget(ReviewTarget.forBranch("origin/feature", "origin/main"), "feature", null)
        assertEquals(listOf("Branches" to listOf("feature")), listSections(listOf(branch), emptyList(), "").map { it.title to it.rows.map { r -> r.title } })
    }

    @Test
    fun `the next PR is the lowest not reviewed, and none once all are`() {
        val done = ReviewProgress(1, 1, Verdict.APPROVE)
        val rows = stack.mapIndexed { i, t -> StackRow(t, if (i == 0) done else null, 1) }
        assertEquals(2, nextInStack(rows)?.listed?.pr?.number)
        assertEquals(null, nextInStack(rows.map { it.copy(progress = done) }))
    }

    @Test
    fun `the second of two PRs on one parent says which it sits on`() {
        val rows = listOf(t(1), t(2, parent = 1), t(4, parent = 2), t(3, parent = 1)).map { StackRow(it, null, 1) } // 1 <- 2 <- 4, and 1 <- 3
        assertEquals(listOf(null, null, null, 1), rows.indices.map { builtOn(rows, it) })
    }
}

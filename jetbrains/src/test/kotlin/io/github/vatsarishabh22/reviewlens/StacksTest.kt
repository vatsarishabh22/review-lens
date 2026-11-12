package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

class StacksTest {
    private fun pr(n: Int) = PullRequest(n, "t", "b$n", "h$n", "main", Person("a", "a"))

    @Test
    fun `a stack lists bottom first, a PR's children after it by number, and leaves lone PRs out`() {
        val prs = listOf(pr(9), pr(7), pr(5), pr(6), pr(8), pr(3))
        // 5 <- 6 <- 8, and 5 <- 7: a second PR built on 5 follows 6's line
        assertEquals(listOf(listOf(5, 6, 8, 7)), stacks(prs, mapOf(7 to 5, 8 to 6, 6 to 5).mapValues { StackParent(it.value) }).map { s -> s.map { it.number } })
    }

    @Test
    fun `a PR whose base is its parent's branch is marked stacked the host's way, one built on main is not`() {
        fun pr(n: Int, head: String, base: String, commits: List<String>) = PullRequest(n, "t", head, commits.last(), base, Person("a", "a"), commits = commits)
        val prs = listOf(pr(1, "one", "main", listOf("a")), pr(2, "two", "one", listOf("b")), pr(3, "three", "main", listOf("a", "c")))
        assertEquals(mapOf(2 to StackParent(1, onBranch = true), 3 to StackParent(1)), stackView(prs, emptyMap()).parents)
    }

    @Test
    fun `two PRs naming each other are no stack`() = assertEquals(emptyList<List<PullRequest>>(), stacks(listOf(pr(1), pr(2)), mapOf(1 to StackParent(2), 2 to StackParent(1))))
}

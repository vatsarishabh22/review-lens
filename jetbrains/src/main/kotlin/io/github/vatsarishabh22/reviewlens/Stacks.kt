package io.github.vatsarishabh22.reviewlens

/**
 * The open PR a PR is built on; [moved] when it changed after the PR was built on it, so the PR compares with its base;
 * [onBranch] when it is stacked the host's way (its base is that PR's head branch), so git already compares from it.
 */
data class StackParent(val number: Int, val moved: Boolean = false, val onBranch: Boolean = false)

/** What a list saw: the PR's parent, that parent's head then, and whether it is stacked the host's way (`stacks.json`'s `parents`). */
data class SeenParent(val number: Int, val head: String, val onBranch: Boolean = false)

/** Each open PR's parent (spec/README.md, Stacks), and what to write down for the next list. */
data class StackView(val parents: Map<Int, StackParent>, val seen: Map<Int, SeenParent>)

/**
 * Stacked the host's way first: the open PR from this repository whose head branch is this PR's base is its parent,
 * never moved (a release PR is so the parent of every PR into its branch: wider than a stack, said in the spec). Then the
 * exact fact: the open PR whose head is in its commits, the closest when several are: the head sitting latest in this
 * PR's own list (the host cuts every list at 100, so list lengths can't tell). A PR at this PR's own head is never its
 * parent. The [seen] note speaks only for a parent that moved: the PR still holds the head the note saw, and no head
 * the exact fact finds sits later. Never a guess.
 */
fun stackView(prs: List<PullRequest>, seen: Map<Int, SeenParent>): StackView {
    val open = prs.associateBy { it.number }
    val parents = prs.mapNotNull { pr ->
        val at = pr.commits.withIndex().associate { (i, sha) -> sha to i }
        // a PR at this PR's own head holds its head as this one holds theirs: neither is built on the other
        val others = prs.filter { it.number != pr.number && it.headSha != pr.headSha }
        // a PR's base branch lives here: a fork's same-named branch (a contributor's main) is no one's parent
        others.firstOrNull { it.head == pr.base && !it.fromFork }?.let { return@mapNotNull pr.number to StackParent(it.number, onBranch = true) }
        val exact = others.filter { it.headSha in at }.maxByOrNull { at.getValue(it.headSha) }
        val moved = seen[pr.number]?.let { note ->
            others.firstOrNull { it.number == note.number }?.takeIf { note.head in at && (exact == null || at.getValue(exact.headSha) < at.getValue(note.head)) }
        }
        (moved?.let { StackParent(it.number, moved = true) } ?: exact?.let { StackParent(it.number) })?.let { pr.number to it }
    }.toMap()
    // a moved parent keeps the note that proves it; a built one is written as it is now
    val notes = parents.mapNotNull { (n, p) -> (if (p.moved) seen[n] else open[p.number]?.let { SeenParent(p.number, it.headSha, p.onBranch) })?.let { n to it } }.toMap()
    return StackView(parents, notes)
}

/**
 * The stacks among [prs], each bottom first, a PR's children after it by number (two PRs on one parent follow one
 * another); a PR with no parent and no child is in none.
 */
fun stacks(prs: List<PullRequest>, parents: Map<Int, StackParent>): List<List<PullRequest>> {
    val byNumber = prs.associateBy { it.number }
    val children = parents.entries.groupBy({ it.value.number }, { it.key })
    fun chain(n: Int): List<PullRequest> = listOfNotNull(byNumber[n]) + children[n].orEmpty().sorted().flatMap(::chain)
    return prs.filter { it.number !in parents && it.number in children }.map { chain(it.number) }
}

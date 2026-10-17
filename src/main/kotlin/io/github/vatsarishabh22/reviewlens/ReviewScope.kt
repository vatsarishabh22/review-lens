package io.github.vatsarishabh22.reviewlens

data class Commit(val sha: String, val subject: String, val author: String = "", val time: Long = 0) {
    val label get() = "${sha.take(7)}  $subject"
}

/** The review shows changes from [from] to [to]; the checkout sits at the PR head. */
data class ScopeRange(val from: String, val to: String)

/** What is under review: a PR (listed via gh) or a plain remote branch (without gh). */
data class ReviewTarget(val key: String, val label: String, val prNumber: Int?, val head: String, val base: String)

/**
 * Turns the commits picked in the scope popup into a range. [commits] are newest first, as `git log` lists them.
 * No pick, or every commit, means the whole PR from [mergeBase]; a contiguous pick covers oldest-picked^..newest-picked.
 */
fun resolveScope(mergeBase: String, commits: List<Commit>, picked: Collection<Commit>): ScopeRange {
    require(commits.isNotEmpty()) { "Nothing to review: the branch has no commits beyond its base" }
    if (picked.isEmpty() || picked.size == commits.size) return ScopeRange(mergeBase, commits.first().sha)
    val indexes = picked.map { commits.indexOf(it) }
    return ScopeRange("${commits[indexes.max()].sha}^", commits[indexes.min()].sha)
}

/**
 * The scope list's checked commit indexes after the reviewer clicks [index] (0 = newest). A scope is one git range, so
 * checking fills the gap to the other picks, and unchecking a middle commit keeps only the part newer than it.
 */
fun toggleScope(picked: Set<Int>, index: Int): Set<Int> = when {
    index !in picked -> (picked + index).let { (it.min()..it.max()).toSet() }
    else -> picked.filter { it < index }.toSet().ifEmpty { picked - index }
}

/** The index before [index] in a list of [size], wrapping to the last; -1 (not in the list) also starts from the last. */
fun previousIndex(size: Int, index: Int): Int = if (index <= 0) size - 1 else index - 1

/**
 * "Since your last review": the commits pushed after [seenHead] up to the PR head. Null when nothing is new, or when
 * [seenHead] is no longer in the PR's history (a force-push rewrote it).
 */
fun sinceLastReview(head: String, commits: List<Commit>, seenHead: String?): Pair<ScopeRange, String>? {
    val newCommits = commits.indexOfFirst { it.sha == seenHead } // commits are newest first, so the index counts what's new
    if (seenHead == null || newCommits <= 0) return null
    return ScopeRange(seenHead, head) to "Since your last review ($newCommits)"
}

fun scopeLabel(commits: List<Commit>, picked: Collection<Commit>): String = when {
    picked.isEmpty() || picked.size == commits.size -> "All commits (${commits.size})"
    picked.size == 1 -> "Commit ${picked.first().sha.take(7)}"
    else -> "${picked.size} of ${commits.size} commits"
}

package io.github.vatsarishabh22.reviewlens

data class Commit(val sha: String, val subject: String) {
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

fun scopeLabel(commits: List<Commit>, picked: Collection<Commit>): String = when {
    picked.isEmpty() || picked.size == commits.size -> "All commits (${commits.size})"
    picked.size == 1 -> "Commit ${picked.first().sha.take(7)}"
    else -> "${picked.size} of ${commits.size} commits"
}

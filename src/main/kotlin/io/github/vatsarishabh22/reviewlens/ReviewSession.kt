package io.github.vatsarishabh22.reviewlens

/** A target after fetching: its head commit, where it forked from its base, and its commits newest first. */
data class LoadedTarget(val target: ReviewTarget, val head: String, val mergeBase: String, val commits: List<Commit>)

/** Fetches a target and starts, re-scopes or ends its review in this checkout. Git calls block: run off EDT. */
class ReviewSession(val git: GitClient) {

    val lifecycle = ReviewLifecycle(git)

    fun load(target: ReviewTarget): LoadedTarget {
        val ref = target.prNumber?.let { git.fetchPr(it, target.base.removePrefix("${GitClient.REMOTE}/")) }
            ?: target.head.also { git.fetchAll() }
        val head = git.revParse(ref)
        val mergeBase = git.mergeBase(target.base, head)
        return LoadedTarget(target, head, mergeBase, git.commits(mergeBase, head))
    }

    /** Starts the review (parking the reviewer's work), or re-scopes the one in progress and moves it to a newer head. */
    fun begin(loaded: LoadedTarget, range: ScopeRange, scopeLabel: String): ActiveReview {
        val active = lifecycle.active()
        val review = when {
            active == null -> lifecycle.start(loaded.target, loaded.head)
            active.target.key != loaded.target.key -> error("Already reviewing ${active.target.label}. Exit that review first.")
            active.head != loaded.head -> lifecycle.moveTo(loaded.head)
            else -> active
        }
        val reviewed = ReviewMeta.load(git, loaded.target.key)?.reviewed.orEmpty()
        ReviewMeta.save(git, ReviewMeta(loaded.target, range, scopeLabel, reviewed))
        return review
    }
}

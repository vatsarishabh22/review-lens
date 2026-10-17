package io.github.vatsarishabh22.reviewlens

/** A target after fetching: its head commit, where it forked from its base, and its commits newest first. */
data class LoadedTarget(val target: ReviewTarget, val head: String, val mergeBase: String, val commits: List<Commit>)

/** Fetches a target and starts, re-scopes or ends its review in this checkout. Git calls block: run off EDT. */
class ReviewSession(val git: GitClient) {

    val lifecycle = ReviewLifecycle(git)

    fun load(target: ReviewTarget): LoadedTarget {
        val ref = target.prNumber?.let { git.fetchPr(it, target.base.removePrefix("${GitClient.REMOTE}/")) }
            ?: target.head.also { git.fetchAll() }
        return local(target, git.revParse(ref))
    }

    /** The target's commits up to [head] from what is already fetched: the review bar's scope menu, no network. */
    fun local(target: ReviewTarget, head: String): LoadedTarget {
        val mergeBase = git.mergeBase(target.base, head)
        return LoadedTarget(target, head, mergeBase, git.commits(mergeBase, head))
    }

    /**
     * Starts the review (parking the reviewer's work), or re-scopes the one in progress. The checkout sits at the
     * scope's end ([ScopeRange.to]), so the files on disk are exactly what the scope delivers: one-commit scope = that commit.
     */
    fun begin(loaded: LoadedTarget, range: ScopeRange, scopeLabel: String, progress: (StartStep) -> Unit = {}): ActiveReview {
        val active = lifecycle.active()
        val review = when {
            active == null -> lifecycle.start(loaded.target, range.to, progress)
            active.target.key != loaded.target.key -> error("Already reviewing ${active.target.label}. Exit that review first.")
            active.head != range.to -> lifecycle.moveTo(range.to)
            else -> active
        }
        // copy, not rebuild: the ticks and the queue of ticks GitHub has not confirmed outlive a change of scope
        val meta = ReviewMeta.load(git, loaded.target.key)?.copy(target = loaded.target, range = range, scopeLabel = scopeLabel, seenHead = loaded.head)
            ?: ReviewMeta(loaded.target, range, scopeLabel, seenHead = loaded.head)
        ReviewMeta.save(git, meta)
        return review
    }
}

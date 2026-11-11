package io.github.vatsarishabh22.reviewlens

/**
 * A target after fetching: its head commit, where the whole of it starts ([start]: the merge base with its base branch,
 * or in a stack where its own changes start), and its own commits newest first. In a stack: the [parent] it is compared
 * from and that parent's head [parentHead]; or [note], why it compares with its base branch instead.
 */
data class LoadedTarget(
    val target: ReviewTarget, val head: String, val start: String, val commits: List<Commit>,
    val parent: Int? = null, val note: String? = null, val parentHead: String? = null,
)

/** Fetches a target and starts, re-scopes or ends its review in this checkout. Git calls block: run off EDT. */
class ReviewSession(val git: GitClient) {

    val lifecycle = ReviewLifecycle(git)

    /** Fetches [target] (and its stack [parent]'s head with it, when the PR is compared from it) and lists what it adds. */
    fun load(target: ReviewTarget, parent: StackParent? = null): LoadedTarget {
        val n = target.prNumber ?: return git.fetchAll().let { local(target, git.revParse(target.head)) }
        val host = ReviewHosts.required(git)
        val from = parent?.takeUnless { it.moved || it.onBranch }
        val head = git.revParse(git.fetchReview(host.headRef(n), target.key, target.base.removePrefix("${GitClient.REMOTE}/"),
            from?.let { host.headRef(it.number) to ReviewTarget.keyOf(it.number) }))
        return loaded(target, head, parent, from?.let { git.revParse(git.reviewRef(ReviewTarget.keyOf(it.number))) })
    }

    /** What [target] at [head] adds: from its merge base, or in a stack from its [parent]'s head [parentHead] (null when it moved). No network. */
    fun loaded(target: ReviewTarget, head: String, parent: StackParent?, parentHead: String?): LoadedTarget {
        val mergeBase = git.mergeBase(target.base, head)
        // stacked the host's way, git already compares from where the PR left its parent's branch
        if (parent == null || parent.onBranch) return LoadedTarget(target, head, mergeBase, git.commits(mergeBase, head), parent?.number)
        val from = parentHead?.let { ownStart(target, it, head, mergeBase) }
        if (from != null) return LoadedTarget(target, head, from, git.commits(from, head), parent.number, parentHead = parentHead)
        val p = ReviewTarget.refOf(parent.number)
        val base = target.base.removePrefix("${GitClient.REMOTE}/")
        val note = if (parentHead == null || !git.isAncestor(parentHead, head)) "$p changed after this PR was built on it, so for now it is compared with $base: $p's changes show here too."
            else "This PR took a newer $base than $p, and the two don't merge cleanly, so it is compared with $base: $p's changes show here too."
        return LoadedTarget(target, head, mergeBase, git.commits(mergeBase, head), note = note)
    }

    /**
     * [target] as the host has it now, with its stack parent: its base branch as it is now (a merged parent's branch is
     * gone, and the host moved the PR onto the parent's base), the parent written down as a list does. When the host
     * can't say: the target as saved and the parent the last list wrote down. A branch has neither.
     */
    fun current(target: ReviewTarget): Pair<ReviewTarget, StackParent?> {
        val n = target.prNumber ?: return target to null
        val store = StackStore(git)
        return runCatching { ReviewHosts.required(git).openPullRequests() }
            .map { prs -> rebased(target, prs) to store.observe(prs)[n] }
            .getOrElse { target to store.parents()[n]?.let { StackParent(it.number, onBranch = it.onBranch) } }
    }

    /**
     * Where a stacked PR's own changes start (spec/README.md, Stacks): its parent's head, or that merged with the newer
     * base the PR took (kept under `<key>-base`); null when the parent moved off this PR or the merge conflicts.
     */
    private fun ownStart(target: ReviewTarget, parentHead: String, head: String, mergeBase: String): String? = when {
        !git.isAncestor(parentHead, head) -> null
        git.isAncestor(mergeBase, parentHead) -> parentHead
        else -> git.mergeCommit(parentHead, mergeBase, git.reviewRef("${target.key}-base"))
    }

    /**
     * The target's commits up to [head] from what is already fetched, from the saved [parentHead] by the same rule as
     * [loaded] when it still applies: the review bar's scope menu, no network.
     */
    fun local(target: ReviewTarget, head: String, parentHead: String? = null): LoadedTarget {
        val mergeBase = git.mergeBase(target.base, head)
        val from = parentHead?.let { ownStart(target, it, head, mergeBase) }
        return LoadedTarget(target, head, from ?: mergeBase, git.commits(from ?: mergeBase, head), parentHead = parentHead.takeIf { from != null })
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
        // the start may be a commit on no branch (a stack's merge base): kept from git's cleanup, so the saved range resolves
        git.pin(git.reviewRef("${loaded.target.key}-from"), range.from)
        // copy, not rebuild: the ticks and the queue of ticks GitHub has not confirmed outlive a change of scope
        val meta = ReviewMeta.load(git, loaded.target.key)?.copy(target = loaded.target, range = range, scopeLabel = scopeLabel, seenHead = loaded.head, parentHead = loaded.parentHead)
            ?: ReviewMeta(loaded.target, range, scopeLabel, seenHead = loaded.head, parentHead = loaded.parentHead)
        ReviewMeta.save(git, meta)
        return review
    }
}

/** [target] on the base branch its PR has now among [prs]; as saved when it isn't listed (closed, or not a PR). */
fun rebased(target: ReviewTarget, prs: List<PullRequest>): ReviewTarget =
    prs.firstOrNull { it.number == target.prNumber }?.let { target.copy(base = "${GitClient.REMOTE}/${it.base}") } ?: target

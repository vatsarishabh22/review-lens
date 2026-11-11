package io.github.vatsarishabh22.reviewlens

/** A row of the panel's list: a PR from `gh`, or a remote branch when `gh` can't list PRs ([pr] is null); [parent] in a stack. */
data class ListedTarget(val target: ReviewTarget, val title: String, val pr: PullRequest?, val parent: StackParent? = null) {
    val requested get() = pr?.requested == true
}

/**
 * The panel's rows; [branchesOnly] when `gh` could not list PRs, so the panel says why it shows branches (even none);
 * [stacks]: the rows built on one another, each bottom first; [stackKeys]: a stacked PR's key in [StackStore].
 */
data class Listing(
    val targets: List<ListedTarget>, val branchesOnly: Boolean, val hostName: String,
    val stacks: List<List<ListedTarget>> = emptyList(), val stackKeys: Map<Int, String> = emptyMap(),
)

/** What the Review panel lists: open PRs, or remote branches when this repo has no reachable GitHub. Runs CLIs: call off EDT. */
class ReviewTargets(private val git: GitClient) {

    fun list(): Listing {
        // picked again on every load, so signing in or out shows; no host signed in for origin, or no answer: branches stand in
        val host = ReviewHosts.refresh(git)
        val prs = host?.let { runCatching { it.openPullRequests() }.getOrNull() }
        if (prs == null) return Listing(branches(), branchesOnly = true, ReviewHosts.nameOf(git))
        // what this list sees is written down, so a parent pushed to before the next list is said to have moved, not guessed
        val parents = StackStore(git).observe(prs)
        val targets = prs.map { pr ->
            ListedTarget(ReviewTarget.forPr(pr.number, pr.title, pr.head, "${GitClient.REMOTE}/${pr.base}"), pr.title, pr, parents[pr.number])
        }
        val byNumber = targets.associateBy { it.pr!!.number }
        val stacks = stacks(prs, parents).map { stack -> stack.map { byNumber.getValue(it.number) } }
        return Listing(targets, branchesOnly = false, host.name, stacks, StackStore.keys(prs, parents))
    }

    /**
     * Progress of a review this reviewer started (a tick, a pending draft or a posted verdict), counted in its own saved
     * scope and against the PR's head now; null when it was only opened.
     */
    fun progress(listed: ListedTarget): ReviewProgress? {
        val meta = ReviewMeta.load(git, listed.target.key) ?: return null
        val drafts = meta.drafts.orEmpty().count { !it.posted }
        if (meta.reviewed.isEmpty() && meta.verdict == null && drafts == 0) return null
        val changes = runCatching { git.changes(meta.range) }.getOrNull() ?: return null
        return ReviewProgress(changes.count { meta.reviewed[it.path] == it.newBlob }, changes.size, meta.verdict, meta.headMoved(listed.pr?.headSha), drafts, meta.reviewed.isNotEmpty())
    }

    private fun branches(): List<ListedTarget> {
        val base = git.defaultBase()
        return git.remoteBranches().filter { it != base }.map {
            ListedTarget(ReviewTarget.forBranch(it, base), it.removePrefix("${GitClient.REMOTE}/"), null)
        }
    }
}

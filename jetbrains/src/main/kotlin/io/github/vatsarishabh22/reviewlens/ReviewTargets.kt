package io.github.vatsarishabh22.reviewlens

/** A row of the panel's list: a PR from `gh`, or a remote branch when `gh` can't list PRs ([pr] is null). */
data class ListedTarget(val target: ReviewTarget, val title: String, val pr: PullRequest?) {
    val requested get() = pr?.requested == true
}

/** The panel's rows; [branchesOnly] when `gh` could not list PRs, so the panel says why it shows branches (even none). */
data class Listing(val targets: List<ListedTarget>, val branchesOnly: Boolean, val hostName: String)

/** What the Review panel lists: open PRs, or remote branches when this repo has no reachable GitHub. Runs CLIs: call off EDT. */
class ReviewTargets(private val git: GitClient) {

    fun list(): Listing {
        // picked again on every load, so signing in or out shows; no host signed in for origin, or no answer: branches stand in
        val host = ReviewHosts.refresh(git)
        val prs = host?.let { runCatching { it.openPullRequests() }.getOrNull() }
        if (prs == null) return Listing(branches(), branchesOnly = true, ReviewHosts.nameOf(git))
        return Listing(prs.map { pr ->
            ListedTarget(ReviewTarget.forPr(pr.number, pr.title, pr.head, "${GitClient.REMOTE}/${pr.base}"), pr.title, pr)
        }, branchesOnly = false, host.name)
    }

    /** Progress of a review this reviewer started before, counted in its own saved scope and against the PR's head now; null when never started. */
    fun progress(listed: ListedTarget): ReviewProgress? {
        val meta = ReviewMeta.load(git, listed.target.key)?.takeIf { it.reviewed.isNotEmpty() } ?: return null
        val changes = runCatching { git.changes(meta.range) }.getOrNull() ?: return null
        return ReviewProgress(changes.count { meta.reviewed[it.path] == it.newBlob }, changes.size, meta.verdict, meta.headMoved(listed.pr?.headSha))
    }

    private fun branches(): List<ListedTarget> {
        val base = git.defaultBase()
        return git.remoteBranches().filter { it != base }.map {
            ListedTarget(ReviewTarget.forBranch(it, base), it.removePrefix("${GitClient.REMOTE}/"), null)
        }
    }
}

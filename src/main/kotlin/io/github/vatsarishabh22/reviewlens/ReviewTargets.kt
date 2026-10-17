package io.github.vatsarishabh22.reviewlens

/** A row of the panel's list: a PR from `gh`, or a remote branch when `gh` can't list PRs ([pr] is null). */
data class ListedTarget(val target: ReviewTarget, val title: String, val pr: PullRequest?, val requested: Boolean)

/** What the Review panel lists: open PRs, or remote branches when this repo has no reachable GitHub. Runs CLIs: call off EDT. */
class ReviewTargets(private val git: GitClient, private val gh: GhClient = GhClient(git.root)) {

    fun list(): List<ListedTarget> {
        // gh can be logged in while this repo isn't on GitHub; then PRs can't be listed and branches stand in.
        val prs = if (gh.isReady()) runCatching { gh.openPullRequests() }.getOrNull() else null
        if (prs == null) return branches()
        // ponytail: matches user review requests only; a request to one of the viewer's teams lists under "Other open".
        val me = runCatching { gh.viewerLogin() }.getOrNull()
        return prs.map { pr ->
            val target = ReviewTarget("pr-${pr.number}", "#${pr.number} ${pr.title}", pr.number, pr.headRefName, "${GitClient.REMOTE}/${pr.baseRefName}")
            ListedTarget(target, pr.title, pr, pr.reviewRequests.any { it.login != null && it.login == me })
        }
    }

    /** Progress of a review this reviewer started before, counted in its own saved scope; null when never started. */
    fun progress(target: ReviewTarget): ReviewProgress? {
        val meta = ReviewMeta.load(git, target.key)?.takeIf { it.reviewed.isNotEmpty() } ?: return null
        val changes = runCatching { git.changes(meta.range) }.getOrNull() ?: return null
        return ReviewProgress(changes.count { meta.reviewed[it.path] == it.newBlob }, changes.size)
    }

    private fun branches(): List<ListedTarget> {
        val base = git.defaultBase()
        return git.remoteBranches().filter { it != base }.map {
            val target = ReviewTarget(it.removePrefix("${GitClient.REMOTE}/").replace(Regex("[^A-Za-z0-9._-]"), "_"), it, null, it, base)
            ListedTarget(target, it.removePrefix("${GitClient.REMOTE}/"), null, false)
        }
    }
}

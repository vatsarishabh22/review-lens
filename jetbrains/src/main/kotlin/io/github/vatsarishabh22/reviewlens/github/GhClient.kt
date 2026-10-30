package io.github.vatsarishabh22.reviewlens.github

import io.github.vatsarishabh22.reviewlens.*
import java.nio.file.Files
import java.nio.file.Path

/** GitHub through the `gh` CLI, the host of a checkout whose `origin` is on a host `gh` is signed in to ([ReviewHosts]). */
class GhClient(private val root: Path) : ReviewHost, ViewedSync {
    override val name = "GitHub"

    /** `gh` is signed in to [host] (github.com, or a GitHub Enterprise server). */
    fun signedInTo(host: String): Boolean = runCatching { runCli(root, "gh", "auth", "status", "--hostname", host) }.isSuccess

    override val viewed: ViewedSync get() = this

    override fun headRef(number: Int) = "pull/$number/head"

    private fun viewerLogin(): String = runCli(root, "gh", "api", "user", "--jq", ".login")

    /** Open PRs without their checks: checks double the call's time (2.65 s vs 5.43 s for 70 PRs) and only the detail shows them. */
    override fun openPullRequests(): List<PullRequest> {
        val json = runCli(root, "gh", "pr", "list", "--state", "open", "--limit", "100", "--json",
            "number,title,headRefName,headRefOid,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests")
        return parsePullRequests(json, runCatching { viewerLogin() }.getOrNull())
    }

    /** This viewer's GitHub "Viewed" ticks for PR [number], every page; owner and repo come from the checkout's remote. */
    override fun viewedStates(number: Int): ViewedStates = parseViewedStates(runCli(root, "gh", "api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$VIEWED_QUERY", "--jq", VIEWED_JQ))

    /** Everyone's review threads on PR [number], every page. */
    override fun reviewThreads(number: Int): List<ReviewThread> = parseReviewThreads(runCli(root, "gh", "api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$REVIEW_THREADS_QUERY", "--jq", REVIEW_THREADS_JQ))

    /** Ticks or unticks one file on GitHub (private to the viewer; nobody is notified). */
    override fun setViewed(reviewId: String, path: String, viewed: Boolean) {
        val mutation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
        runCli(root, "gh", "api", "graphql", "-f", "id=$reviewId", "-f", "path=$path",
            "-f", "query=mutation(\$id:ID!,\$path:String!){$mutation(input:{pullRequestId:\$id,path:\$path}){clientMutationId}}")
    }

    /** The PR's id and latest push, and your own pending review on it (started on github.com or anywhere else), if any. */
    override fun reviewSlot(number: Int): GitHubSlot = parseReviewSlot(runCli(root, "gh", "api", "graphql", "-F", "owner={owner}", "-F", "name={repo}",
        "-F", "number=$number", "-f", "query=$REVIEW_SLOT_QUERY", "--jq", REVIEW_SLOT_JQ))

    /**
     * Posts one review: the new [comments] and the [replies] go into your pending review, which is then submitted with
     * [verdict] and [summary]. GitHub keeps one pending review per person per PR, so one started on github.com is
     * adopted (its comments post too) rather than refused. A refusal at any step undoes only what this call added, so
     * nothing posts by halves and nothing written on github.com is lost, and throws GitHub's reason.
     */
    override fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>) {
        val slot = reviewSlot(number)
        slot.refusal(verdict, summary, Outbox(comments, replies), commit)?.let { throw CliException(it) }
        val adopted = slot.pending?.id
        val review = adopted ?: graphql(startReviewBody(slot.id, commit, comments), ".data.addPullRequestReview.pullRequestReview.id")
        val added = mutableListOf<String>()
        try {
            if (adopted != null) comments.forEach { added += graphql(addThreadBody(review, it), ".data.addPullRequestReviewThread.thread.comments.nodes[0].id") }
            replies.forEach { added += graphql(replyBody(review, it), ".data.addPullRequestReviewThreadReply.comment.id") }
            graphql(submitBody(review, verdict, summary), ".data.submitPullRequestReview.pullRequestReview.id")
        } catch (e: CliException) {
            if (adopted == null) runCatching { graphql(deleteReviewBody(review), ".data.deletePullRequestReview.pullRequestReview.id") }
            else added.forEach { runCatching { graphql(deleteCommentBody(it), ".data.deletePullRequestReviewComment.pullRequestReview.id") } }
            throw e
        }
    }

    override fun resolveThread(threadId: String, resolved: Boolean) {
        val field = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        graphql(resolveBody(threadId, resolved), ".data.$field.thread.id")
    }

    override fun editComment(commentId: String, body: String) {
        graphql(editCommentBody(commentId, body), ".data.updatePullRequestReviewComment.pullRequestReviewComment.id")
    }

    override fun deleteComment(commentId: String) {
        graphql(deleteCommentBody(commentId), ".data.deletePullRequestReviewComment.pullRequestReview.id")
    }

    /** Sends one GraphQL [body] and returns what [jq] picks from the answer; a refusal throws with GitHub's reason. */
    private fun graphql(body: String, jq: String): String {
        val file = Files.createTempFile("review-lens-graphql", ".json")
        try {
            Files.writeString(file, body)
            return runCli(root, "gh", "api", "graphql", "--input", file.toString(), "--jq", jq).ifBlank { null }?.takeUnless { it == "null" }
                ?: throw CliException("GitHub didn't answer")
        } catch (e: CliException) {
            throw CliException(githubRefusal(e.stdout) ?: e.stderr.ifBlank { "GitHub didn't answer" })
        } finally {
            Files.deleteIfExists(file)
        }
    }

    override fun checks(number: Int): Checks =
        summarizeChecks(runCli(root, "gh", "pr", "view", number.toString(), "--json", "statusCheckRollup"))
}

/** Your pending review on a PR: GitHub keeps one per person, so Finish review adds to it rather than starting another. */
data class PendingReview(val id: String, val comments: Int)

/** GitHub's [ReviewSlot]: the PR's id, its latest push ([head]), your pending review if one exists, and whether you opened the PR. */
data class GitHubSlot(val id: String, val head: String, val pending: PendingReview?, val ownPullRequest: Boolean) : ReviewSlot {
    override val started get() = pending?.comments ?: 0

    /** GitHub refuses Approve and Request changes on your own pull request. */
    override fun denied(verdict: Verdict) =
        if (ownPullRequest && verdict != Verdict.COMMENT) "GitHub doesn't let you approve or request changes on your own pull request." else null

    /**
     * New comments can't join [pending] from a scope ending before [head]: what is added to a pending review lands on the
     * PR's latest push whatever line it was written for (measured on rl-thread-test#1). Replies sit on their threads and always can.
     */
    override fun held(outbox: Outbox, scopeEnd: String) = if (pending == null || outbox.comments.isEmpty() || scopeEnd == head) null
        else "You have a review in progress on GitHub, and comments added to it land on the latest push. Review up to the latest push to add yours, or submit that one on GitHub first."

    /** Then [denied], then GitHub's 422 on an empty Comment or Request changes (a blank reason, measured on PR #1). */
    override fun refusal(verdict: Verdict, summary: String, outbox: Outbox, scopeEnd: String) = held(outbox, scopeEnd) ?: denied(verdict)
        ?: if (verdict != Verdict.APPROVE && summary.isBlank() && outbox.size + started == 0) "Add a summary or an inline comment to post a ${verdict.label.lowercase()} review." else null
}

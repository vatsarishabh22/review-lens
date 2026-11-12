package io.github.vatsarishabh22.reviewlens.github

import com.intellij.openapi.application.ApplicationManager
import io.github.vatsarishabh22.reviewlens.*
import java.nio.file.Files
import java.nio.file.Path

/** GitHub through the `gh` CLI, the host of a checkout whose `origin` is on a host `gh` is signed in to ([ReviewHosts]). */
class GhClient(private val root: Path) : ReviewHost, ViewedSync {
    override val name = "GitHub"

    /** `gh` is signed in to [host] (github.com, or a GitHub Enterprise server). */
    fun signedInTo(host: String): Boolean = runCatching { gh("auth", "status", "--hostname", host) }.isSuccess

    override val viewed: ViewedSync get() = this

    override fun headRef(number: Int) = "pull/$number/head"

    private fun viewerLogin(): String = gh("api", "user", "--jq", ".login")

    /**
     * Open PRs without their checks: checks double the call's time (2.65 s vs 5.43 s for 70 PRs) and only the detail shows
     * them. Their commits come from a second call run alongside (1.4 to 1.8 s for 22 PRs, measured); when it fails the
     * list shows no stacks.
     */
    override fun openPullRequests(): List<PullRequest> {
        val commits = ApplicationManager.getApplication().executeOnPooledThread<Map<Int, List<String>>> { runCatching { parseCommitIds(gh("api", "graphql",
            "-F", "owner={owner}", "-F", "name={repo}", "-f", "query=$COMMIT_IDS_QUERY", "--jq", COMMIT_IDS_JQ)) }.getOrNull().orEmpty() }
        val json = gh("pr", "list", "--state", "open", "--limit", "100", "--json",
            "number,title,headRefName,headRefOid,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests,isCrossRepository")
        val prs = parsePullRequests(json, runCatching { viewerLogin() }.getOrNull())
        val ids = commits.get()
        // a failed lookup, or a PR it didn't list, leaves the commits unknown: never "no commits", which reads as "no parent"
        return prs.map { it.copy(commits = ids[it.number]) }
    }

    /** This viewer's GitHub "Viewed" ticks for PR [number], every page; owner and repo come from the checkout's remote. */
    override fun viewedStates(number: Int): ViewedStates = parseViewedStates(gh("api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$VIEWED_QUERY", "--jq", VIEWED_JQ, timeoutMs = VIEWED_TIMEOUT_MS))

    /** Everyone's review threads on PR [number], every page. */
    override fun reviewThreads(number: Int): List<ReviewThread> = parseReviewThreads(gh("api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$REVIEW_THREADS_QUERY", "--jq", REVIEW_THREADS_JQ))

    /** Ticks or unticks files on GitHub in one call (private to the viewer; nobody is notified). */
    override fun setViewed(reviewId: String, ticks: Map<String, Boolean>) {
        // ticks go one call at a time (HostViewed): a call that hangs must not hold back the ones after it
        if (ticks.isNotEmpty()) graphql(viewedBody(reviewId, ticks), ".data | length", VIEWED_TIMEOUT_MS)
    }

    /** The PR's id and latest push, and your own pending review on it (started on github.com or anywhere else), if any. */
    override fun reviewSlot(number: Int): GitHubSlot = parseReviewSlot(gh("api", "graphql", "-F", "owner={owner}", "-F", "name={repo}",
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
        var review = adopted
        val added = mutableListOf<String>()
        var submitting = false
        try {
            review = review ?: graphql(startReviewBody(slot.id, commit, comments), ".data.addPullRequestReview.pullRequestReview.id")
            if (adopted != null) comments.forEach { added += graphql(addThreadBody(review, it), ".data.addPullRequestReviewThread.thread.comments.nodes[0].id") }
            replies.forEach { added += graphql(replyBody(review, it), ".data.addPullRequestReviewThreadReply.comment.id") }
            submitting = true
            graphql(submitBody(review, verdict, summary), ".data.submitPullRequestReview.pullRequestReview.id")
        } catch (e: CliException) {
            // a call stopped unanswered may still land after any read: a read proves what landed, never what didn't
            val expected = (slot.pending?.comments ?: comments.size) + added.size
            val posted = when {
                !e.timedOut -> false
                review == null -> null // the start hung: a review it may still make has no id to undo by
                else -> timedOutPost(submitting, adopted == null, expected, review.let { id -> runCatching { reviewRead(id) }.getOrNull() })
            }
            when (posted) {
                true -> return
                null -> throw CliException(CANT_TELL)
                false -> Unit
            }
            // undone up to the first delete that fails: what it leaves would be adopted by the next Finish and post twice
            val undone = if (adopted == null) review?.let { delete(deleteReviewBody(it), ".data.deletePullRequestReview.pullRequestReview.id") } ?: true
                else added.all { delete(deleteCommentBody(it), ".data.deletePullRequestReviewComment.pullRequestReview.id") }
            throw when {
                !undone -> CliException(CANT_TELL)
                e.timedOut -> CliException("GitHub didn't answer in time. Nothing was posted")
                else -> e
            }
        }
    }

    /** One review read by its id: its state and comment count, replies included; a review that is gone fails the read. */
    private fun reviewRead(id: String): ReviewRead = parseReviewRead(gh("api", "graphql", "-F", "id=$id", "-f", "query=$REVIEW_READ_QUERY", "--jq", REVIEW_READ_JQ))

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

    /** A read from GitHub through `gh`, stopped after [timeoutMs] so a hung call never holds a step waiting on it (spec rule 7). */
    private fun gh(vararg args: String, timeoutMs: Int = READ_TIMEOUT_MS) = runCli(root, "gh", *args, timeoutMs = timeoutMs)

    /** Whether a delete GraphQL [body] went through. */
    private fun delete(body: String, jq: String) = runCatching { graphql(body, jq) }.isSuccess

    /** Sends one GraphQL [body] and returns what [jq] picks from the answer; a refusal throws with GitHub's reason. */
    private fun graphql(body: String, jq: String, timeoutMs: Int = WRITE_TIMEOUT_MS): String {
        val file = Files.createTempFile("review-lens-graphql", ".json")
        try {
            Files.writeString(file, body)
            return runCli(root, "gh", "api", "graphql", "--input", file.toString(), "--jq", jq, timeoutMs = timeoutMs).ifBlank { null }?.takeUnless { it == "null" }
                ?: throw CliException("GitHub didn't answer")
        } catch (e: CliException) {
            throw CliException(githubRefusal(e.stdout) ?: e.stderr.ifBlank { "GitHub didn't answer" }, timedOut = e.timedOut)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    // ponytail: GitHub lists up to 300 files in a compare; a stacked PR adding more counts 300 in the list
    override fun changedFiles(from: String, to: String): Int =
        gh("api", "repos/{owner}/{repo}/compare/$from...$to", "--jq", ".files | length").trim().toInt()

    override fun checks(number: Int): Checks =
        summarizeChecks(gh("pr", "view", number.toString(), "--json", "statusCheckRollup"))
}

/** One of your reviews as GitHub holds it: still [pending] or submitted, and its comments, replies included (measured on rl-thread-test#1). */
data class ReviewRead(val pending: Boolean, val comments: Int)

/**
 * What a Finish post stopped unanswered did to the review it started ([fresh]) or adopted, from one read of that review by
 * its id ([now]; null when the read failed or the review is gone). The call may still land after the read, so the read
 * proves only what landed: true when the call that hung was the submit and the review is submitted holding the
 * [expected] comments. False (undo) only for a review it started, still pending: deleted by its id, a late call to it
 * fails. Anything else, an adopted review above all (a late call would land after any undo), is null: can't tell.
 */
fun timedOutPost(submitting: Boolean, fresh: Boolean, expected: Int, now: ReviewRead?): Boolean? = when {
    now == null -> null
    now.pending -> if (fresh) false else null
    submitting && now.comments == expected -> true
    else -> null
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

/** What a Finish post says when it can't tell what reached GitHub: the drafts stay, and the pull request is the place to look. */
private const val CANT_TELL = "Review Lens can't tell what reached GitHub. Open the pull request on GitHub before you finish again"

/** A write: a minute, past any answer GitHub still gives; stopped, what it did is read back before anything is undone. */
private const val WRITE_TIMEOUT_MS = 60_000

/** A read: well past the slowest measured (a 70-PR list, 5.4 s), and short enough that a hung one frees what waits on it. */
private const val READ_TIMEOUT_MS = 30_000

/** 8x the slowest Viewed call measured (300 files in one call, 2.5 s). */
private const val VIEWED_TIMEOUT_MS = 20_000

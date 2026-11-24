package io.github.vatsarishabh22.reviewlens.core

/**
 * The code host a review talks to: its open reviews, threads and one-call Finish (spec/README.md; GitLab's is
 * spec/gitlab.md). GitHub's is `host/github/GhClient`. Everything else in a review is plain git and never asks the host.
 * Calls run a CLI and block: call off EDT.
 */
interface ReviewHost {
    /** How the UI names the host: "Submit to GitHub". */
    val name: String

    fun openPullRequests(): List<PullRequest>

    /** The refspec source that fetches review [number]'s head (`pull/7/head` on GitHub). */
    fun headRef(number: Int): String

    fun reviewThreads(number: Int): List<ReviewThread>

    /** What the host will take on review [number] now; read again each time Finish opens. */
    fun reviewSlot(number: Int): ReviewSlot

    /** Posts [comments], [replies], [summary] and [verdict] as one review at [commit]; a refusal undoes what it added and throws the host's reason. */
    fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>)

    fun checks(number: Int): Checks

    /** How many files [to] changes on top of [from] (two commits), counted by the host without fetching either. */
    fun changedFiles(from: String, to: String): Int

    /** Resolves thread [threadId], or reopens it, on the host at once; a refusal throws the host's reason. */
    fun resolveThread(threadId: String, resolved: Boolean)

    /** Replaces posted comment [commentId]'s text, or deletes it, on the host at once; a refusal throws the host's reason. */
    fun editComment(commentId: String, body: String)
    fun deleteComment(commentId: String)

    /** The host's own "Viewed" checkboxes, or null where it keeps none to sync with (GitLab): ticks then stay on this laptop. */
    val viewed: ViewedSync?
}

/**
 * The host's answer to "what will you take on this review now": the rules, their data and their words are the host's
 * own (GitHub's: [GitHubSlot]). Pure, so the Finish popup can ask on every keystroke on EDT.
 */
interface ReviewSlot {
    /** Comments the viewer began on the host's website, which Finish posts with theirs. */
    val started: Int

    /** Why [verdict] can't end this review whatever is written (greys its choice), or null. */
    fun denied(verdict: Verdict): String?

    /** Why the [outbox] can't post from a scope ending at [scopeEnd] whatever the verdict (said in place of "will be posted"), or null. */
    fun held(outbox: Outbox, scopeEnd: String): String?

    /** Why the host won't take this review as it is now (greys Submit), or null. */
    fun refusal(verdict: Verdict, summary: String, outbox: Outbox, scopeEnd: String): String?
}

enum class ChecksState { NONE, RUNNING, FAILING, PASSED }

data class Checks(val state: ChecksState, val failing: Int = 0) {
    val text get() = when (state) {
        ChecksState.NONE -> ""
        ChecksState.RUNNING -> "Checks running"
        ChecksState.FAILING -> if (failing == 1) "1 check failing" else "$failing checks failing"
        ChecksState.PASSED -> "Checks passed"
    }
}

/** GitHub's own per-viewer state of a PR file; DISMISSED = "new changes since last viewed" (our 🔄). */
enum class ViewedState { VIEWED, UNVIEWED, DISMISSED }

data class ViewedStates(val pullRequestId: String, val files: Map<String, ViewedState>)

/** A host's per-viewer "Viewed" checkboxes on a review's files. */
interface ViewedSync {
    fun viewedStates(number: Int): ViewedStates
    /** Ticks (true) or unticks each file of [ticks] in one call; none is no call. A refusal throws and may leave some applied: sending again is harmless. */
    fun setViewed(reviewId: String, ticks: Map<String, Boolean>)
}

/** Someone on the host as the UI shows them: [name] in text, [login] seeds their avatar. */
data class Person(val login: String, val name: String)

/**
 * An open review on the host in the host's neutral words; each host maps its own answer to it. [head] is the branch
 * under review and [headSha] its commit on the host now (a review whose head moved on shows "New commits since your
 * review"); [requested]: the host asks the viewer to review it ("Waiting on you"); [commits]: its commit ids, oldest
 * first, for finding its stack (null when the host didn't say: its lookup failed, or the PR is past what it lists);
 * [fromFork]: opened from another repository, so its [head] is a branch there, never one this repository's PRs can be
 * based on.
 */
data class PullRequest(
    val number: Int, val title: String, val head: String, val headSha: String, val base: String, val author: Person,
    val body: String = "", val additions: Int = 0, val deletions: Int = 0, val changedFiles: Int = 0, val updatedAt: String = "",
    val requested: Boolean = false, val commits: List<String>? = null, val fromFork: Boolean = false,
)

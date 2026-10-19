package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson

/**
 * An inline comment written during a review. [line] (1-based) counts in the file version [blob], the one the scope
 * showed, so a draft stays on the line it was written for. [posted] once Finish review sent it ("on GitHub").
 * A reply has [replyTo], the GitHub thread it answers; its path, line and blob are the thread's, for listing it.
 */
data class Draft(val path: String, val line: Int, val blob: String, val body: String, val posted: Boolean = false, val replyTo: String? = null)

/** The three ways GitHub lets a review end; [name] is GitHub's own `event` value, [done] how the PR list says it was posted, [posted] how the finish message does. */
enum class Verdict(val label: String, val hint: String, val done: String, val posted: String) {
    COMMENT("Comment", "Feedback without a verdict", "commented", "Commented"),
    APPROVE("Approve", "Ready to merge", "approved", "Approved"),
    REQUEST_CHANGES("Request changes", "Must be addressed before merging", "changes requested", "Requested changes"),
}

/**
 * Why Submit is greyed out, or null when GitHub will take the review. GitHub refuses Approve and Request changes on
 * your own pull request, and an empty Comment or Request changes (a 422 with a blank reason, measured on PR #1).
 */
fun submitBlocker(verdict: Verdict, summary: String, comments: Int, ownPullRequest: Boolean): String? = when {
    ownPullRequest && verdict != Verdict.COMMENT -> "GitHub doesn't let you approve or request changes on your own pull request."
    verdict != Verdict.APPROVE && summary.isBlank() && comments == 0 -> "Add a summary or an inline comment to post a ${verdict.label.lowercase()} review."
    else -> null
}

/** The comments Finish review sends: pending ones written on the file versions this scope shows; the rest wait for their scope. */
fun toPost(drafts: List<Draft>, changes: List<FileChange>): List<Draft> {
    val blobs = changes.associate { it.path to it.newBlob }
    return drafts.filter { !it.posted && it.replyTo == null && blobs[it.path] == it.blob }
}

/** The replies Finish review sends: every pending one whose thread GitHub still has ([threads] null: not known, so try). */
fun repliesToPost(drafts: List<Draft>, threads: List<ReviewThread>?): List<Draft> =
    drafts.filter { draft -> !draft.posted && draft.replyTo != null && (threads == null || threads.any { it.id == draft.replyTo }) }

/** What Finish review would post now; the bar's badge, the popup and the post itself all read this one value. */
data class Outbox(val comments: List<Draft>, val replies: List<Draft>) {
    val size get() = comments.size + replies.size
    val text get() = draftsText(comments.size, replies.size)
}

/** "2 comments and 1 reply", or "" when there are neither. */
fun draftsText(comments: Int, replies: Int): String = listOfNotNull(
    comments.takeIf { it > 0 }?.let { "$it comment${if (it > 1) "s" else ""}" },
    replies.takeIf { it > 0 }?.let { "$it repl${if (it > 1) "ies" else "y"}" },
).joinToString(" and ")

/** What Exit says it kept: " 1 comment and 1 reply kept as drafts for next time.", or "" when nothing is waiting. */
fun keptText(drafts: List<Draft>): String {
    val waiting = drafts.filter { !it.posted }
    val text = draftsText(waiting.count { it.replyTo == null }, waiting.count { it.replyTo != null })
    return if (text.isEmpty()) "" else " $text kept as drafts for next time."
}

/** One GraphQL call as `gh api graphql --input` takes it. */
fun graphqlBody(query: String, variables: Map<String, Any?>): String = Gson().toJson(mapOf("query" to query, "variables" to variables))

/** Starts the reviewer's pending review at [commit] with the new [comments]; GitHub answers with the review's id. */
fun startReviewBody(pullRequestId: String, commit: String, comments: List<Draft>) = graphqlBody(START_REVIEW, mapOf("pr" to pullRequestId, "commit" to commit,
    "threads" to comments.map { mapOf("path" to it.path, "line" to it.line, "side" to "RIGHT", "body" to it.body) }))
fun replyBody(reviewId: String, reply: Draft) = graphqlBody(ADD_REPLY, mapOf("review" to reviewId, "thread" to reply.replyTo, "body" to reply.body))
fun submitBody(reviewId: String, verdict: Verdict, summary: String) = graphqlBody(SUBMIT_REVIEW, mapOf("review" to reviewId, "event" to verdict.name, "body" to summary.ifBlank { null }))
fun deleteReviewBody(reviewId: String) = graphqlBody(DELETE_REVIEW, mapOf("review" to reviewId))

private const val START_REVIEW = "mutation(\$pr:ID!,\$commit:GitObjectID!,\$threads:[DraftPullRequestReviewThread]){addPullRequestReview(input:{pullRequestId:\$pr,commitOID:\$commit,threads:\$threads}){pullRequestReview{id}}}"
private const val ADD_REPLY = "mutation(\$review:ID!,\$thread:ID!,\$body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewId:\$review,pullRequestReviewThreadId:\$thread,body:\$body}){comment{id}}}"
private const val SUBMIT_REVIEW = "mutation(\$review:ID!,\$event:PullRequestReviewEvent!,\$body:String){submitPullRequestReview(input:{pullRequestReviewId:\$review,event:\$event,body:\$body}){pullRequestReview{id}}}"
private const val DELETE_REVIEW = "mutation(\$review:ID!){deletePullRequestReview(input:{pullRequestReviewId:\$review}){pullRequestReview{id}}}"

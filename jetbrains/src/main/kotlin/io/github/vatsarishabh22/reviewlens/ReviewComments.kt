package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson

/**
 * An inline comment written during a review. [line] (1-based) counts in the file version [blob], the one the scope
 * showed, so a draft stays on the line it was written for. [posted] once Finish review sent it ("on <host>").
 * A reply has [replyTo], the GitHub thread it answers; its path, line and blob are the thread's, for listing it.
 */
data class Draft(val path: String, val line: Int, val blob: String, val body: String, val posted: Boolean = false, val replyTo: String? = null, val startLine: Int? = null)

/** Where a comment sits, as the lists say it: `line 15`, or `lines 15–17` for one on several lines. */
fun span(startLine: Int?, line: Int) = if (startLine == null) "line $line" else "lines $startLine–$line"

/** GitHub takes a comment on several lines only inside one block of the diff: every line within 3 of a [changed] one (1-based). */
fun inOneDiffBlock(changed: Set<Int>, from: Int, to: Int) = (from..to).all { n -> changed.any { kotlin.math.abs(it - n) <= 3 } }

/** A comment's body in order: its text, and each ` ```suggestion ` block's lines (GitHub's "suggested change"; none = delete the lines). */
sealed interface BodyPart {
    data class Text(val markdown: String) : BodyPart
    data class Suggestion(val lines: List<String>) : BodyPart
}

fun bodyParts(body: String): List<BodyPart> {
    val parts = mutableListOf<BodyPart>()
    var at = 0
    fun text(until: Int) = body.substring(at, until).trim().takeIf { it.isNotEmpty() }?.let { parts += BodyPart.Text(it) }
    for (m in SUGGESTION.findAll(body)) {
        text(m.range.first)
        parts += BodyPart.Suggestion(m.groupValues[1].takeIf { it.isNotEmpty() }?.split('\n').orEmpty())
        at = m.range.last + 1
    }
    text(body.length)
    return parts
}

/** The block Suggest a change puts in the box: the covered [lines] as they are now, for the reviewer to edit. */
fun suggestionBlock(lines: List<String>) = "```suggestion\n" + lines.joinToString("\n") + "\n```"

private val SUGGESTION = Regex("```suggestion[^\\n]*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)

/** The three ways a review can end; [done] how the PR list says it was posted, [posted] how the finish message does. */
enum class Verdict(val label: String, val hint: String, val done: String, val posted: String) {
    COMMENT("Comment", "Feedback without a verdict", "commented", "Commented"),
    APPROVE("Approve", "Ready to merge", "approved", "Approved"),
    REQUEST_CHANGES("Request changes", "Must be addressed before merging", "changes requested", "Requested changes"),
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
    "threads" to comments.map { mapOf("path" to it.path, "line" to it.line, "side" to "RIGHT", "body" to it.body) + range(it) }))
/** A comment on several lines also names its first line, on the same side. */
private fun range(comment: Draft) = comment.startLine?.let { mapOf("startLine" to it, "startSide" to "RIGHT") }.orEmpty()
fun replyBody(reviewId: String, reply: Draft) = graphqlBody(ADD_REPLY, mapOf("review" to reviewId, "thread" to reply.replyTo, "body" to reply.body))
fun submitBody(reviewId: String, verdict: Verdict, summary: String) = graphqlBody(SUBMIT_REVIEW, mapOf("review" to reviewId, "event" to githubEvent(verdict), "body" to summary.ifBlank { null }))
/** GitHub's own `PullRequestReviewEvent` for a verdict, spelled here so renaming a [Verdict] can't change what GitHub is sent. */
private fun githubEvent(verdict: Verdict) = when (verdict) { Verdict.COMMENT -> "COMMENT"; Verdict.APPROVE -> "APPROVE"; Verdict.REQUEST_CHANGES -> "REQUEST_CHANGES" }
fun deleteReviewBody(reviewId: String) = graphqlBody(DELETE_REVIEW, mapOf("review" to reviewId))
/** Adds a new comment to a pending review someone else started; GitHub anchors it at the PR's latest push (it takes no commit). */
fun addThreadBody(reviewId: String, comment: Draft) = graphqlBody(ADD_THREAD, mapOf("review" to reviewId, "path" to comment.path, "line" to comment.line, "body" to comment.body) + range(comment))
fun deleteCommentBody(commentId: String) = graphqlBody(DELETE_COMMENT, mapOf("comment" to commentId))
/** Replaces a posted comment's text on GitHub at once. */
fun editCommentBody(commentId: String, body: String) = graphqlBody(UPDATE_COMMENT, mapOf("comment" to commentId, "body" to body))
/** Resolves a thread, or reopens it ([resolved] false), on GitHub at once: GitHub can't hold it in a pending review. */
fun resolveBody(threadId: String, resolved: Boolean) = graphqlBody(if (resolved) RESOLVE_THREAD else UNRESOLVE_THREAD, mapOf("thread" to threadId))

private const val START_REVIEW = "mutation(\$pr:ID!,\$commit:GitObjectID!,\$threads:[DraftPullRequestReviewThread]){addPullRequestReview(input:{pullRequestId:\$pr,commitOID:\$commit,threads:\$threads}){pullRequestReview{id}}}"
private const val ADD_REPLY = "mutation(\$review:ID!,\$thread:ID!,\$body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewId:\$review,pullRequestReviewThreadId:\$thread,body:\$body}){comment{id}}}"
private const val SUBMIT_REVIEW = "mutation(\$review:ID!,\$event:PullRequestReviewEvent!,\$body:String){submitPullRequestReview(input:{pullRequestReviewId:\$review,event:\$event,body:\$body}){pullRequestReview{id}}}"
private const val ADD_THREAD = "mutation(\$review:ID!,\$path:String!,\$line:Int!,\$startLine:Int,\$startSide:DiffSide,\$body:String!){addPullRequestReviewThread(input:{pullRequestReviewId:\$review,path:\$path,line:\$line,side:RIGHT,startLine:\$startLine,startSide:\$startSide,body:\$body}){thread{comments(first:1){nodes{id}}}}}"
private const val DELETE_COMMENT = "mutation(\$comment:ID!){deletePullRequestReviewComment(input:{id:\$comment}){pullRequestReview{id}}}"
private const val UPDATE_COMMENT = "mutation(\$comment:ID!,\$body:String!){updatePullRequestReviewComment(input:{pullRequestReviewCommentId:\$comment,body:\$body}){pullRequestReviewComment{id}}}"
private const val RESOLVE_THREAD = "mutation(\$thread:ID!){resolveReviewThread(input:{threadId:\$thread}){thread{id}}}"
private const val UNRESOLVE_THREAD = "mutation(\$thread:ID!){unresolveReviewThread(input:{threadId:\$thread}){thread{id}}}"
private const val DELETE_REVIEW = "mutation(\$review:ID!){deletePullRequestReview(input:{pullRequestReviewId:\$review}){pullRequestReview{id}}}"

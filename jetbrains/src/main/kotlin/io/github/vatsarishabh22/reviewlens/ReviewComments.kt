package io.github.vatsarishabh22.reviewlens


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

/** Your comments (not replies) the lists show: pending ones, and posted ones while the host's [threads] aren't loaded. */
fun ownComments(drafts: List<Draft>, threads: List<ReviewThread>?): List<Draft> = drafts.filter { it.replyTo == null && (!it.posted || threads == null) }

/** Your pending replies to [thread], shown inside its card and under its row. */
fun pendingReplies(drafts: List<Draft>, thread: ReviewThread): List<Draft> = drafts.filter { !it.posted && it.replyTo == thread.id }

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

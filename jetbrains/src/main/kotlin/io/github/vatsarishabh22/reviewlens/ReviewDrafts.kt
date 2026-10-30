package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** The review's draft comments and summary, and posting them; the drafts are saved in [ReviewMeta.drafts] through [ReviewService.update]. */
@Service(Service.Level.PROJECT)
class ReviewDrafts(private val project: Project) {

    private val review get() = ReviewService.of(project)

    /** The Finish review summary while it is being written; cleared once the review is posted. */
    @Volatile var summary = ""

    /** Comments on [change]'s file as this scope shows it; posted ones stay visible as "on GitHub" until GitHub's own threads are loaded. */
    fun on(change: FileChange): List<Draft> = mine().filter { it.path == change.path && it.blob == change.newBlob }

    /** Every comment (not reply) of this review the panel lists: pending ones, and posted ones while GitHub's threads aren't loaded. */
    fun mine(): List<Draft> = review.meta?.drafts.orEmpty().filter { it.replyTo == null && (!it.posted || review.threads == null) }

    /** Your pending replies to [thread], shown inside its card. */
    fun repliesTo(thread: ReviewThread): List<Draft> = review.meta?.drafts.orEmpty().filter { !it.posted && it.replyTo == thread.id }

    /** What Finish review would post now: comments on the versions this scope shows, and replies to threads GitHub still has. */
    fun outbox(): Outbox = review.meta?.drafts.orEmpty().let { Outbox(toPost(it, review.changes), repliesToPost(it, review.threads)) }

    fun add(change: FileChange, line: Int, body: String, startLine: Int? = null) = update { it + Draft(change.path, line, change.newBlob, body, startLine = startLine) }

    /** A reply to [thread], shown under [change]'s line [line]; posted with the review, into the thread. */
    fun reply(change: FileChange, line: Int, thread: ReviewThread, body: String) = update { it + Draft(change.path, line, change.newBlob, body, replyTo = thread.id) }

    /** Replaces a pending comment's text, keeping its line and its place among the drafts. */
    fun edit(draft: Draft, body: String) = update { drafts -> drafts.map { if (it == draft) it.copy(body = body) else it } }

    /** Removes a pending comment; the way out when GitHub refuses one, which would otherwise block every Finish review. */
    fun delete(draft: Draft) = update { it - draft }

    /**
     * Posts the pending comments and replies with [summary] and [verdict] as one GitHub review, remembers [verdict], and
     * returns what went ("1 comment and 1 reply"). Runs gh: call off EDT.
     */
    fun submit(summary: String, verdict: Verdict): String {
        val meta = review.meta ?: error("No review in progress")
        val number = meta.target.prNumber ?: error("Only a pull request can be reviewed on GitHub")
        val sending = outbox()
        val host = ReviewHosts.required(review.git!!)
        host.submitReview(number, meta.range.to, summary, verdict, sending.comments, sending.replies)
        val sent = (sending.comments + sending.replies).toSet()
        review.update { m -> m.copy(drafts = m.drafts?.map { if (it in sent) it.copy(posted = true) else it }, verdict = verdict) }
        this.summary = ""
        return sending.text
    }

    private fun update(change: (List<Draft>) -> List<Draft>) = review.update { it.copy(drafts = change(it.drafts.orEmpty())) }

    companion object {
        fun of(project: Project): ReviewDrafts = project.service()
    }
}

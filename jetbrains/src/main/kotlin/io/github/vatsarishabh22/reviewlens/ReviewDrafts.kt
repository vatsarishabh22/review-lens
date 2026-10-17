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

    /** Comments on [change]'s file as this scope shows it; posted ones stay visible, marked as on GitHub. */
    fun on(change: FileChange): List<Draft> = review.meta?.drafts.orEmpty().filter { it.path == change.path && it.blob == change.newBlob }

    /** The comments Finish review would post now. */
    fun pending(): List<Draft> = toPost(review.meta?.drafts.orEmpty(), review.changes)

    fun add(change: FileChange, line: Int, body: String) = update { it + Draft(change.path, line, change.newBlob, body) }

    /** Replaces a pending comment's text, keeping its line and its place among the drafts. */
    fun edit(draft: Draft, body: String) = update { drafts -> drafts.map { if (it == draft) it.copy(body = body) else it } }

    /** Removes a pending comment; the way out when GitHub refuses one, which would otherwise block every Finish review. */
    fun delete(draft: Draft) = update { it - draft }

    /** Posts the pending comments with [summary] and [verdict] as one GitHub review, remembers [verdict], and returns how many went. Runs gh: call off EDT. */
    fun submit(summary: String, verdict: Verdict): Int {
        val meta = review.meta ?: error("No review in progress")
        val number = meta.target.prNumber ?: error("Only a pull request can be reviewed on GitHub")
        val sending = toPost(meta.drafts.orEmpty(), review.changes)
        GhClient(review.git!!.root).submitReview(number, reviewPayload(meta.range.to, summary, verdict, sending))
        val sent = sending.toSet()
        review.update { m -> m.copy(drafts = m.drafts?.map { if (it in sent) it.copy(posted = true) else it }, verdict = verdict) }
        this.summary = ""
        return sending.size
    }

    private fun update(change: (List<Draft>) -> List<Draft>) = review.update { it.copy(drafts = change(it.drafts.orEmpty())) }

    companion object {
        fun of(project: Project): ReviewDrafts = project.service()
    }
}

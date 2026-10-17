package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson

/**
 * An inline comment written during a review. [line] (1-based) counts in the file version [blob], the one the scope
 * showed, so a draft stays on the line it was written for. [posted] once Finish review sent it ("on GitHub").
 */
data class Draft(val path: String, val line: Int, val blob: String, val body: String, val posted: Boolean = false)

/** The three ways GitHub lets a review end; [name] is GitHub's own `event` value. */
enum class Verdict(val label: String, val hint: String) {
    COMMENT("Comment", "Feedback without a verdict"),
    APPROVE("Approve", "Ready to merge"),
    REQUEST_CHANGES("Request changes", "Must be addressed before merging"),
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

/** What Finish review sends: pending drafts written on the file versions this scope shows; the rest wait for their scope. */
fun toPost(drafts: List<Draft>, changes: List<FileChange>): List<Draft> {
    val blobs = changes.associate { it.path to it.newBlob }
    return drafts.filter { !it.posted && blobs[it.path] == it.blob }
}

/** GitHub's "create a review" body: one call posts the summary, the verdict and every comment, anchored at [commit]. */
fun reviewPayload(commit: String, summary: String, verdict: Verdict, drafts: List<Draft>): String = Gson().toJson(buildMap {
    put("commit_id", commit)
    put("event", verdict.name)
    if (summary.isNotBlank()) put("body", summary)
    put("comments", drafts.map { mapOf("path" to it.path, "line" to it.line, "side" to "RIGHT", "body" to it.body) })
})

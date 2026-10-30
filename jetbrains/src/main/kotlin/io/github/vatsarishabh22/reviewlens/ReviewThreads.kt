package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson

/** How a thread reads in the list: resolved wins, then outdated (its line is gone on GitHub), else open. */
enum class ThreadState { OPEN, RESOLVED, OUTDATED }

/** One comment of a thread; [canEdit] / [canDelete] when GitHub lets the viewer change it (their own). */
data class ThreadComment(val author: String, val body: String, val id: String = "", val canEdit: Boolean = false, val canDelete: Boolean = false, val mine: Boolean = false)

/**
 * A review thread as GitHub has it. [line] is already moved by GitHub to [commit] (null when outdated); [originalLine]
 * is where it was written. [mine]: the viewer started it.
 */
data class ReviewThread(
    val id: String,
    val path: String,
    val line: Int?,
    val originalLine: Int?,
    val leftSide: Boolean,
    val commit: String?,
    val resolved: Boolean,
    val resolvedBy: String?,
    val url: String,
    val mine: Boolean,
    val comments: List<ThreadComment>,
    /** GitHub lets this viewer resolve it (write access, or the PR's author) / reopen it. */
    val canResolve: Boolean = false,
    val canUnresolve: Boolean = false,
    /** The viewer resolved it (here or on the host's website): a reply waiting on it is then no news to them. */
    val resolvedByMe: Boolean = false,
    /** The first line of a thread on several lines ([line] is the last), as GitHub moved it; null for one line. */
    val startLine: Int? = null,
) {
    /** Resolved by someone else, so a reply the viewer has waiting on it needs saying (it still posts; the thread stays resolved). */
    val resolvedByOther get() = resolved && !resolvedByMe

    val state get() = when { resolved -> ThreadState.RESOLVED; line == null -> ThreadState.OUTDATED; else -> ThreadState.OPEN }

    /** The line it sits under in the file at [scopeEnd], or null when GitHub anchored it elsewhere (spec: Threads). */
    fun lineAt(scopeEnd: String): Int? = line?.takeIf { !leftSide && commit == scopeEnd }

    /** Where the list says it is. */
    val where get() = line?.let { "line $it" } ?: "line $originalLine on an older push"
}

/** A comment's first line of text with its markdown dropped (badges keep their alt text), for one-line previews. */
fun previewLine(markdown: String): String = markdown.lineSequence()
    .map { line ->
        line.replace(Regex("<[^>]+>"), "").replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*`~]+|^\\s*(#+|>|[-+] )\\s*"), "").trim()
    }
    .firstOrNull { it.isNotEmpty() }.orEmpty().take(160)

private class RawThread(
    val id: String = "", val path: String = "", val line: Int? = null, val originalLine: Int? = null, val diffSide: String? = null, val isResolved: Boolean = false, val resolvedBy: String? = null,
    val url: String? = null, val commit: String? = null, val mine: Boolean = false, val comments: List<ThreadComment>? = null,
    val viewerCanResolve: Boolean = false, val viewerCanUnresolve: Boolean = false, val resolvedByMe: Boolean = false, val startLine: Int? = null,
)

/** Reads the one-JSON-object-per-line output of [REVIEW_THREADS_JQ]; threads without comments are dropped. */
fun parseReviewThreads(text: String): List<ReviewThread> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
    val raw = Gson().fromJson(line, RawThread::class.java)
    raw.comments?.takeIf { it.isNotEmpty() }?.let {
        ReviewThread(raw.id, raw.path, raw.line, raw.originalLine, raw.diffSide == "LEFT", raw.commit, raw.isResolved, raw.resolvedBy, raw.url.orEmpty(), raw.mine, it,
            // GitHub answers startLine == line for a one-line thread (measured): only a real range keeps one
            raw.viewerCanResolve, raw.viewerCanUnresolve, raw.resolvedByMe, raw.startLine?.takeIf { raw.line != null && it < raw.line })
    }
}

const val REVIEW_THREADS_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){viewer{login} repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){reviewThreads(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{id path line startLine originalLine diffSide isResolved resolvedBy{login} viewerCanResolve viewerCanUnresolve " +
    "comments(first:50){nodes{id author{login} body url commit{oid} viewerCanUpdate viewerCanDelete}}}}}}}"
const val REVIEW_THREADS_JQ = ".data.viewer.login as \$me | .data.repository.pullRequest.reviewThreads.nodes[] | {id, path, line, startLine, originalLine, diffSide, isResolved, resolvedBy: .resolvedBy.login, resolvedByMe: (.resolvedBy.login == \$me), viewerCanResolve, viewerCanUnresolve, " +
    "url: .comments.nodes[0].url, commit: .comments.nodes[0].commit.oid, mine: (.comments.nodes[0].author.login == \$me), " +
    "comments: [.comments.nodes[] | {id, author: (.author.login // \"ghost\"), body, canEdit: .viewerCanUpdate, canDelete: .viewerCanDelete, mine: (.author.login == \$me)}]} | tojson"

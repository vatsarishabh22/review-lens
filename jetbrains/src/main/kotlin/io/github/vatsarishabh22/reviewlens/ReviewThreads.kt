package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson

/** How a thread reads in the list: resolved wins, then outdated (its line is gone on GitHub), else open. */
enum class ThreadState { OPEN, RESOLVED, OUTDATED }

data class ThreadComment(val author: String, val body: String)

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
) {
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
)

/** Reads the one-JSON-object-per-line output of [REVIEW_THREADS_JQ]; threads without comments are dropped. */
fun parseReviewThreads(text: String): List<ReviewThread> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
    val raw = Gson().fromJson(line, RawThread::class.java)
    raw.comments?.takeIf { it.isNotEmpty() }?.let {
        ReviewThread(raw.id, raw.path, raw.line, raw.originalLine, raw.diffSide == "LEFT", raw.commit, raw.isResolved, raw.resolvedBy, raw.url.orEmpty(), raw.mine, it)
    }
}

const val REVIEW_THREADS_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){viewer{login} repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){reviewThreads(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{id path line originalLine diffSide isResolved resolvedBy{login} " +
    "comments(first:50){nodes{author{login} body url commit{oid}}}}}}}}"
const val REVIEW_THREADS_JQ = ".data.viewer.login as \$me | .data.repository.pullRequest.reviewThreads.nodes[] | {id, path, line, originalLine, diffSide, isResolved, resolvedBy: .resolvedBy.login, " +
    "url: .comments.nodes[0].url, commit: .comments.nodes[0].commit.oid, mine: (.comments.nodes[0].author.login == \$me), " +
    "comments: [.comments.nodes[] | {author: (.author.login // \"ghost\"), body}]} | tojson"

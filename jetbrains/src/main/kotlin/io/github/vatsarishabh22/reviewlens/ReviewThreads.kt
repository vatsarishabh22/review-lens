package io.github.vatsarishabh22.reviewlens


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

package io.github.vatsarishabh22.reviewlens

/** A row's state in the Comments tab: a thread's on the host, or one of your drafts. [CommentLook] gives each its words and colours. */
enum class RemarkKind {
    OPEN, RESOLVED, OUTDATED, POSTED, PENDING;

    companion object {
        fun of(state: ThreadState) = when (state) { ThreadState.OPEN -> OPEN; ThreadState.RESOLVED -> RESOLVED; ThreadState.OUTDATED -> OUTDATED }
    }
}

/**
 * One thread or draft in the Comments tab; [line] is where clicking it goes, null when the version shown has no such line.
 * [pending] are your replies to the thread, [draft] is set when the row is itself your pending draft: both are edited there,
 * since the editor can show a draft only where it has a place (a thread a push moved, a deleted thread, another scope's version).
 */
data class Remark(val path: String, val line: Int?, val who: String, val kind: RemarkKind, val where: String, val replies: Int, val body: String, val mine: Boolean,
    val pending: List<Draft> = emptyList(), val draft: Draft? = null)

/** The Comments tab's filter: each one keeps its own rows. */
enum class RemarkFilter(val label: String, val keeps: (Remark) -> Boolean) {
    ALL("All", { true }),
    OPEN("Open", { it.kind == RemarkKind.OPEN }),
    MINE("Mine", { it.mine || it.pending.isNotEmpty() }),
    PENDING("Pending", { it.kind == RemarkKind.PENDING || it.pending.isNotEmpty() }),
}

/**
 * Every row of the Comments tab for a scope ending at [scopeEnd] (none without one): the host's [threads] (null: not
 * loaded) with your pending replies under them, your comments, and each pending reply whose thread isn't listed, by
 * path, resolved last, then line. [host] names the host in "its thread is gone from …".
 */
fun remarks(threads: List<ReviewThread>?, drafts: List<Draft>, changes: List<FileChange>, scopeEnd: String?, host: String): List<Remark> {
    val end = scopeEnd ?: return emptyList()
    val blobs = changes.associate { it.path to it.newBlob }
    val onHost = threads.orEmpty().map { thread ->
        Remark(thread.path, thread.lineAt(end), if (thread.mine) "You" else thread.comments[0].author, RemarkKind.of(thread.state), thread.where, thread.comments.size - 1,
            thread.comments[0].body, thread.mine, pendingReplies(drafts, thread))
    }
    val comments = ownComments(drafts, threads).map { draft ->
        val current = blobs[draft.path] == draft.blob
        Remark(draft.path, draft.line.takeIf { current }, "You", if (draft.posted) RemarkKind.POSTED else RemarkKind.PENDING,
            span(draft.startLine, draft.line) + if (current) "" else " of an earlier version", 0, draft.body, true, draft = draft.takeUnless { it.posted })
    }
    // a pending reply whose thread isn't listed (deleted on the host, or the threads didn't load) gets its own row
    val ids = threads.orEmpty().map { it.id }.toSet()
    val strays = drafts.filter { !it.posted && it.replyTo != null && it.replyTo !in ids }.map { draft ->
        Remark(draft.path, null, "You", RemarkKind.PENDING, if (threads == null) "reply" else "reply · its thread is gone from $host", 0, draft.body, true, draft = draft)
    }
    return (onHost + comments + strays).sortedWith(compareBy({ it.path }, { it.kind == RemarkKind.RESOLVED }, { it.line ?: Int.MAX_VALUE }))
}

package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The review's threads on the host and the viewer's slot there: read at load, read again before Finish, and changed at
 * once (edit or delete a posted comment, resolve or reopen a thread; spec: posted is public, so these can't wait for
 * Finish). Each write runs off EDT, then the threads are read again so the views show what the host now has. The views
 * only draw: [pending] says what is on its way, [onChanged] when to redraw.
 */
@Service(Service.Level.PROJECT)
class HostThreads internal constructor(
    private val host: () -> ReviewHost,
    /** The review under way's number on the host, or null (none, or a branch review). */
    private val number: () -> Int?,
    private val pool: (() -> Unit) -> Unit,
    private val onEdt: (() -> Unit) -> Unit,
) {
    constructor(project: Project) : this(
        host = { ReviewHosts.required(ReviewService.of(project).git ?: throw CliException("No review in progress")) },
        number = { ReviewService.of(project).meta?.target?.prNumber },
        pool = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        onEdt = { ApplicationManager.getApplication().invokeLater(it, project.disposed) },
    )

    /** Everyone's threads on the review, read at load; null for a branch review or when the host didn't answer. */
    @Volatile var threads: List<ReviewThread>? = null; private set
    /** The viewer's slot as Finish review last read it; null when it wasn't read or the host didn't answer. */
    @Volatile var slot: ReviewSlot? = null; private set

    private val writing = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** What each comment or thread on its way to the host says meanwhile ("Saving…"), by its id. */
    val pending: Map<String, String> get() = HashMap(writing)

    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** At load: the review's threads, or none. The loader redraws; runs the host's CLI: off EDT. */
    fun load() {
        threads = number()?.let(::fetch)
    }

    /**
     * Reads the threads and the viewer's slot again (Finish review does, so a thread resolved or deleted meanwhile, or a
     * review started on the host's website, is said before anything posts). A failed read keeps the threads shown. Off EDT.
     */
    fun reload() {
        val number = number() ?: return
        slot = runCatching { host().reviewSlot(number) }.onFailure { thisLogger().info("Host pending review not read", it) }.getOrNull()
        fetch(number)?.let { threads = it; changed() }
    }

    /** [change]'s threads, each with the line it sits under in the file at [scopeEnd], or null when it is only listed. */
    fun threadsOn(change: FileChange, scopeEnd: String?): List<Pair<ReviewThread, Int?>> {
        val end = scopeEnd ?: return emptyList()
        return threads.orEmpty().filter { it.path == change.path }.map { it to it.lineAt(end) }
    }

    /** [failed] gets the host's refusal on EDT, so the caller can reopen the box with [body]: nothing typed is lost. */
    fun edit(comment: ThreadComment, body: String, failed: (Throwable) -> Unit) = write(comment.id, "Saving…", failed) { it.editComment(comment.id, body) }

    fun delete(comment: ThreadComment, failed: (Throwable) -> Unit) = write(comment.id, "Deleting…", failed) { it.deleteComment(comment.id) }

    fun resolve(thread: ReviewThread, resolved: Boolean, failed: (Throwable) -> Unit) =
        write(thread.id, if (resolved) "Resolving…" else "Unresolving…", failed) { it.resolveThread(thread.id, resolved) }

    private fun fetch(number: Int): List<ReviewThread>? =
        runCatching { host().reviewThreads(number) }.onFailure { thisLogger().info("Host review threads not loaded", it) }.getOrNull()

    private fun write(id: String, saying: String, failed: (Throwable) -> Unit, call: (ReviewHost) -> Unit) {
        writing[id] = saying
        changed()
        pool {
            val sent = runCatching { call(host()) }
            reload()
            onEdt {
                writing -= id
                sent.onFailure(failed)
                changed()
            }
        }
    }

    /** Listeners redraw, so they always hear it on EDT, whichever thread changed what. */
    private fun changed() = onEdt { listeners.forEach { it() } }

    companion object {
        fun of(project: Project): HostThreads = project.service()
    }
}

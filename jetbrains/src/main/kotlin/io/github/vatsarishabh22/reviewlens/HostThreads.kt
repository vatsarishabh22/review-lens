package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What is already on the host, changed at once: edit or delete a posted comment, resolve or reopen a thread (spec:
 * posted is public, so these can't wait for Finish). Each write runs off EDT, then the threads are read again so the
 * views show what the host now has. The views only draw: [pending] says what is on its way, [onChanged] when to redraw.
 */
@Service(Service.Level.PROJECT)
class HostThreads internal constructor(
    private val host: () -> ReviewHost,
    private val reload: () -> Unit,
    private val pool: (() -> Unit) -> Unit,
    private val onEdt: (() -> Unit) -> Unit,
) {
    constructor(project: Project) : this(
        host = { ReviewHosts.required(ReviewService.of(project).git ?: throw CliException("No review in progress")) },
        reload = { ReviewService.of(project).reloadThreads() },
        pool = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        onEdt = { ApplicationManager.getApplication().invokeLater(it, project.disposed) },
    )

    private val writing = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** What each comment or thread on its way to the host says meanwhile ("Saving…"), by its id. */
    val pending: Map<String, String> get() = HashMap(writing)

    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** [failed] gets the host's refusal on EDT, so the caller can reopen the box with [body]: nothing typed is lost. */
    fun edit(comment: ThreadComment, body: String, failed: (Throwable) -> Unit) = write(comment.id, "Saving…", failed) { it.editComment(comment.id, body) }

    fun delete(comment: ThreadComment, failed: (Throwable) -> Unit) = write(comment.id, "Deleting…", failed) { it.deleteComment(comment.id) }

    fun resolve(thread: ReviewThread, resolved: Boolean, failed: (Throwable) -> Unit) =
        write(thread.id, if (resolved) "Resolving…" else "Unresolving…", failed) { it.resolveThread(thread.id, resolved) }

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

    private fun changed() = listeners.forEach { it() }

    companion object {
        fun of(project: Project): HostThreads = project.service()
    }
}

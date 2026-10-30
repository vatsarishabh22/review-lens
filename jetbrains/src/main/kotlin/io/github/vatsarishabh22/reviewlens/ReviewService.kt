package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The review in progress in this checkout, if any: its scope, changed files and ticks. The host keeps the rest: its
 * Viewed sync is [HostViewed], its threads [HostThreads]; blame is [ReviewBlame].
 */
@Service(Service.Level.PROJECT)
class ReviewService(private val project: Project) : Disposable {

    init {
        // a threads reload (Finish, a write) redraws every view of the review, as a load does
        HostThreads.of(project).onChanged(this) { fireChanged() }
    }

    override fun dispose() = Unit

    @Volatile var git: GitClient? = null; private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var active: ActiveReview? = null; private set
    @Volatile var meta: ReviewMeta? = null; private set
    @Volatile var changes: List<FileChange> = emptyList(); private set
    /** Every tracked path at the review's checkout, for the panel's whole-project view. */
    @Volatile var trackedFiles: List<String> = emptyList(); private set

    /** The host's name for UI text ([ReviewHosts.nameOf]). */
    val hostName get() = ReviewHosts.nameOf(git)
    val isActive get() = meta != null

    /** Re-reads which review is active and recomputes its changes. Runs git: call off EDT. */
    fun reload() {
        val git = git ?: project.basePath?.let { runCatching { GitClient.forProject(it) }.getOrNull() }?.also { git = it } ?: return
        val review = ReviewLifecycle(git).active()
        val loaded = review?.let { ReviewMeta.load(git, it.target.key) }
        changes = loaded?.let { git.changes(it.range) }.orEmpty()
        val fresh = loaded?.let { HostViewed.of(project).fold(git, it, changes).also { synced -> if (synced != it) ReviewMeta.save(git, synced) } }
        trackedFiles = if (fresh != null) git.trackedFiles() else emptyList()
        active = review
        meta = fresh
        HostThreads.of(project).load()
        fireChanged()
    }

    fun relativePath(file: VirtualFile): String? {
        val base = git?.root?.toString() ?: return null
        return file.path.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")
    }

    fun changeFor(file: VirtualFile): FileChange? = relativePath(file)?.let { path -> changes.find { it.path == path } }

    fun badge(change: FileChange): Badge = badgeOf(meta?.reviewed.orEmpty(), change)

    fun reviewedCount() = changes.count { badge(it) == Badge.REVIEWED }

    /** Unreviewed files under [dir] ("" = the whole repository), for folder badges. */
    fun pendingUnder(dir: String) = changes.count { (dir.isEmpty() || it.path.startsWith("$dir/")) && badge(it) != Badge.REVIEWED }

    /** Marks a file viewed, or un-marks it, here at once; for a file the host lists, the same tick goes there in the background. */
    @Synchronized
    fun toggleReviewed(change: FileChange) {
        val current = meta ?: return
        val viewed = badge(change) != Badge.REVIEWED
        val reviewed = if (viewed) current.reviewed + (change.path to change.newBlob) else current.reviewed - change.path
        // A PR tick is queued even when the host is unreachable now, so the next load sends it instead of erasing it.
        val sends = HostViewed.of(project).sends(current, change)
        val unsent = if (!sends) current.unsent else current.unsent.orEmpty() + (change.path to viewed)
        update { current.copy(reviewed = reviewed, unsent = unsent) }
        // the last tick is the moment to finish: say so once, with the way to do it
        if (viewed && current.target.reviewable && reviewedCount() == changes.size) notify(project, "All ${changes.size} files viewed",
            "Finish the review to post a verdict, or mark it reviewed without posting.", NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Finish review…") { showFinishReview(project, null) })
        if (sends) HostViewed.of(project).send(git!!, change.path, viewed) { took -> if (took) confirmSent(change.path, viewed) else fireChanged() }
    }

    /** The host has the tick: drop it from [ReviewMeta.unsent] unless the reviewer flipped it again meanwhile. */
    @Synchronized
    private fun confirmSent(path: String, viewed: Boolean) {
        val current = meta ?: return
        if (current.unsent?.get(path) != viewed) return
        meta = current.copy(unsent = (current.unsent - path).ifEmpty { null }).also { ReviewMeta.save(git!!, it) }
    }

    /** Every change to the saved review: written at once (it survives exit and a crash), then the editor and panel redraw. */
    @Synchronized
    fun update(change: (ReviewMeta) -> ReviewMeta) {
        val current = meta ?: return
        meta = change(current).also { ReviewMeta.save(git!!, it) }
        fireChanged()
    }

    /** The file before [before] in path order, wrapping around. */
    fun previous(before: String?): FileChange? = changes.getOrNull(previousIndex(changes.size, changes.indexOfFirst { it.path == before }))

    /** The first file after [after] (in path order, wrapping around) that is not reviewed yet. */
    fun nextUnreviewed(after: String?): FileChange? {
        val start = changes.indexOfFirst { it.path == after } + 1
        return (changes.drop(start) + changes.take(start)).firstOrNull { badge(it) != Badge.REVIEWED && it.path != after }
    }

    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun fireChanged() = ApplicationManager.getApplication().invokeLater({
        listeners.forEach { it() }
        EditorNotifications.getInstance(project).updateAllNotifications()
        ProjectView.getInstance(project).refresh()
    }, project.disposed)

    companion object {
        fun of(project: Project): ReviewService = project.service()
    }
}

package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

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

    /** How the review is ending, or null; every Finish and Exit reads it, so one click ends it once. */
    enum class Ending(val busy: String) { POSTING("Posting…"), EXITING("Exiting…") }

    @Volatile var ending: Ending? = null
        set(value) { if (field != value) { field = value; fireChanged() } }

    /** The reviewer's own tabs (file URLs), which a tick never closes. */
    var openBefore: Set<String> = emptySet(); private set

    /** Remembers the tabs open now as the reviewer's own: at Start, and after a restart, when which were is not known. */
    fun rememberOpenTabs() { openBefore = FileEditorManager.getInstance(project).openFiles.map { it.url }.toSet() }

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
        if (review == null) ending = null // whatever ended it, nothing is ending any more
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

    @Synchronized
    fun toggleReviewed(change: FileChange) = setReviewed(listOf(change), badge(change) != Badge.REVIEWED)

    /** A folder's box: ticks its [files] (the ones directly in it), or unticks them all when all are viewed ([folderTick]). */
    @Synchronized
    fun toggleFolder(files: List<FileChange>) = folderTick(files, meta?.reviewed.orEmpty()).let { (changed, viewed) -> setReviewed(changed, viewed) }

    /** Marks [files] viewed, or un-marks them, here at once; the ones the host lists go there in one call in the background. Callers hold the lock. */
    private fun setReviewed(files: List<FileChange>, viewed: Boolean) {
        val current = meta ?: return
        val reviewed = if (viewed) current.reviewed + files.associate { it.path to it.newBlob } else current.reviewed - files.map { it.path }.toSet()
        // A PR tick is queued even when the host is unreachable now, so the next load sends it instead of erasing it.
        val sent = files.filter { HostViewed.of(project).sends(current, it) }.associate { it.path to viewed }
        update { current.copy(reviewed = reviewed, unsent = if (sent.isEmpty()) current.unsent else current.unsent.orEmpty() + sent) }
        // the last tick is the moment to finish: say so once, with the way to do it
        if (viewed && current.target.reviewable && reviewedCount() == changes.size) notify(project, "All ${changes.size} files viewed",
            "Finish the review to post a verdict, or mark it reviewed without posting.", NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Finish review…") { showFinishReview(project, null) })
        if (sent.isEmpty()) return
        HostViewed.of(project).send(git!!, sent) { took -> took?.let(::confirmSent); fireChanged() }
        // the panel says "Sending…" once the call has waited this long: redraw then
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ if (HostViewed.of(project).waiting() > 0) fireChanged() }, HostViewed.SHOW_SENDING_MS, TimeUnit.MILLISECONDS)
    }

    /** The host took [sent] ([confirmed]). */
    @Synchronized
    private fun confirmSent(sent: Map<String, Boolean>) {
        val current = meta ?: return
        val unsent = confirmed(current.unsent, sent)
        if (unsent != current.unsent) meta = current.copy(unsent = unsent).also { ReviewMeta.save(git!!, it) }
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

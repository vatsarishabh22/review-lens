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

    /** What a start, a re-scope, Finish, Exit or the load on opening is busy with, as [ReviewService.busy] holds it; [busy] is its words. */
    enum class Ending(val busy: String) { LOADING("Loading…"), SWITCHING("Switching…"), POSTING("Posting…"), EXITING("Exiting…") }

    /** Start, re-scope, Finish, Exit and the load on opening each claim it, so none runs under another and one click does it once. */
    val busy = Busy(::fireChanged)

    val ending get() = busy.ending

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

    /**
     * Re-reads which review is active and recomputes its changes, for the step holding [by]: only the load on opening and
     * the checkouts of Start, re-scope and Exit, so two never overlap, and one whose claim was since replaced publishes
     * nothing. Runs git: call off EDT.
     */
    fun reload(by: Busy.Claim) {
        val git = git ?: project.basePath?.let { runCatching { GitClient.forProject(it) }.getOrNull() }?.also { git = it } ?: return
        val review = ReviewLifecycle(git).active()
        val read = review?.let { ReviewMeta.load(git, it.target.key) }
        val changed = read?.let { git.changes(it.range) }.orEmpty()
        val heard = read?.let { it to HostViewed.of(project).answer(git, it, changed) }
        val tracked = if (read != null) git.trackedFiles() else emptyList()
        // published under update()'s lock, from the file as it is then, so a tick saved meanwhile stays on screen; what is
        // still queued joins the tick line under the same lock, so a tick made from now on queues behind it, as clicked
        synchronized(this) {
            if (!busy.holds(by)) return
            changes = changed
            trackedFiles = tracked
            active = review
            meta = heard?.let { (read, answer) ->
                val (folded, view) = answer
                HostViewed.of(project).adopt(view)
                ReviewMeta.foldIn(git, read, folded)
            }
            meta?.unsent?.let(HostViewed.of(project)::sendable)?.takeIf { it.isNotEmpty() }?.let(::send)
        }
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
        // A PR tick is queued even when the host is unreachable now, so the next load sends it instead of erasing it.
        val sent = files.filter { HostViewed.of(project).sends(current, it) }.associate { it.path to viewed }
        update { m ->
            m.copy(reviewed = if (viewed) m.reviewed + files.associate { it.path to it.newBlob } else m.reviewed - files.map { it.path }.toSet(),
                unsent = if (sent.isEmpty()) m.unsent else m.unsent.orEmpty() + sent)
        }
        // the last tick is the moment to finish: say so once, with the way to do it
        if (viewed && current.target.reviewable && reviewedCount() == changes.size) notify(project, "All ${changes.size} files viewed",
            "Finish the review to post a verdict, or mark it reviewed without posting.", NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Finish review…") { showFinishReview(project, null) })
        if (sent.isNotEmpty()) send(sent)
    }

    /** [ticks] to the host through the one tick line ([HostViewed.send]); its answer confirms them, in the review they are for. */
    private fun send(ticks: Map<String, Boolean>) {
        val key = meta?.target?.key ?: return
        HostViewed.of(project).send(git ?: return, ticks) { took -> took?.let { confirmSent(key, it) }; fireChanged() }
        // the panel says "Sending…" once the call has waited this long: redraw then
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ if (HostViewed.of(project).waiting() > 0) fireChanged() }, HostViewed.SHOW_SENDING_MS, TimeUnit.MILLISECONDS)
    }

    /** The host took [sent], ticks of review [key] ([confirmedIn]). */
    @Synchronized
    private fun confirmSent(key: String, sent: Map<String, Boolean>) {
        update { confirmedIn(it, key, sent) }
    }

    /** Every change to the saved review: written at once (it survives exit and a crash), then the editor and panel redraw; false when no review is loaded. */
    @Synchronized
    fun update(change: (ReviewMeta) -> ReviewMeta): Boolean {
        val current = meta ?: return false
        // built on what the file holds now, so a change saved meanwhile by a re-scope or a load is kept; published under
        // the lock reload() publishes under
        meta = ReviewMeta.change(git!!, current.target.key) { change(it ?: current) }
        fireChanged()
        return true
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

/**
 * The one busy state. A step [claim]s it and only that claim releases it or hands it on, so an older step's end (an
 * Exit finishing after a new Start began) never clears a newer one's. [changed] runs on every change.
 */
class Busy(private val changed: () -> Unit) {
    /** A step's hold on the state: a token, so only [hand] and [release] change what it holds. */
    class Claim

    private var held: Claim? = null
    @Volatile var ending: ReviewService.Ending? = null; private set

    /** The busy state for [ending], or null when another step holds it, which [refused] hears as it was then. */
    fun claim(ending: ReviewService.Ending, refused: (ReviewService.Ending) -> Unit = {}): Claim? {
        // decided under the lock, said outside it: neither callback runs while holding it
        val mine = Claim()
        val holder = synchronized(this) { this.ending ?: run { this.ending = ending; held = mine; null } }
        if (holder != null) { refused(holder); return null }
        changed()
        return mine
    }

    /** [claim] now does [ending] (Finish's posting becomes its exit), while it still holds the state. */
    fun hand(claim: Claim, ending: ReviewService.Ending) {
        if (synchronized(this) { (held === claim).also { if (it) this.ending = ending } }) changed()
    }

    /** Whether [claim] still holds the state. */
    @Synchronized
    fun holds(claim: Claim) = held === claim

    /** Frees the state, if [claim] still holds it. */
    fun release(claim: Claim) {
        if (synchronized(this) { (held === claim).also { if (it) { held = null; ending = null } } }) changed()
    }
}

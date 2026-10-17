package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.projectView.ProjectView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Timer

/** The review in progress in this checkout, if any: its changed files and viewed state (synced with GitHub). Blame is [ReviewBlame]. */
@Service(Service.Level.PROJECT)
class ReviewService(private val project: Project) : Disposable {

    override fun dispose() = flashEnd.stop()

    @Volatile var git: GitClient? = null; private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var active: ActiveReview? = null; private set
    @Volatile var meta: ReviewMeta? = null; private set
    @Volatile var changes: List<FileChange> = emptyList(); private set
    /** Every tracked path at the review's checkout, for the panel's whole-project view. */
    @Volatile var trackedFiles: List<String> = emptyList(); private set

    /** Where the viewed checklist lives right now, for the panel's one-line status. */
    enum class Sync(val text: String) { GITHUB("Synced with GitHub"), LOCAL("Viewed kept on this laptop"), FAILED("GitHub sync failed · kept on this laptop") }
    @Volatile var sync = Sync.LOCAL; private set
    @Volatile private var pullRequestId: String? = null
    /** The viewer opened this PR (from GitHub at load); false when unknown, so GitHub's own refusal still applies. */
    @Volatile var ownPullRequest = false; private set
    private var syncFailureShown = false

    val isActive get() = meta != null

    /** True for a moment after a blocked edit: the review bar shows "Read-only while reviewing" (step 4, option A). */
    @Volatile var flashing = false; private set
    private val flashEnd = Timer(2200) { flashing = false; EditorNotifications.getInstance(project).updateAllNotifications() }
        .apply { isRepeats = false }

    /** Re-reads which review is active and recomputes its changes. Runs git: call off EDT. */
    fun reload() {
        val git = git ?: project.basePath?.let { runCatching { GitClient.forProject(it) }.getOrNull() }?.also { git = it } ?: return
        val review = ReviewLifecycle(git).active()
        val loaded = review?.let { ReviewMeta.load(git, it.target.key) }
        changes = loaded?.let { git.changes(it.range) }.orEmpty()
        val fresh = loaded?.let { withGitHubViewed(git, it) }
        trackedFiles = if (fresh != null) git.trackedFiles() else emptyList()
        active = review
        meta = fresh
        fireChanged()
    }

    /** For a PR review, folds GitHub's ticks into the saved checklist (GitHub wins for its files); a branch review stays local. */
    private fun withGitHubViewed(git: GitClient, meta: ReviewMeta): ReviewMeta {
        val number = meta.target.prNumber ?: return meta.also { sync = Sync.LOCAL; pullRequestId = null }
        val gh = GhClient(git.root)
        return runCatching { gh.viewedStates(number) }.fold(
            onSuccess = { states ->
                pullRequestId = states.pullRequestId
                ownPullRequest = states.ownPullRequest
                syncViewed(meta, states.files, changes) { path, viewed -> runCatching { gh.setViewed(states.pullRequestId, path, viewed) }.isSuccess }
                    .also { sync = if (it.unsent == null) Sync.GITHUB else Sync.FAILED; if (it != meta) ReviewMeta.save(git, it) }
            },
            onFailure = { syncFailed(it); meta },
        )
    }

    /** Says once per session that ticks stay on the laptop, so a failed sync is never silent. */
    private fun syncFailed(error: Throwable) {
        sync = Sync.FAILED
        if (syncFailureShown) return
        syncFailureShown = true
        thisLogger().info("GitHub viewed sync failed", error)
        notify(project, "Can't reach GitHub right now", "Your viewed ticks are kept on this laptop and sent when GitHub is back.", NotificationType.WARNING)
    }

    fun relativePath(file: VirtualFile): String? {
        val base = git?.root?.toString() ?: return null
        return file.path.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")
    }

    fun changeFor(file: VirtualFile): FileChange? = relativePath(file)?.let { path -> changes.find { it.path == path } }

    fun badge(change: FileChange): Badge = when (meta?.reviewed?.get(change.path)) {
        null -> Badge.TODO
        change.newBlob -> Badge.REVIEWED
        else -> Badge.CHANGED_SINCE
    }

    fun reviewedCount() = changes.count { badge(it) == Badge.REVIEWED }

    /** Unreviewed files under [dir] ("" = the whole repository), for folder badges. */
    fun pendingUnder(dir: String) = changes.count { (dir.isEmpty() || it.path.startsWith("$dir/")) && badge(it) != Badge.REVIEWED }

    /** Marks a file viewed, or un-marks it, here at once; for a PR the same tick goes to GitHub in the background. */
    @Synchronized
    fun toggleReviewed(change: FileChange) {
        val current = meta ?: return
        val viewed = badge(change) != Badge.REVIEWED
        val reviewed = if (viewed) current.reviewed + (change.path to change.newBlob) else current.reviewed - change.path
        // A PR tick is queued even when GitHub is unreachable now, so the next load sends it instead of erasing it.
        val unsent = if (current.target.prNumber == null) current.unsent else current.unsent.orEmpty() + (change.path to viewed)
        update { current.copy(reviewed = reviewed, unsent = unsent) }
        // the last tick is the moment to finish: say so once, with the way to do it
        if (viewed && current.target.prNumber != null && reviewedCount() == changes.size) notify(project, "All ${changes.size} files viewed",
            "Finish the review to post a verdict, or mark it reviewed without posting.", NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Finish review…") { showFinishReview(project, null) })
        val id = pullRequestId ?: return
        val root = git!!.root
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { GhClient(root).setViewed(id, change.path, viewed) }
                .onSuccess { confirmSent(change.path, viewed) }
                .onFailure { syncFailed(it); fireChanged() }
        }
    }

    /** GitHub has the tick: drop it from [ReviewMeta.unsent] unless the reviewer flipped it again meanwhile. */
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

    /** Called when an edit was refused; extends the flash while the reviewer keeps typing. */
    fun flashReadOnly() = ApplicationManager.getApplication().invokeLater({
        flashing = true
        flashEnd.restart()
        EditorNotifications.getInstance(project).updateAllNotifications()
    }, project.disposed)

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

package io.github.vatsarishabh22.reviewlens

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationsManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.concurrency.AppExecutorUtil

const val TITLE = "Review Lens"

/**
 * Runs [work] off EDT with a progress bar, then [then] on EDT; failures surface as an error balloon, never a dialog,
 * and [failed] lets the caller put its UI back.
 */
fun <T> background(project: Project, title: String, work: () -> T, failed: () -> Unit = {}, then: (T) -> Unit = {}) {
    object : Task.Backgroundable(project, title) {
        private var result: Result<T>? = null
        override fun run(indicator: ProgressIndicator) {
            result = runCatching(work)
        }
        override fun onSuccess() {
            result!!.onSuccess(then).onFailure { notify(project, "$title failed", it.message.orEmpty(), NotificationType.ERROR); failed() }
        }
    }.queue()
}

fun notify(project: Project, title: String, content: String, type: NotificationType = NotificationType.INFORMATION, vararg actions: AnAction) {
    NotificationGroupManager.getInstance().getNotificationGroup(TITLE).createNotification(title, content, type)
        .apply { actions.forEach(::addAction) }.notify(project)
}

/**
 * Parks the reviewer's work, checks the PR out here, and opens the first file to review.
 * [progress] hears each stage on EDT; [failed] runs after the error balloon.
 */
fun beginReview(
    project: Project, session: ReviewSession, loaded: LoadedTarget, range: ScopeRange, label: String,
    progress: (StartStep) -> Unit = {}, failed: () -> Unit = {},
) {
    val wasActive = ReviewService.of(project).isActive
    val onEdt = { step: StartStep -> ApplicationManager.getApplication().invokeLater({ progress(step) }, project.disposed) }
    background(project, "Starting review of ${loaded.target.label}", {
        session.begin(loaded, range, label, onEdt).also { afterCheckout(project, session.git) }
    }, failed) { review ->
        val service = ReviewService.of(project)
        refreshViews(project)
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        service.nextUnreviewed(null)?.let { openChange(project, it) }
        if (!wasActive) notify(project, "Reviewing ${review.target.label}",
            if (review.stashMessage != null) "Your changes on ${review.originalRef} are parked. Exit review brings them back."
            else "Read-only. Exit review takes you back to ${review.originalRef}.", NotificationType.INFORMATION, ActionManager.getInstance().getAction("ReviewLens.Exit"))
    }
}

/**
 * Ends the review with [verdict] posted to GitHub as one review, or with nothing posted (null: Mark as reviewed), then
 * exits and shows the PR list for the next one. On a refusal the comments stay drafts and the review stays open.
 */
fun finishReview(project: Project, summary: String, verdict: Verdict?) {
    val number = ReviewService.of(project).meta?.target?.prNumber ?: return
    if (verdict == null) return endReview(project, "Finished ${ReviewTarget.refOf(number)}", "Marked as reviewed. Nothing was posted to ${ReviewService.of(project).hostName}.")
    background(project, "Posting your review", {
        runCatching { ReviewDrafts.of(project).submit(summary, verdict) }.getOrElse { throw IllegalStateException("${it.message}. Your comments are kept as drafts.", it) }
    }) { sent ->
        endReview(project, "Finished ${ReviewTarget.refOf(number)}", "${verdict.posted} on ${ReviewService.of(project).hostName}${if (sent.isEmpty()) "" else " with $sent"}.")
    }
}

/** Goes back to the reviewer's branch and restores their parked work; the review can be resumed later. */
fun exitReview(project: Project) {
    val service = ReviewService.of(project)
    val total = service.changes.size
    endReview(project, null, if (total > 0 && service.reviewedCount() == total) "All $total files reviewed." else "")
}

/**
 * The one way a review ends: back to the original branch, parked work restored, `active.json` gone. [finished] titles
 * a Finish review (which then lands on the PR list) instead of "Back on <branch>"; [said] leads the message.
 */
private fun endReview(project: Project, finished: String?, said: String) {
    val service = ReviewService.of(project)
    val git = service.git ?: return
    val branch = service.active?.originalRef ?: return
    val kept = keptText(service.meta?.drafts.orEmpty())
    // ticks on functions this scope no longer changes go now (kept across re-scopes until Exit); unknown until searched
    project.serviceIfCreated<ImpactService>()?.functions()?.let { keys -> service.update { it.keepChecked(keys) } }
    background(project, "Exiting review", { ReviewLifecycle(git).exit().also { afterCheckout(project, git) } }) { exited ->
        refreshViews(project)
        // The "Reviewing…" balloons offer Exit, which no longer applies.
        NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)
            .filter { it.groupId == TITLE }.forEach(Notification::expire)
        // the panel turns back to the PR list on its own once the review ends; a finish brings it forward
        if (finished != null) ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        val title = finished ?: "Back on $branch"
        val lead = if (said.isEmpty()) "" else "$said "
        exited.changedStash?.let { stash ->
            val files = exited.changedDuringReview.joinToString(", ")
            notify(project, "Changed during the review", "$files changed while you reviewed (the IDE rewrites its settings). Kept in the stash \"$stash\", not applied.", NotificationType.WARNING)
        }
        when (exited.restored) {
            Restored.RESTORED -> notify(project, title, "${lead}Your parked changes are restored.$kept")
            Restored.NOTHING_TO_RESTORE -> notify(project, title, "${lead}Nothing was parked, so nothing to restore.$kept")
            Restored.KEPT_IN_STASH -> notify(project, "$title: your changes need a hand", "${lead}Your parked changes didn't apply cleanly, so they are still safe in the stash \"review-lens: parked from $branch…\". Resolve the conflicts, or run git stash pop.", NotificationType.WARNING)
            Restored.STASH_MISSING -> notify(project, title, "${lead}The parked stash was not found; it may have been popped by hand.", NotificationType.WARNING)
        }
    }
}

/** After a checkout outside the IDE's own git calls: reload changed files and the changes view, then the review. */
private fun afterCheckout(project: Project, git: GitClient) {
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(git.root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
    VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    ReviewService.of(project).reload()
}

/** Opens a changed file in the editor; a deleted file has nothing to open, so it shows its diff. */
fun openChange(project: Project, change: FileChange) {
    if (change.status == 'D') return showDiff(project, change)
    openPath(project, change.path, missing = { showDiff(project, change) })
}

/**
 * Opens the repository file at [path], the one way every view opens a file, at [line] (1-based) when given, with
 * [columns] of that line selected when given; [missing] runs when it isn't on disk.
 */
fun openPath(project: Project, path: String, missing: () -> Unit = {}, line: Int? = null, columns: IntRange? = null) {
    val file = ReviewService.of(project).git?.root?.resolve(path) ?: return
    // A VFS lookup can hit disk, which is not allowed on EDT: find the file in the background, open it on EDT.
    ReadAction.nonBlocking<VirtualFile?> { LocalFileSystem.getInstance().findFileByNioFile(file) }
        .finishOnUiThread(ModalityState.defaultModalityState()) { found ->
            if (found == null) return@finishOnUiThread missing()
            // no line: the caret stays where the reviewer left it
            (if (line == null) OpenFileDescriptor(project, found) else OpenFileDescriptor(project, found, line - 1, columns?.first ?: 0).apply { setScrollType(ScrollType.CENTER) }).navigate(true)
            if (line != null && columns != null) FileEditorManager.getInstance(project).selectedTextEditor?.let { editor ->
                val start = editor.caretModel.offset
                editor.selectionModel.setSelection(start, start + columns.count())
            }
            ReviewBlame.of(project).blameText(found, 0) // starts the blame early so it is ready when the editor paints
        }
        .expireWith(ReviewService.of(project))
        .submit(AppExecutorUtil.getAppExecutorService())
}

/** The file at the scope's base vs at `<to>`, read off EDT, handed to [then] on EDT. */
fun loadDiff(project: Project, change: FileChange, then: (SimpleDiffRequest) -> Unit) {
    val meta = ReviewService.of(project).meta ?: return
    val git = ReviewService.of(project).git ?: return
    background(project, "Loading diff", {
        git.show(meta.range.from, change.oldPath ?: change.path) to git.show(meta.range.to, change.path)
    }) { (before, after) ->
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.name)
        val base = factory.create(project, before, fileType)
        // a deleted file has no tab: the impact map puts its removed functions above this side
        if (change.status == 'D') base.document.putUserData(ImpactInlays.DELETED_FILE, change.path)
        then(SimpleDiffRequest("${change.path} · ${meta.scopeLabel}", base,
            factory.create(project, after, fileType), "Base ${meta.range.from.take(9)}", "PR ${meta.range.to.take(9)}"))
    }
}

/** A deleted file has no tab to flip, so its diff opens in IntelliJ's diff viewer. */
fun showDiff(project: Project, change: FileChange) = loadDiff(project, change) { DiffManager.getInstance().showDiff(project, it) }

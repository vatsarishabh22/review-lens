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
import com.intellij.openapi.fileEditor.FileEditorManager
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
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        service.nextUnreviewed(null)?.let { openChange(project, it) }
        if (!wasActive) notify(project, "Reviewing ${review.target.label}",
            if (review.stashMessage != null) "Your changes on ${review.originalRef} are parked. Exit review brings them back."
            else "Read-only. Exit review takes you back to ${review.originalRef}.", NotificationType.INFORMATION, ActionManager.getInstance().getAction("ReviewLens.Exit"))
    }
}

/** Goes back to the reviewer's branch and restores their parked work. */
fun exitReview(project: Project) {
    val service = ReviewService.of(project)
    val git = service.git ?: return
    val branch = service.active?.originalRef ?: return
    background(project, "Exiting review", { ReviewLifecycle(git).exit().also { afterCheckout(project, git) } }) { restored ->
        // The "Reviewing…" balloons offer Exit, which no longer applies.
        NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)
            .filter { it.groupId == TITLE }.forEach(Notification::expire)
        when (restored) {
            Restored.RESTORED -> notify(project, "Back on $branch", "Your parked changes are restored.")
            Restored.NOTHING_TO_RESTORE -> notify(project, "Back on $branch", "Nothing was parked, so nothing to restore.")
            Restored.KEPT_IN_STASH -> notify(project, "Back on $branch: your changes need a hand", "They didn't apply cleanly, so they are still safe in the stash \"review-lens: parked from $branch…\". Resolve the conflicts, or run git stash pop.", NotificationType.WARNING)
            Restored.STASH_MISSING -> notify(project, "Back on $branch", "The parked stash was not found; it may have been popped by hand.", NotificationType.WARNING)
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
    val path = ReviewService.of(project).git?.root?.resolve(change.path) ?: return
    // A VFS lookup can hit disk, which is not allowed on EDT: find the file in the background, open it on EDT.
    ReadAction.nonBlocking<VirtualFile?> { LocalFileSystem.getInstance().findFileByNioFile(path) }
        .finishOnUiThread(ModalityState.defaultModalityState()) { file ->
            if (file == null) return@finishOnUiThread showDiff(project, change)
            FileEditorManager.getInstance(project).openFile(file, true)
            ReviewService.of(project).blameText(file, 0) // starts the blame early so it is ready when the editor paints
        }
        .expireWith(ReviewService.of(project))
        .submit(AppExecutorUtil.getAppExecutorService())
}

/** The file at the scope's base vs at `<to>`, in IntelliJ's diff viewer. */
fun showDiff(project: Project, change: FileChange) {
    val meta = ReviewService.of(project).meta ?: return
    val git = ReviewService.of(project).git ?: return
    background(project, "Loading diff", {
        git.show(meta.range.from, change.oldPath ?: change.path) to git.show(meta.range.to, change.path)
    }) { (before, after) ->
        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(change.name)
        DiffManager.getInstance().showDiff(project, SimpleDiffRequest(
            "${change.path} · ${meta.scopeLabel}",
            factory.create(project, before, fileType),
            factory.create(project, after, fileType),
            "Base ${meta.range.from.take(9)}",
            "PR ${meta.range.to.take(9)}",
        ))
    }
}

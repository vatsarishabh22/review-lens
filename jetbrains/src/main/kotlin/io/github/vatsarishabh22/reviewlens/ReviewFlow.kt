package io.github.vatsarishabh22.reviewlens

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationsManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
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
import javax.swing.Timer

const val TITLE = "Review Lens"

/**
 * Runs [work] off EDT with a progress bar, then [then] on EDT; failures surface as an error balloon, never a dialog,
 * and [failed] lets the caller put its UI back. Neither runs once [current] says what the work was for is gone (the
 * reviewer moved to another PR or review meanwhile), so an answer never lands on what is on screen now. Every caller
 * says what its work belongs to: `{ true }` only for work that must finish whatever happens (posting, exiting).
 */
fun <T> background(project: Project, title: String, work: () -> T, current: () -> Boolean, failed: () -> Unit = {}, then: (T) -> Unit = {}) {
    // not cancellable: no work here checks the indicator, so a cancel would only drop a result that still happened
    object : Task.Backgroundable(project, title, false) {
        private var result: Result<T>? = null
        override fun run(indicator: ProgressIndicator) {
            result = runCatching(work)
        }
        override fun onSuccess() {
            if (!current()) return
            result!!.onSuccess(then).onFailure { notify(project, "$title failed", it.message.orEmpty(), NotificationType.ERROR); failed() }
        }
    }.queue()
}

/**
 * Refuses [step] while a start, a re-scope, Finish, Exit or the load on opening is under way, and says so: a greyed control never gets here,
 * but a balloon's link or a shortcut can, and a click must never vanish without a word. True when refused.
 */
fun refusedBusy(project: Project, step: String): Boolean {
    val busy = ReviewService.of(project).ending ?: return false
    sayBusy(project, busy, step)
    return true
}

private fun sayBusy(project: Project, busy: ReviewService.Ending, step: String) = notify(project, busy.busy, "$step is back once that's done.")

/** The busy state for [step], doing [ending]; null when refused, said with what held it at that moment. */
private fun claimed(project: Project, ending: ReviewService.Ending, step: String): Busy.Claim? =
    ReviewService.of(project).busy.claim(ending) { sayBusy(project, it, step) }

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
    val service = ReviewService.of(project)
    val wasActive = service.isActive
    // Finish, Exit and another re-scope wait for this one: the checkout and the saved review change under it. Busy with
    // one of them already: refused, and the caller hears it
    val claim = claimed(project, ReviewService.Ending.SWITCHING, "Start review") ?: return failed()
    if (!wasActive) service.rememberOpenTabs()
    val onEdt = { step: StartStep -> ApplicationManager.getApplication().invokeLater({ progress(step) }, project.disposed) }
    // always current: nothing else can run on the review until it releases its claim
    background(project, "Starting review of ${loaded.target.label}", {
        session.begin(loaded, range, label, onEdt).also { afterCheckout(project, session.git, claim) }
    }, current = { true }, failed = { service.busy.release(claim); failed() }) { review ->
        service.busy.release(claim)
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
fun finishReview(project: Project, summary: String, verdict: Verdict?, posted: () -> Unit, failed: (String) -> Unit) {
    val service = ReviewService.of(project)
    val number = service.meta?.target?.prNumber ?: return
    val claim = claimed(project, ReviewService.Ending.POSTING, "Finish review") ?: return
    if (verdict == null) { endReview(project, claim, number, "Marked as reviewed. Nothing was posted to ${service.hostName}."); return posted() }
    // the host's refusal is the popup's to say, where the click was: caught here, so no second balloon says it too
    background(project, "Posting your review", { runCatching { ReviewDrafts.of(project).submit(summary, verdict) } }, current = { true }) { result ->
        result.onSuccess { sent ->
            endReview(project, claim, number, "${verdict.posted} on ${service.hostName}${if (sent.isEmpty()) "" else " with $sent"}.")
            posted()
        }.onFailure { service.busy.release(claim); failed("${it.message}. Your comments are kept as drafts.") }
    }
}

/** A tick from the file's own tab (⌥R, its Viewed button) moves on and closes the tab, unless it was yours or holds a comment. */
fun viewedFromFile(project: Project, change: FileChange, file: VirtualFile?) {
    val service = ReviewService.of(project)
    service.toggleReviewed(change)
    if (service.badge(change) != Badge.REVIEWED) return
    service.nextUnreviewed(change.path)?.let { openChange(project, it) }
    if (file != null && file.url !in service.openBefore && !CommentInlays.of(project).holdsText(change.path)) FileEditorManager.getInstance(project).closeFile(file)
}

/** Goes back to the reviewer's branch and restores their parked work; the review can be resumed later. */
fun exitReview(project: Project) {
    val service = ReviewService.of(project)
    val claim = claimed(project, ReviewService.Ending.EXITING, "Exit") ?: return
    val total = service.changes.size
    endReview(project, claim, null, if (allViewed(service.reviewedCount(), total)) "All $total files reviewed." else "")
}

/**
 * The one way a review ends: back to the original branch, parked work restored, `active.json` gone, under [claim]
 * (Exit's, or Finish's handed on) until its own completion. [finished] is the PR a Finish ended (null for Exit): it
 * titles the balloon instead of "Back on <branch>" and offers the next PR of its stack; [said] leads the message.
 */
private fun endReview(project: Project, claim: Busy.Claim, finished: Int?, said: String) {
    val service = ReviewService.of(project)
    val git = service.git
    val branch = service.active?.originalRef
    if (git == null || branch == null) return service.busy.release(claim) // nothing to exit
    service.busy.hand(claim, ReviewService.Ending.EXITING)
    val kept = keptText(service.meta?.drafts.orEmpty())
    // ticks on functions this scope no longer changes go now (kept across re-scopes until Exit); unknown until searched.
    // A failed save loses only those ticks, never the exit
    runCatching { project.serviceIfCreated<ImpactService>()?.functions()?.let { keys -> service.update { it.keepChecked(keys) } } }
        .onFailure { logger<ReviewService>().warn("Impact ticks not trimmed at exit", it) }
    background(project, "Exiting review", { ReviewLifecycle(git).exit().also { afterCheckout(project, git, claim) } }, current = { true }, failed = { service.busy.release(claim) }) { exited ->
        service.busy.release(claim)
        refreshViews(project)
        // The "Reviewing…" balloons offer Exit, which no longer applies.
        NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)
            .filter { it.groupId == TITLE }.forEach(Notification::expire)
        val title = finished?.let { "Finished ${ReviewTarget.refOf(it)}" } ?: "Back on $branch"
        val lead = if (said.isEmpty()) "" else "$said "
        exited.changedStash?.let { stash ->
            val files = exited.changedDuringReview.joinToString(", ")
            notify(project, "Changed during the review", "$files changed while you reviewed (the IDE rewrites its settings). Kept in the stash \"$stash\", not applied.", NotificationType.WARNING)
        }
        var told = false
        fun say(nextPr: Int?) {
            if (told) return
            told = true
            val next = listOfNotNull(nextPr?.let { nextAction(project, it) }).toTypedArray()
            when (exited.restored) {
                Restored.RESTORED -> notify(project, title, "${lead}Your parked changes are restored.$kept", NotificationType.INFORMATION, *next)
                Restored.NOTHING_TO_RESTORE -> notify(project, title, "${lead}Nothing was parked, so nothing to restore.$kept", NotificationType.INFORMATION, *next)
                Restored.KEPT_IN_STASH -> notify(project, "$title: your changes need a hand", "${lead}Your parked changes didn't apply cleanly, so they are still safe in the stash \"review-lens: parked from $branch…\". Resolve the conflicts, or run git stash pop.", NotificationType.WARNING)
                Restored.STASH_MISSING -> notify(project, title, "${lead}The parked stash was not found; it may have been popped by hand.", NotificationType.WARNING, *next)
            }
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
        if (finished == null || toolWindow == null || exited.restored == Restored.KEPT_IN_STASH) {
            if (finished != null) toolWindow?.activate(null) // a finish brings the PR list forward whatever it says
            return@background say(null)
        }
        // a finish brings the PR list forward and says it once the list ending the review loads is in (about a second),
        // so a stacked PR's next one comes from today's list, even after a restart that never showed it; a list that
        // fails or is slower than NEXT_WAIT_MS gives the balloon without the link, never no balloon
        Timer(NEXT_WAIT_MS) { say(null) }.apply { isRepeats = false }.start()
        toolWindow.activate {
            val panel = ReviewPanel.of(project) ?: return@activate say(null)
            panel.whenListed { listed -> say(if (listed) panel.nextInStackAfter(finished) else null) }
        }
    }
}

/** How long a Finish balloon waits for the list before it is said without "Review #n next →". */
private const val NEXT_WAIT_MS = 5_000

/** "Review #n next →" in a Finish balloon: PR [number]'s detail; a click from the log once another review began does nothing. */
private fun nextAction(project: Project, number: Int): AnAction = NotificationAction.createSimpleExpiring("Review ${ReviewTarget.refOf(number)} next →") {
    if (ReviewService.of(project).isActive) return@createSimpleExpiring
    ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    ReviewPanel.of(project)?.openPr(number)
}

/** After a checkout outside the IDE's own git calls, by the step holding [claim]: reload changed files and the changes view, then the review. */
private fun afterCheckout(project: Project, git: GitClient, claim: Busy.Claim) {
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(git.root)?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
    VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
    ReviewService.of(project).reload(claim)
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
    }, current = { ReviewService.of(project).meta?.range == meta.range }) { (before, after) ->
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

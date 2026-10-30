package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.DumbAwareAction

/** Base for actions that only apply while a review is in progress. */
abstract class ReviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { ReviewService.of(it).isActive } == true
    }

    protected fun currentChange(e: AnActionEvent): FileChange? {
        val project = e.project ?: return null
        return currentFile(e)?.let { ReviewService.of(project).changeFor(it) }
    }

    /** The open tab's file: inside the Diff view the data context names the diff's content, not the file. */
    protected fun currentFile(e: AnActionEvent): VirtualFile? {
        val project = e.project ?: return null
        return FileEditorManager.getInstance(project).selectedEditor?.file ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
    }
}

class MarkReviewedAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val change = currentChange(e) ?: return
        val service = ReviewService.of(project)
        service.toggleReviewed(change)
        // Marking moves on, so a review is a run of ⌥R presses.
        if (service.badge(change) == Badge.REVIEWED) service.nextUnreviewed(change.path)?.let { openChange(project, it) }
    }
}

class NextFileAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewService.of(project).nextUnreviewed(currentChange(e)?.path)?.let { openChange(project, it) }
    }
}

/** ⌥D: flips the open changed file between File and Diff, in place. */
class ShowDiffAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = currentFile(e)?.takeIf { currentChange(e) != null } ?: return
        showView(project, file, !isDiffShown(project, file))
    }
}

/** ⌥I: unfolds the next place outside the PR left to check (spec/impact.md, "Ticks"). */
class NextPlaceAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        e.getData(CommonDataKeys.EDITOR)?.let(ImpactInlays.of(project)::nextPlace)
    }
}

class ExitReviewAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::exitReview)
    }
}

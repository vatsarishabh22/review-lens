package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction

/** Base for actions that only apply while a review is in progress. */
abstract class ReviewAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { ReviewService.of(it).isActive } == true
    }

    protected fun currentChange(e: AnActionEvent): FileChange? {
        val project = e.project ?: return null
        return e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { ReviewService.of(project).changeFor(it) }
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

class ShowDiffAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        currentChange(e)?.let { showDiff(project, it) }
    }
}

class ExitReviewAction : ReviewAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::exitReview)
    }
}

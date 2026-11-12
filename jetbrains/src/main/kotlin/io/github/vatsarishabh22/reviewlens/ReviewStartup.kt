package io.github.vatsarishabh22.reviewlens

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Picks up a review that was in progress when the IDE closed (or crashed), so it can be resumed or exited cleanly. */
class ReviewStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = ReviewService.of(project)
        ReviewGutter.of(project) // listens before the first reload, so marks appear as soon as a review is active
        CommentInlays.of(project)
        ImpactInlays.of(project) // starts the impact search once a review is active
        // the load holds the busy state: Start, Exit and Finish wait for it, so none runs on a review still being read
        val claim = service.busy.claim(ReviewService.Ending.LOADING) ?: return
        try { service.reload(claim) } finally { service.busy.release(claim) }
        val review = service.active ?: return
        withContext(Dispatchers.EDT) {
            // which tabs were open before the review is not saved: treat every restored one as the reviewer's, so a tick never closes it
            service.rememberOpenTabs()
            refreshViews(project) // tabs restored at startup opened before the review was known, without their Diff view
            val windows = ToolWindowManager.getInstance(project)
            windows.invokeLater { windows.getToolWindow(TOOL_WINDOW_ID)?.activate(null) }
            notify(project, "Still reviewing ${review.target.label}", "You're on ${review.reviewBranch}. Exit review takes you back to ${review.originalRef}" +
                (if (review.stashMessage != null) " and restores your parked changes." else "."), NotificationType.INFORMATION, ActionManager.getInstance().getAction("ReviewLens.Exit"))
        }
    }
}

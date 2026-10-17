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
        service.reload()
        val review = service.active ?: return
        withContext(Dispatchers.EDT) {
            val windows = ToolWindowManager.getInstance(project)
            windows.invokeLater { windows.getToolWindow(TOOL_WINDOW_ID)?.activate(null) }
            notify(project, "Still reviewing ${review.target.label}", "You're on ${review.reviewBranch}. Exit review takes you back to ${review.originalRef}" +
                (if (review.stashMessage != null) " and restores your parked changes." else "."), NotificationType.INFORMATION, ActionManager.getInstance().getAction("ReviewLens.Exit"))
        }
    }
}

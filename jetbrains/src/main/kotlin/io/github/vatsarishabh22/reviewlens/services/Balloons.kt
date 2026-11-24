package io.github.vatsarishabh22.reviewlens.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.Project

const val TITLE = "Review Lens"

/**
 * Refuses [step] while a start, a re-scope, Finish, Exit or the load on opening is under way, and says so: a greyed control never gets here,
 * but a balloon's link or a shortcut can, and a click must never vanish without a word. True when refused.
 */
fun refusedBusy(project: Project, step: String): Boolean {
    val busy = ReviewService.of(project).ending ?: return false
    sayBusy(project, busy, step)
    return true
}

internal fun sayBusy(project: Project, busy: ReviewService.Ending, step: String) = notify(project, busy.busy, "$step is back once that's done.")

fun notify(project: Project, title: String, content: String, type: NotificationType = NotificationType.INFORMATION, vararg actions: AnAction) {
    NotificationGroupManager.getInstance().getNotificationGroup(TITLE).createNotification(title, content, type)
        .apply { actions.forEach(::addAction) }.notify(project)
}

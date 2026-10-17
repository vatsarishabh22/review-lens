package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager

/** Toolbar entry: pick a PR (or a branch without gh), pick a scope, review it in this window. */
class ReviewPrAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReviewService.of(project).active?.let {
            return notify(project, "Already reviewing ${it.target.label}", "Exit that review first.", NotificationType.INFORMATION, ActionManager.getInstance().getAction("ReviewLens.Exit"))
        }
        background(project, "Listing pull requests", { listTargets(project) }) { (session, targets) ->
            if (targets.isEmpty()) return@background notify(project, "Nothing to review", "No open PRs or remote branches found.")
            choose(project, "Review PR", targets, ReviewTarget::label) { target ->
                background(project, "Fetching ${target.label}", { session.load(target) }) { loaded ->
                    pickScope(project, session, loaded)
                }
            }
        }
    }

    private fun listTargets(project: Project): Pair<ReviewSession, List<ReviewTarget>> {
        val git = GitClient.forProject(project.basePath!!)
        val gh = GhClient(git.root)
        // gh can be logged in while this repo isn't on GitHub; then PRs can't be listed and branches stand in.
        val prs = if (gh.isReady()) runCatching { gh.openPullRequests() }.getOrNull() else null
        val targets = if (prs != null) {
            prs.map {
                ReviewTarget("pr-${it.number}", "#${it.number} ${it.title}", it.number, it.headRefName, "${GitClient.REMOTE}/${it.baseRefName}")
            }
        } else {
            val base = git.defaultBase()
            git.remoteBranches().filter { it != base }.map {
                ReviewTarget(it.removePrefix("${GitClient.REMOTE}/").replace(Regex("[^A-Za-z0-9._-]"), "_"), it, null, it, base)
            }
        }
        return ReviewSession(git) to targets
    }
}

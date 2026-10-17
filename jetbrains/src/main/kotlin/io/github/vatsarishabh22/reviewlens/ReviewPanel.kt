package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.CardLayout
import javax.swing.JPanel

const val TOOL_WINDOW_ID = "Review"

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project, toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** The Review tool window: the PR list and one PR's detail before a review, its changed files during one. */
class ReviewPanel(private val project: Project, private val toolWindow: ToolWindow) : JPanel(CardLayout()), Disposable {

    private val service = ReviewService.of(project)
    private val list = PrListView(::openDetail, ::loadList)
    private val detail = PrDetailView(project) { show(LIST) }
    private val files = ChangedFilesView(project, this)
    private var git: GitClient? = null
    private var progress: Map<String, ReviewProgress> = emptyMap()
    private var wasActive: Boolean? = null

    init {
        add(list, LIST); add(detail, DETAIL); add(files, FILES)
        service.onChanged(this, ::refresh)
        refresh()
    }

    private fun refresh() {
        val active = service.isActive
        if (active) {
            files.refresh()
            show(FILES)
        } else if (wasActive != false) { // first paint, or the review just ended: back to a fresh list
            show(LIST)
            loadList()
        }
        wasActive = active
    }

    /** Lists PRs (or branches) and which of them this reviewer has started before. */
    fun loadList() {
        list.loading()
        background(project, "Listing pull requests", {
            val git = git ?: GitClient.forProject(project.basePath!!)
            val source = ReviewTargets(git)
            val listing = source.list()
            Triple(git, listing, listing.targets.mapNotNull { t -> source.progress(t.target)?.let { t.target.key to it } }.toMap())
        }, failed = list::failed) { (git, listing, progress) ->
            this.git = git
            this.progress = progress
            list.show(listing, progress)
        }
    }

    private fun openDetail(listed: ListedTarget) {
        detail.open(git ?: return, listed, progress[listed.target.key] != null)
        show(DETAIL)
    }

    /** Shows a screen and names it in the tool window header, as the prototype does (the stripe title is the header's text). */
    private fun show(card: String) {
        (layout as CardLayout).show(this, card)
        toolWindow.stripeTitle = when (card) { LIST -> "Pull requests"; DETAIL -> "Pull request"; else -> "Review" }
    }

    override fun dispose() = Unit

    private companion object {
        const val LIST = "list"
        const val DETAIL = "detail"
        const val FILES = "files"
    }
}

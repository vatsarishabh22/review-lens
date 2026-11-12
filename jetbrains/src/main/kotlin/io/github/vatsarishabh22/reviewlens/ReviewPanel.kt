package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
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
    private val detail = PrDetailView(project, { show(LIST) }, ::openPr)
    private val files = ChangedFilesView(project, this)
    private var git: GitClient? = null
    private var progress: Map<String, ReviewProgress> = emptyMap()
    private var listing: Listing? = null
    private var wasActive: Boolean? = null
    private val waiting = Waiters()

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
    fun loadList() = whenListed {}

    /** Runs [then] (on EDT) once a listing started now, or the one already in flight, came in (true) or failed. */
    fun whenListed(then: (Boolean) -> Unit) {
        if (!waiting.add(then)) return
        list.loading()
        background(project, "Listing pull requests", {
            val git = git ?: GitClient.forProject(project.basePath!!)
            val source = ReviewTargets(git)
            val listing = source.list()
            Triple(git, listing, listing.targets.mapNotNull { t -> source.progress(t)?.let { t.target.key to it } }.toMap())
        }, failed = { list.failed(); waiting.settle(false) }) { (git, listing, progress) ->
            this.git = git
            this.progress = progress
            this.listing = listing
            try {
                list.show(listing, progress)
                fillStackCounts(git, listing)
            } finally { waiting.settle(true) } // whatever drawing throws, the next load is never blocked behind this one
        }
    }

    /** A stacked PR's own size: the kept ones at once, the rest from the host, all at once, while the list is already up. */
    private fun fillStackCounts(git: GitClient, listing: Listing) {
        if (listing.stackKeys.isEmpty()) return
        val counts = StackStore(git)
        list.counts(counts.counts())
        background(project, "Counting stacked pull requests", {
            ReviewHosts.of(git)?.let { host -> counts.fill(listing.stackKeys.values, host::changedFiles) }
        }) { filled ->
            if (filled != null && this.listing === listing) list.counts(filled)
        }
    }

    /** The PR to review after [finished], when [finished] sits in a stack of the last listing; null otherwise. */
    fun nextInStackAfter(finished: Int): Int? = listing?.let { nextAfter(it.stacks, progress, finished) }

    /** Opens PR [number]'s detail, as the last listing has it, where Start review says what it parks before it does. */
    fun openPr(number: Int) {
        listing?.targets?.find { it.pr?.number == number }?.let(::openDetail)
    }

    private fun openDetail(listed: ListedTarget) {
        detail.open(git ?: return, listed, progress[listed.target.key])
        show(DETAIL)
    }

    /** Shows a screen and names it in the tool window header, as the prototype does (the stripe title is the header's text). */
    private fun show(card: String) {
        (layout as CardLayout).show(this, card)
        toolWindow.stripeTitle = when (card) { LIST -> "Pull requests"; DETAIL -> "Pull request"; else -> "Review" }
    }

    override fun dispose() = Unit

    companion object {
        /** The project's Review panel, once its tool window was opened. */
        fun of(project: Project): ReviewPanel? =
            ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.contentManager?.contents?.firstNotNullOfOrNull { it.component as? ReviewPanel }

        private const val LIST = "list"
        private const val DETAIL = "detail"
        private const val FILES = "files"
    }
}

/** Callers waiting on one load in flight: the first starts it, and [settle] answers each of them once. */
class Waiters {
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    /** Adds [then]; true when it is the first, so the caller starts the load. */
    fun add(then: (Boolean) -> Unit): Boolean {
        waiting += then
        return waiting.size == 1
    }

    /** Tells everyone waiting whether the load came in ([ok]); the next [add] starts a new one. */
    fun settle(ok: Boolean) = waiting.toList().also { waiting.clear() }.forEach { it(ok) }
}

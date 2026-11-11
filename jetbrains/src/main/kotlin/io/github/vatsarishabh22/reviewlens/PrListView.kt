package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.event.DocumentEvent

/**
 * "Waiting on you" over "Other open", filtered by the search box; picking a row opens its detail. Real components, not
 * a JList's painted stamps: a stack is a [StackCard] that scrolls on its own.
 */
class PrListView(private val onPick: (ListedTarget) -> Unit, onRefresh: () -> Unit) : JPanel(BorderLayout()) {

    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Search pull requests" }
    private val rows = ScrollStack(JBUI.Borders.emptyBottom(8))
    private val empty = mutedLabel("Loading pull requests…", small = false).apply { border = JBUI.Borders.empty(16, 12) }
    private var all: List<ListedTarget> = emptyList()
    private var stacks: List<List<ListedTarget>> = emptyList()
    private var progress: Map<String, ReviewProgress> = emptyMap()
    private var stackKeys: Map<Int, String> = emptyMap()
    private var counts: Map<String, Int> = emptyMap()
    /** Each stack card's scroll, by its bottom PR, kept while the same listing is drawn again (search, counts arriving). */
    private var scrolled: Map<Int, Int> = emptyMap()
    /** Branches stand in when `gh` could not list PRs (not installed, signed out, offline, not a GitHub repo): say so. */
    private val fallbackNote = mutedLabel("").apply {
        border = JBUI.Borders.empty(0, 12, 4, 12); isVisible = false
    }

    init {
        // IntelliJ's own toolbar button (hover highlight, tooltip): a bare icon was easy to miss
        val action = object : DumbAwareAction("Refresh", "List the pull requests again", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = onRefresh()
        }
        val refresh = ActionButton(action, action.templatePresentation.clone(), ActionPlaces.TOOLWINDOW_CONTENT, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
            .apply { cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
        val bar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(6, 10, 6, 6)
            add(search); add(refresh, BorderLayout.EAST)
        }
        add(stack(bar, fallbackNote), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(rows, true).apply { horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER })
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        showEmpty()
    }

    fun loading() {
        all = emptyList(); stacks = emptyList(); fallbackNote.isVisible = false
        empty.text = "Loading pull requests…"; showEmpty()
    }

    fun failed() {
        all = emptyList(); stacks = emptyList(); fallbackNote.isVisible = false
        empty.text = "Couldn't list pull requests"; showEmpty()
    }

    /** [progress] per target key, for PRs this reviewer started before (the "Resume" pill). */
    fun show(listing: Listing, progress: Map<String, ReviewProgress>) {
        all = listing.targets; stacks = listing.stacks; stackKeys = listing.stackKeys; counts = emptyMap(); this.progress = progress
        scrolled = emptyMap() // a new listing opens each stack at its next PR
        fallbackNote.isVisible = listing.branchesOnly
        fallbackNote.text = "Couldn't get pull requests from ${listing.hostName} · showing branches"
        empty.text = if (listing.branchesOnly) "No branches to review" else "No open pull requests"
        rebuild()
    }

    /** Stacked PRs' own sizes, as they arrive. */
    fun counts(counts: Map<String, Int>) {
        this.counts = counts
        rebuild()
    }

    /** A PR's own file count: the host's, except a stacked PR's, which is what it adds on top of its parent. */
    private fun ownFiles(t: ListedTarget): Int? {
        val pr = t.pr ?: return null
        return stackKeys[pr.number]?.let { counts[it] } ?: pr.changedFiles.takeIf { pr.number !in stackKeys }
    }

    private fun showEmpty() {
        rows.removeAll(); rows.add(empty); rows.revalidate(); rows.repaint()
    }

    private fun rebuild() {
        scrolled = scrolled + rows.components.filterIsInstance<StackCard>().mapNotNull { c -> c.scrolled?.let { c.bottom to it } }
        rows.removeAll()
        listSections(all, stacks, search.text).forEach { section ->
            rows.add(row(sectionLabel(section.title)).apply { border = JBUI.Borders.empty(10, 12, 4, 12) })
            section.stacks.forEach { stack ->
                rows.add(StackCard(stack.map { StackRow(it, progress[it.target.key], ownFiles(it)) }, scrolled[stack.first().pr!!.number], onPick))
            }
            section.rows.forEach { rows.add(PrRow(it, progress[it.target.key], ownFiles(it), onPick)) }
        }
        if (rows.componentCount == 0) showEmpty() else { rows.revalidate(); rows.repaint() }
    }
}

/** One heading of the list and what sits under it: whole stacks first, then the PRs (or branches) in none. */
data class ListSection(val title: String, val stacks: List<List<ListedTarget>>, val rows: List<ListedTarget>)

/**
 * "Waiting on you" over "Other open" (or "Branches" without the host), filtered by [query]. A stack stays whole: shown
 * when any PR in it matches, in Waiting on you when any PR in it asks for you. Empty sections are left out.
 */
fun listSections(all: List<ListedTarget>, stacks: List<List<ListedTarget>>, query: String): List<ListSection> {
    val q = query.trim().lowercase()
    val matches = { t: ListedTarget -> q.isEmpty() || q in "${t.target.ref.orEmpty()} ${t.title} ${t.pr?.author?.name}".lowercase() }
    val shown = all.filter(matches)
    if (shown.none { it.pr != null }) return listOf(ListSection("Branches", emptyList(), shown)).filter { it.rows.isNotEmpty() }
    val stacked = stacks.flatten().toSet()
    val (waiting, other) = stacks.filter { it.any(matches) }.partition { stack -> stack.any { it.requested } }
    return listOf(
        ListSection("Waiting on you", waiting, shown.filter { it.requested && it !in stacked }),
        ListSection("Other open", other, shown.filter { !it.requested && it !in stacked }),
    ).filter { it.stacks.isNotEmpty() || it.rows.isNotEmpty() }
}

/** A PR (or branch) outside any stack: avatar, title, number, author, files, and where your review stands. */
private class PrRow(listed: ListedTarget, progress: ReviewProgress?, files: Int?, onPick: (ListedTarget) -> Unit) : PickRow(BorderLayout(JBUI.scale(10), 0)) {
    init {
        val pr = listed.pr
        val title = JBLabel(listed.title).apply { font = JBFont.label().asBold() }
        val meta = mutableListOf<JComponent>()
        if (pr != null) {
            meta += mutedLabel(ReviewTarget.refOf(pr.number)); meta += mutedLabel(pr.author.name)
            files?.let { meta += mutedLabel(files(it)) }
        }
        if (progress != null) meta += if (progress.done) Pill(progress.label, ReviewColors.okSoft, ReviewColors.ok)
            else Pill(progress.label, ReviewColors.review, ReviewColors.reviewFg)
        // still asked, whatever was done: the host clears the request when it has your review
        if (listed.requested) meta += Pill("Review requested", ReviewColors.accentSoft, ReviewColors.accent)
        val text = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(title, BorderLayout.NORTH)
            if (meta.isNotEmpty()) add(row(*meta.toTypedArray()))
        }
        val avatar = JBLabel(if (pr != null) AvatarIcon(pr.author.name, pr.author.login) else AllIcons.Vcs.Branch).apply {
            verticalAlignment = JBLabel.TOP
            border = JBUI.Borders.emptyTop(2)
        }
        border = JBUI.Borders.empty(8, 12)
        add(avatar, BorderLayout.WEST)
        add(text)
        pickOn({ onPick(listed) }, text, title)
    }

    override fun paintComponent(g: Graphics) {
        if (hover) { g.color = ReviewColors.chip; g.fillRect(0, 0, width, height) }
        super.paintComponent(g)
    }
}

package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.project.DumbAwareAction
import java.awt.Cursor
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent

/** One line of the list: a section heading or a PR (or branch) with its resume state. */
private sealed interface ListRow {
    data class Heading(val text: String) : ListRow
    data class Item(val listed: ListedTarget, val progress: ReviewProgress?) : ListRow
}

/** "Waiting on you" over "Other open", filtered by the search box; picking a row opens its detail. */
class PrListView(private val onPick: (ListedTarget) -> Unit, onRefresh: () -> Unit) : JPanel(BorderLayout()) {

    private val search = SearchTextField(false).apply { textEditor.emptyText.text = "Search pull requests" }
    private val model = DefaultListModel<ListRow>()
    private val list = JBList(model).apply { cellRenderer = Renderer(); emptyText.text = "Loading pull requests…" }
    private var all: List<ListedTarget> = emptyList()
    private var progress: Map<String, ReviewProgress> = emptyMap()
    /** Branches stand in when `gh` could not list PRs (not installed, signed out, offline, not a GitHub repo): say so. */
    private val fallbackNote = mutedLabel("Couldn't get pull requests from GitHub · showing branches").apply {
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
        add(ScrollPaneFactory.createScrollPane(list, true))
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it).contains(e.point) } ?: return
                (model[row] as? ListRow.Item)?.let { onPick(it.listed) }
            }
        })
    }

    fun loading() {
        all = emptyList(); model.clear(); fallbackNote.isVisible = false
        list.emptyText.text = "Loading pull requests…"
    }

    fun failed() {
        all = emptyList(); model.clear(); fallbackNote.isVisible = false
        list.emptyText.text = "Couldn't list pull requests"
    }

    /** [progress] per target key, for PRs this reviewer started before (the "Resume" pill). */
    fun show(listing: Listing, progress: Map<String, ReviewProgress>) {
        all = listing.targets; this.progress = progress
        fallbackNote.isVisible = listing.branchesOnly
        list.emptyText.text = if (listing.branchesOnly) "No branches to review" else "No open pull requests"
        rebuild()
    }

    private fun rebuild() {
        val query = search.text.trim().lowercase()
        val shown = all.filter { query.isEmpty() || query in "${it.target.ref.orEmpty()} ${it.title} ${it.pr?.author?.displayName}".lowercase() }
        model.clear()
        fun section(title: String, rows: List<ListedTarget>) {
            if (rows.isEmpty()) return
            model.addElement(ListRow.Heading(title))
            rows.forEach { model.addElement(ListRow.Item(it, progress[it.target.key])) }
        }
        if (shown.any { it.pr != null }) {
            section("Waiting on you", shown.filter { it.requested })
            section("Other open", shown.filter { !it.requested })
        } else {
            section("Branches", shown)
        }
    }

    private class Renderer : ListCellRenderer<ListRow> {
        override fun getListCellRendererComponent(list: JList<out ListRow>, value: ListRow, index: Int, selected: Boolean, focused: Boolean): Component =
            when (value) {
                is ListRow.Heading -> JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(10, 12, 4, 12)
                    add(sectionLabel(value.text))
                }
                is ListRow.Item -> item(list, value, selected)
            }

        private fun item(list: JList<*>, row: ListRow.Item, selected: Boolean): Component {
            val pr = row.listed.pr
            val title = JBLabel(row.listed.title).apply { font = JBFont.label().asBold() }
            val meta = mutableListOf<JComponent>()
            if (pr != null) {
                meta += mutedLabel(ReviewTarget.refOf(pr.number)); meta += mutedLabel(pr.author.displayName); meta += mutedLabel(files(pr.changedFiles))
            }
            val progress = row.progress
            when {
                progress != null -> meta += if (progress.done) Pill(progress.label, ReviewColors.okSoft, ReviewColors.ok)
                    else Pill(progress.label, ReviewColors.review, ReviewColors.reviewFg)
                row.listed.requested -> meta += Pill("Review requested", ReviewColors.accentSoft, ReviewColors.accent)
            }
            val text = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                isOpaque = false
                add(title, BorderLayout.NORTH)
                if (meta.isNotEmpty()) add(row(*meta.toTypedArray()))
            }
            val avatar = JBLabel(if (pr != null) AvatarIcon(pr.author.displayName, pr.author.login) else AllIcons.Vcs.Branch).apply {
                verticalAlignment = JBLabel.TOP
                border = JBUI.Borders.emptyTop(2)
            }
            return JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
                border = JBUI.Borders.empty(8, 12)
                background = if (selected) UIUtil.getListSelectionBackground(list.hasFocus()) else list.background
                add(avatar, BorderLayout.WEST)
                add(text)
            }
        }
    }
}

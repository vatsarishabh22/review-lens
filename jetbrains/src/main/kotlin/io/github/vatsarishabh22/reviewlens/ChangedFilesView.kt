package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.RowIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.font.TextAttribute
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A row of the changed-files list: a folder heading or a changed file under it. */
private sealed interface FileRow {
    data class Dir(val path: String) : FileRow
    data class File(val change: FileChange, val nested: Boolean) : FileRow
}

/** What the panel lists under its title. */
private enum class Pane(val label: String) { FILES("Changed files"), PROJECT("Whole project"), COMMENTS("Comments") }


/**
 * One thread or draft in the Comments tab; [line] is where clicking it goes, null when the version shown has no such line.
 * [pending] are your replies to the thread, [draft] is set when the row is itself your pending draft: both are edited here,
 * since the editor can show a draft only where it has a place (a thread a push moved, a deleted thread, another scope's version).
 */
private data class Remark(val path: String, val line: Int?, val who: String, val kind: CommentLook, val where: String, val replies: Int, val body: String, val mine: Boolean,
    val pending: List<Draft> = emptyList(), val draft: Draft? = null)

private const val EDIT = "Edit"
private const val DELETE = "Delete"

/** The Comments tab's filter: each one keeps its own rows. */
private enum class Filter(val label: String, val keeps: (Remark) -> Boolean) {
    ALL("All", { true }),
    OPEN("Open", { it.kind == CommentLook.OPEN }),
    MINE("Mine", { it.mine || it.pending.isNotEmpty() }),
    PENDING("Pending", { it.kind == CommentLook.PENDING || it.pending.isNotEmpty() }),
}

private sealed interface RemarkRow {
    data class File(val path: String) : RemarkRow
    data class Item(val remark: Remark) : RemarkRow
    /** Your pending reply, under the thread it answers. */
    data class Reply(val draft: Draft) : RemarkRow
}

/**
 * The review in progress: title, viewed progress, and the changed files grouped by folder (or the whole project
 * with the changed files marked, or every comment on the PR). Clicking a badge marks the file viewed; clicking the row opens it.
 */
class ChangedFilesView(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val service = ReviewService.of(project)
    private val title = wrappingText("", JBFont.label().asBold())
    private val strip = ProgressStrip()
    private val viewedText = mutedLabel()
    private val syncText = mutedLabel()
    private val model = DefaultListModel<FileRow>()
    private val list = JBList(model).apply { cellRenderer = Renderer() }
    private val treeRoot = DefaultMutableTreeNode()
    private val tree = Tree(DefaultTreeModel(treeRoot)).apply { isRootVisible = false; cellRenderer = ProjectRenderer() }
    private val remarkModel = DefaultListModel<RemarkRow>()
    private val remarkList = JBList(remarkModel).apply { cellRenderer = RemarkRenderer() }
    private val remarkNote = mutedLabel().apply { border = JBUI.Borders.empty(4, 12, 0, 12) }
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private var pane = Pane.FILES
    private var filter = Filter.ALL
    private lateinit var tabs: SegmentedButton<Pane>
    private var syncing = false

    init {
        val toggle = panel {
            row {
                tabs = segmentedButton(Pane.entries) { text = it.label + (if (it == Pane.COMMENTS) remarkCount().takeIf { n -> n > 0 }?.let { n -> "  $n" }.orEmpty() else "") }
                    .apply { selectedItem = Pane.FILES }
                    .whenItemSelected { pane = it; showBody() }
            }
        }.apply { isOpaque = false }
        val filters = panel {
            row {
                segmentedButton(Filter.entries) { text = it.label }
                    .apply { selectedItem = Filter.ALL }
                    .whenItemSelected { filter = it; fillRemarks() }
            }
        }.apply { isOpaque = false; border = JBUI.Borders.empty(6, 12, 0, 12) }
        val counts = JPanel(BorderLayout()).apply { isOpaque = false; add(viewedText, BorderLayout.WEST); add(syncText, BorderLayout.EAST) }
        val head = stack(title, vgap(8), strip, vgap(6), counts, vgap(4), toggle).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.line), JBUI.Borders.empty(6, 12, 6, 12))
        }
        add(head, BorderLayout.NORTH)
        body.add(ScrollPaneFactory.createScrollPane(list, true), Pane.FILES.name)
        body.add(ScrollPaneFactory.createScrollPane(tree, true), Pane.PROJECT.name)
        body.add(JPanel(BorderLayout()).apply {
            add(stack(filters, remarkNote), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(remarkList, true))
        }, Pane.COMMENTS.name)
        add(body)

        remarkList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = remarkList.locationToIndex(e.point).takeIf { it >= 0 && remarkList.getCellBounds(it, it).contains(e.point) } ?: return
                val row = remarkModel[index]
                val draft = (row as? RemarkRow.Item)?.remark?.draft ?: (row as? RemarkRow.Reply)?.draft
                when (draft?.let { linkAt(index, e.point) }) {
                    EDIT -> editDraft(draft, remarkList.getCellBounds(index, index))
                    DELETE -> ReviewDrafts.of(project).delete(draft)
                    else -> (row as? RemarkRow.Item)?.remark?.let { openPath(project, it.path, line = it.line) }
                }
            }
        })
        remarkList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = remarkList.locationToIndex(e.point)
                val over = index >= 0 && remarkList.getCellBounds(index, index).contains(e.point) && linkAt(index, e.point) != null
                remarkList.cursor = Cursor.getPredefinedCursor(if (over) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            }
        })

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point).takeIf { it >= 0 && list.getCellBounds(it, it).contains(e.point) } ?: return
                val row = model[index] as? FileRow.File ?: return
                if (e.x < badgeRight(row)) service.toggleReviewed(row.change) else if (!syncing) openChange(project, row.change)
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = (tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ProjectEntry ?: return
                if (path.isDir) return
                service.changes.find { it.path == path.path }?.let { return openChange(project, it) }
                openPath(project, path.path)
            }
        })
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let(service::relativePath)?.let(::select)
            }
        })
    }

    /** The Edit or Delete link under [point] in row [index], found by laying out the row as the renderer draws it. */
    private fun linkAt(index: Int, point: Point): String? {
        val bounds = remarkList.getCellBounds(index, index) ?: return null
        val cell = remarkList.cellRenderer.getListCellRendererComponent(remarkList, remarkModel[index], index, false, false)
        cell.setBounds(0, 0, bounds.width, bounds.height)
        fun layout(c: Component) { if (c is Container) { c.doLayout(); c.components.forEach(::layout) } }
        layout(cell)
        return SwingUtilities.getDeepestComponentAt(cell, point.x - bounds.x, point.y - bounds.y)?.name?.takeIf { it == EDIT || it == DELETE }
    }

    /** The editor's comment box, in a popup under the row: ⌘↩ saves, Esc cancels; a click elsewhere keeps what is typed. */
    private fun editDraft(draft: Draft, cell: Rectangle) {
        val field = commentField(project, draft.body, "Edit your comment…", (cell.width - JBUI.scale(24)).coerceAtLeast(JBUI.scale(260)))
        field.preferredSize = Dimension(field.preferredSize.width, COMMENT_BOX_MIN * 2)
        var popup: JBPopup? = null
        fun save() {
            val body = field.text.trim().takeIf { it.isNotEmpty() } ?: return
            popup?.cancel()
            ReviewDrafts.of(project).edit(draft, body)
        }
        DumbAwareAction.create { save() }.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), field)
        val saveButton = JButton("Save").apply { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true); addActionListener { save() } }
        val cancel = JButton("Cancel").apply { addActionListener { popup?.cancel() } }
        val actions = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(mutedLabel("${KeymapUtil.getShortcutsText(CommonShortcuts.getCtrlEnter().shortcuts)} save · Esc cancel"))
            add(row(cancel, saveButton, gap = 8), BorderLayout.EAST)
        }
        val framed = JPanel(BorderLayout()).apply { border = RoundedLineBorder(ReviewColors.accent, JBUI.scale(6)); add(field) } // the editor box's outline
        val content = JPanel(BorderLayout(0, JBUI.scale(8))).apply { border = JBUI.Borders.empty(10); add(framed); add(actions, BorderLayout.SOUTH) }
        popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, field)
            .setRequestFocus(true).setFocusable(true).setCancelKeyEnabled(true).setCancelOnClickOutside(false).createPopup()
        popup.show(RelativePoint(remarkList, Point(cell.x + JBUI.scale(12), cell.y + cell.height)))
    }

    fun refresh() {
        val meta = service.meta ?: return
        val pr = meta.target.ref?.let { "$it " }.orEmpty()
        title.text = pr + meta.target.label.removePrefix(pr)
        val total = service.changes.size
        val done = service.reviewedCount()
        strip.fraction = if (total == 0) 0.0 else done.toDouble() / total
        viewedText.text = "$done of $total files viewed · ${meta.scopeLabel}"
        syncText.text = service.sync.text(service.hostName)

        val selected = (list.selectedValue as? FileRow.File)?.change?.path
        model.clear()
        service.changes.groupBy { it.dir }.toSortedMap().forEach { (dir, files) ->
            if (dir.isNotEmpty()) model.addElement(FileRow.Dir(dir))
            files.forEach { model.addElement(FileRow.File(it, dir.isNotEmpty())) }
        }
        selected?.let(::select)
        if (pane == Pane.PROJECT) buildTree()
        fillRemarks()
        tabs.update(Pane.COMMENTS)
    }

    /** Opens the Comments tab with every thread listed, for the editor's "Show in Comments". */
    fun showComments() {
        filter = Filter.ALL
        tabs.selectedItem = Pane.COMMENTS
        fillRemarks()
    }

    private fun showBody() {
        if (pane == Pane.PROJECT) buildTree()
        cards.show(body, pane.name)
    }

    /** Everyone's threads and your drafts, by file in path order; within a file open before resolved, then by line. */
    private fun remarks(): List<Remark> {
        val end = service.meta?.range?.to ?: return emptyList()
        val blobs = service.changes.associate { it.path to it.newBlob }
        val drafts = ReviewDrafts.of(project)
        val threads = service.threads.orEmpty().map { thread ->
            Remark(thread.path, thread.lineAt(end), if (thread.mine) "You" else thread.comments[0].author, CommentLook.of(thread.state), thread.where, thread.comments.size - 1,
                thread.comments[0].body, thread.mine, drafts.repliesTo(thread))
        }
        val comments = drafts.mine().map { draft ->
            val current = blobs[draft.path] == draft.blob
            Remark(draft.path, draft.line.takeIf { current }, "You", if (draft.posted) CommentLook.POSTED else CommentLook.PENDING,
                span(draft.startLine, draft.line) + if (current) "" else " of an earlier version", 0, draft.body, true, draft = draft.takeUnless { it.posted })
        }
        // a pending reply whose thread isn't listed (deleted on GitHub, or the threads didn't load) gets its own row
        val ids = service.threads.orEmpty().map { it.id }.toSet()
        val strays = service.meta?.drafts.orEmpty().filter { !it.posted && it.replyTo != null && it.replyTo !in ids }.map { draft ->
            Remark(draft.path, null, "You", CommentLook.PENDING, if (service.threads == null) "reply" else "reply · its thread is gone from ${service.hostName}", 0, draft.body, true, draft = draft)
        }
        return (threads + comments + strays).sortedWith(compareBy({ it.path }, { it.kind == CommentLook.RESOLVED }, { it.line ?: Int.MAX_VALUE }))
    }

    private fun remarkCount() = remarks().size

    private fun fillRemarks() {
        remarkModel.clear()
        remarks().filter(filter.keeps).groupBy { it.path }.forEach { (path, items) ->
            remarkModel.addElement(RemarkRow.File(path))
            items.forEach { remark -> remarkModel.addElement(RemarkRow.Item(remark)); remark.pending.forEach { remarkModel.addElement(RemarkRow.Reply(it)) } }
        }
        val pr = service.meta?.target?.reviewable == true
        remarkNote.text = when {
            pr && service.threads == null -> "Couldn't load comments from ${service.hostName} · showing only yours"
            remarkModel.isEmpty -> "Nothing here."
            else -> ""
        }
        remarkNote.isVisible = remarkNote.text.isNotEmpty()
    }

    private fun buildTree() {
        treeRoot.removeAllChildren()
        val dirs = HashMap<String, DefaultMutableTreeNode>().apply { put("", treeRoot) }
        fun dirNode(path: String): DefaultMutableTreeNode = dirs.getOrPut(path) {
            DefaultMutableTreeNode(ProjectEntry(path, true)).also { dirNode(path.substringBeforeLast('/', "")).add(it) }
        }
        service.trackedFiles.sorted().forEach { dirNode(it.substringBeforeLast('/', "")).add(DefaultMutableTreeNode(ProjectEntry(it, false))) }
        (tree.model as DefaultTreeModel).reload()
        // Open the folders that hold changes; the rest stay collapsed.
        TreeUtil.treeNodeTraverser(treeRoot).forEach { node ->
            val entry = (node as DefaultMutableTreeNode).userObject as? ProjectEntry ?: return@forEach
            if (entry.isDir && service.changes.any { it.path.startsWith(entry.path + "/") }) tree.expandPath(TreePath(node.path))
        }
    }

    /** Moves the list selection to [path] without re-opening it. */
    private fun select(path: String) {
        val index = (0 until model.size).firstOrNull { (model[it] as? FileRow.File)?.change?.path == path } ?: return
        syncing = true
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
        syncing = false
    }

    private fun badgeRight(row: FileRow.File) = JBUI.scale(indent(row) + 18)
    private fun indent(row: FileRow.File) = if (row.nested) 28 else 12

    private inner class Renderer : ListCellRenderer<FileRow> {
        override fun getListCellRendererComponent(list: JList<out FileRow>, value: FileRow, index: Int, selected: Boolean, focused: Boolean): Component {
            val cell = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                background = if (selected) UIUtil.getListSelectionBackground(list.hasFocus()) else list.background
            }
            when (value) {
                is FileRow.Dir -> {
                    cell.border = JBUI.Borders.empty(3, 12, 3, 10)
                    cell.add(JBLabel(value.path, AllIcons.Nodes.Folder, JBLabel.LEFT).apply { foreground = ReviewColors.muted })
                }
                is FileRow.File -> {
                    val change = value.change
                    cell.border = JBUI.Borders.empty(3, indent(value), 3, 10)
                    val name = JBLabel(change.name, FileTypeManager.getInstance().getFileTypeByFileName(change.name).icon, JBLabel.LEFT)
                    if (change.status == 'D') {
                        name.foreground = ReviewColors.muted
                        name.font = name.font.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
                    }
                    val comments = ReviewDrafts.of(project).on(change).size + service.threadsOn(change).size
                    val counts = if (change.added == null) mutedLabel("binary") else row(
                        *listOfNotNull(JBLabel("$comments", AllIcons.General.Balloon, JBLabel.LEFT).apply { foreground = ReviewColors.muted; iconTextGap = JBUI.scale(3) }
                            .takeIf { comments > 0 }).toTypedArray(),
                        JBLabel("+${change.added}").apply { foreground = ReviewColors.add; font = mono() },
                        JBLabel("−${change.deleted}").apply { foreground = ReviewColors.del; font = mono() },
                        JBLabel(change.status.toString()).apply { foreground = ReviewColors.status(change.status); font = mono().deriveFont(Font.BOLD) },
                        gap = 6,
                    )
                    cell.add(JBLabel(BadgeIcon(service.badge(change))), BorderLayout.WEST)
                    cell.add(name)
                    cell.add(counts, BorderLayout.EAST)
                }
            }
            return cell
        }

        private fun mono() = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, 11))
    }

    private inner class RemarkRenderer : ListCellRenderer<RemarkRow> {
        override fun getListCellRendererComponent(list: JList<out RemarkRow>, value: RemarkRow, index: Int, selected: Boolean, focused: Boolean): Component {
            val cell = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                background = if (selected) UIUtil.getListSelectionBackground(list.hasFocus()) else list.background
            }
            when (value) {
                is RemarkRow.File -> {
                    cell.border = JBUI.Borders.empty(8, 12, 2, 10)
                    cell.add(JBLabel(value.path).apply { foreground = ReviewColors.muted; font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, 11)) })
                }
                is RemarkRow.Item -> {
                    val remark = value.remark
                    cell.border = JBUI.Borders.empty(5, 18, 5, 10)
                    val replies = if (remark.replies > 0) " · ${remark.replies} repl${if (remark.replies > 1) "ies" else "y"}" else ""
                    val meta = row(JBLabel(remark.who).apply { font = JBFont.label().asBold() }, Pill(remark.kind.label(service.hostName), remark.kind.fill, remark.kind.fg),
                        mutedLabel(remark.where + replies),
                        *listOfNotNull(Pill("your reply pending", ReviewColors.review, ReviewColors.reviewFg).takeIf { remark.pending.isNotEmpty() }).toTypedArray(), gap = 6)
                    val body = JBLabel(previewLine(remark.body)).apply { if (remark.kind == CommentLook.RESOLVED) foreground = ReviewColors.muted }
                    cell.add(JBLabel(Dot(remark.kind.fg)).apply { verticalAlignment = JBLabel.TOP; border = JBUI.Borders.emptyTop(4) }, BorderLayout.WEST)
                    cell.add(stack(if (remark.draft != null) withLinks(meta) else meta, vgap(2), body))
                }
                is RemarkRow.Reply -> {
                    cell.border = JBUI.Borders.compound(JBUI.Borders.emptyLeft(22), JBUI.Borders.customLine(ReviewColors.warn, 0, 2, 0, 0), JBUI.Borders.empty(4, 12, 6, 10))
                    val meta = row(JBLabel("You").apply { font = JBFont.label().asBold() }, CommentLook.PENDING.let { Pill(it.label(service.hostName), it.fill, it.fg) }, mutedLabel("reply"), gap = 6)
                    cell.add(stack(withLinks(meta), vgap(2), JBLabel(previewLine(value.draft.body))))
                }
            }
            return cell
        }
    }

    /** [meta] with Edit and Delete at its right, named so a click can tell them apart ([linkAt]). */
    private fun withLinks(meta: JComponent): JComponent {
        fun link(text: String) = JBLabel(text).apply { name = text; foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED; font = JBFont.small() }
        return JPanel(BorderLayout()).apply { isOpaque = false; add(meta); add(row(link(EDIT), link(DELETE), gap = 8), BorderLayout.EAST) }
    }

    /** The coloured dot before a row in the Comments tab. */
    private class Dot(private val color: Color) : Icon {
        override fun getIconWidth() = JBUI.scale(8)
        override fun getIconHeight() = JBUI.scale(8)
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
            g2.dispose()
        }
    }

    private data class ProjectEntry(val path: String, val isDir: Boolean) {
        val name get() = path.substringAfterLast('/')
    }

    private inner class ProjectRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val entry = (value as? DefaultMutableTreeNode)?.userObject as? ProjectEntry ?: return
            if (entry.isDir) {
                icon = AllIcons.Nodes.Folder
                append(entry.name)
                service.pendingUnder(entry.path).takeIf { it > 0 }
                    ?.let { append("  ★ $it", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, ReviewColors.star)) }
                return
            }
            val change = service.changes.find { it.path == entry.path }
            val fileIcon = FileTypeManager.getInstance().getFileTypeByFileName(entry.name).icon
            if (change == null) {
                icon = fileIcon
                return append(entry.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            icon = RowIcon(BadgeIcon(service.badge(change)), fileIcon)
            append(entry.name)
        }
    }
}

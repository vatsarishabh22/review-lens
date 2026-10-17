package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.RowIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ListCellRenderer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/** A row of the changed-files list: a folder heading or a changed file under it. */
private sealed interface FileRow {
    data class Dir(val path: String) : FileRow
    data class File(val change: FileChange, val nested: Boolean) : FileRow
}

/**
 * The review in progress: title, viewed progress, and the changed files grouped by folder (or the whole project
 * with the changed files marked). Clicking a badge marks the file viewed; clicking the row opens it.
 */
class ChangedFilesView(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val service = ReviewService.of(project)
    private val title = wrappingText("", JBFont.label().asBold())
    private val strip = ProgressStrip()
    private val viewedText = mutedLabel()
    private val model = DefaultListModel<FileRow>()
    private val list = JBList(model).apply { cellRenderer = Renderer() }
    private val treeRoot = DefaultMutableTreeNode()
    private val tree = Tree(DefaultTreeModel(treeRoot)).apply { isRootVisible = false; cellRenderer = ProjectRenderer() }
    private val cards = CardLayout()
    private val body = JPanel(cards)
    private var wholeProject = false
    private var syncing = false

    init {
        val toggle = panel {
            row {
                segmentedButton(listOf(false, true)) { text = if (it) "Whole project" else "Changed files" }
                    .apply { selectedItem = false }
                    .whenItemSelected { whole -> wholeProject = whole; showBody() }
            }
        }.apply { isOpaque = false }
        val head = stack(title, vgap(8), strip, vgap(6), row(viewedText), vgap(4), toggle).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.line), JBUI.Borders.empty(6, 12, 6, 12))
        }
        add(head, BorderLayout.NORTH)
        body.add(ScrollPaneFactory.createScrollPane(list, true), "pr")
        body.add(ScrollPaneFactory.createScrollPane(tree, true), "all")
        add(body)

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
                service.git?.root?.resolve(path.path)?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
                    ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            }
        })
        project.messageBus.connect(parent).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let(service::relativePath)?.let(::select)
            }
        })
    }

    fun refresh() {
        val meta = service.meta ?: return
        val pr = meta.target.prNumber?.let { "#$it " }.orEmpty()
        title.text = pr + meta.target.label.removePrefix(pr)
        val total = service.changes.size
        val done = service.reviewedCount()
        strip.fraction = if (total == 0) 0.0 else done.toDouble() / total
        viewedText.text = "$done of $total files viewed · ${meta.scopeLabel}"

        val selected = (list.selectedValue as? FileRow.File)?.change?.path
        model.clear()
        service.changes.groupBy { it.dir }.toSortedMap().forEach { (dir, files) ->
            if (dir.isNotEmpty()) model.addElement(FileRow.Dir(dir))
            files.forEach { model.addElement(FileRow.File(it, dir.isNotEmpty())) }
        }
        selected?.let(::select)
        if (wholeProject) buildTree()
    }

    private fun showBody() {
        if (wholeProject) buildTree()
        cards.show(body, if (wholeProject) "all" else "pr")
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
                    val counts = if (change.added == null) mutedLabel("binary") else row(
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

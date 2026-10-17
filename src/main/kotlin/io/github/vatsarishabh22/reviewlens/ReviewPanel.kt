package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

const val TOOL_WINDOW_ID = "Review"

class ReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ReviewPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** Header (what, scope, progress) over the changed files grouped by folder; selecting a file opens it. */
class ReviewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = ReviewService.of(project)
    private val title = JBLabel().apply { font = JBFont.label().asBold().biggerOn(1f) }
    private val scope = JBLabel().apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    private val progress = JProgressBar()
    private val progressText = JBLabel()
    private val root = DefaultMutableTreeNode()
    private val tree = Tree(DefaultTreeModel(root)).apply { isRootVisible = false; cellRenderer = FileRenderer() }
    private var syncing = false

    init {
        val actions = ActionManager.getInstance()
        val toolbar = actions.createActionToolbar("ReviewLens", actions.getAction("ReviewLens.Toolbar") as ActionGroup, true)
        toolbar.targetComponent = this
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 8, 8, 8)
            listOf(title, scope, progress, progressText).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it) }
        }
        add(JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.NORTH); add(header) }, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true))

        tree.addTreeSelectionListener {
            if (!syncing) ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FileChange)?.let { openChange(project, it) }
        }
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let(service::relativePath)?.let(::select)
            }
        })
        service.onChanged(this, ::refresh)
        refresh()
    }

    private fun refresh() {
        val meta = service.meta
        if (meta == null) { // no review in progress (step 3 replaces this with the PR list)
            title.text = "No review in progress"
            scope.text = "Use Review PR… to pick a pull request"
            progress.value = 0
            progressText.text = ""
            root.removeAllChildren()
            (tree.model as DefaultTreeModel).reload()
            return
        }
        val done = service.reviewedCount()
        val total = service.changes.size
        title.text = meta.target.label
        scope.text = "${meta.scopeLabel} · read-only"
        progress.maximum = maxOf(total, 1)
        progress.value = done
        progressText.text = "Reviewed $done of $total files"

        val selected = ((tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? FileChange)?.path
        root.removeAllChildren()
        service.changes.groupBy { it.dir }.forEach { (dir, files) ->
            root.add(DefaultMutableTreeNode(dir).apply { files.forEach { add(DefaultMutableTreeNode(it, false)) } })
        }
        (tree.model as DefaultTreeModel).reload()
        TreeUtil.expandAll(tree)
        selected?.let(::select)
    }

    /** Moves the tree selection to [path] without re-opening it. */
    private fun select(path: String) {
        val node = TreeUtil.treeNodeTraverser(root).find { ((it as DefaultMutableTreeNode).userObject as? FileChange)?.path == path }
            as? DefaultMutableTreeNode ?: return
        syncing = true
        TreeUtil.selectPath(tree, TreePath(node.path))
        syncing = false
    }

    override fun dispose() = Unit

    private inner class FileRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileChange -> {
                    val badge = service.badge(item)
                    icon = FileTypeManager.getInstance().getFileTypeByFileName(item.name).icon
                    append("${badge.symbol} ")
                    append(item.name, if (badge == Badge.REVIEWED) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.status}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, statusColor(item.status)))
                    val counts = if (item.added == null) "  binary" else "  +${item.added} −${item.deleted}"
                    append(counts, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    item.oldPath?.let { append("  ← ${it.substringAfterLast('/')}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                }
                is String -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.ifEmpty { "(root)" }, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        private fun statusColor(status: Char) = when (status) {
            'A' -> FileStatus.ADDED.color
            'D' -> FileStatus.DELETED.color
            else -> FileStatus.MODIFIED.color
        }
    }
}

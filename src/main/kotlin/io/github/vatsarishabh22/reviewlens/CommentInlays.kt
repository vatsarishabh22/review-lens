package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Inline comments during a PR review, as the prototype draws them: a "+" in the gutter of the changed line under the
 * mouse, a comment box under that line, and the drafts as cards under their lines. The box and cards are Swing
 * components embedded in the editor (EditorEmbeddedComponentManager, public, what JetBrains' notebooks build on).
 */
@Service(Service.Level.PROJECT)
class CommentInlays(private val project: Project) : Disposable {

    /**
     * The open comment box; its field outlives re-renders so typing survives a refresh. An EditorTextField (a small
     * editor of its own), not a text area: Enter and Backspace then act on it, never on the read-only file around it.
     */
    private class Box(val path: String, val line: Int, val text: EditorTextField, val editing: Draft? = null)

    private var box: Box? = null
    private val plus = HashMap<Editor, RangeHighlighter>()
    private val blocks = HashMap<Editor, List<Inlay<*>>>()

    init {
        val factory = EditorFactory.getInstance()
        factory.eventMulticaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = hover(e.editor, e.logicalPosition.line)
        }, this)
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = SwingUtilities.invokeLater { render(event.editor) }
            override fun editorReleased(event: EditorFactoryEvent) {
                plus.remove(event.editor); blocks.remove(event.editor)
            }
        }, this)
        ReviewService.of(project).onChanged(this) { editors().forEach(::render) }
    }

    private fun editors() = EditorFactory.getInstance().allEditors.filter { it.project == project && it.editorKind == EditorKind.MAIN_EDITOR }

    /** The PR file [editor] shows, when it is one this review changed; branch reviews have nowhere to post. */
    private fun changeIn(editor: Editor): FileChange? {
        val service = ReviewService.of(project)
        if (editor.project != project || editor.editorKind != EditorKind.MAIN_EDITOR || service.meta?.target?.prNumber == null) return null
        return FileDocumentManager.getInstance().getFile(editor.document)?.let(service::changeFor)
    }

    /** Moves the single "+" to [line] (0-based) when the review changed it, else hides it. */
    private fun hover(editor: Editor, line: Int) {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        val target = line.takeIf { changeIn(editor) != null && file != null && it in ReviewGutter.of(project).changedLines(file) }
        val current = plus[editor]
        if (current != null && current.isValid && editor.document.getLineNumber(current.startOffset) == target) return
        current?.let(editor.markupModel::removeHighlighter)
        plus.remove(editor)
        if (target == null) return
        plus[editor] = editor.markupModel.addLineHighlighter(target, HighlighterLayer.LAST, null).apply {
            gutterIconRenderer = PlusRenderer(target + 1) { open(editor, target + 1) }
        }
    }

    /** Opens the comment box under [line] (1-based), empty or holding the pending [editing] draft; one box at a time. */
    private fun open(editor: Editor, line: Int, editing: Draft? = null) {
        val change = changeIn(editor) ?: return
        val document = EditorFactory.getInstance().createDocument(editing?.body.orEmpty())
        box = Box(change.path, line, editing = editing, text = EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false).apply {
            setPlaceholder("Comment on line $line…")
            setShowPlaceholderWhenFocused(true)
            addSettingsProvider { it.settings.isUseSoftWraps = true; it.setBorder(JBUI.Borders.empty(8, 10)) }
            preferredSize = Dimension(WIDTH, JBUI.scale(64))
        })
        editors().forEach(::render)
        box?.text?.let { SwingUtilities.invokeLater { IdeFocusManager.getInstance(project).requestFocus(it, true) } }
    }

    private fun close() {
        box = null
        editors().forEach(::render)
    }

    /** Rebuilds [editor]'s comment blocks: one per line that has drafts or the open box. */
    private fun render(editor: Editor) {
        blocks.remove(editor)?.forEach(Disposer::dispose)
        val ex = editor as? EditorEx ?: return
        val change = changeIn(editor) ?: return
        val drafts = ReviewDrafts.of(project).on(change).groupBy { it.line }
        val open = box?.takeIf { it.path == change.path }
        val lines = (drafts.keys + listOfNotNull(open?.line)).filter { it in 1..editor.document.lineCount }.sorted()
        blocks[editor] = lines.mapNotNull { line ->
            EditorEmbeddedComponentManager.getInstance().addComponent(ex, block(editor, change, line, drafts[line].orEmpty(), open?.takeIf { it.line == line }),
                EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null, true, false, 0,
                    editor.document.getLineEndOffset(line - 1)))
        }
    }

    private fun block(editor: Editor, change: FileChange, line: Int, drafts: List<Draft>, open: Box?): JComponent {
        val comments = ReviewDrafts.of(project)
        val parts = drafts.filter { it != open?.editing }.map { draft -> // the draft being edited shows as the box instead
            val state = if (draft.posted) mutedLabel("on GitHub") else Pill("Pending", ReviewColors.review, ReviewColors.reviewFg)
            val who = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(row(JBLabel("You").apply { font = JBFont.label().asBold(); foreground = ReviewColors.editorText }, state), BorderLayout.WEST)
                if (!draft.posted) add(row(
                    ActionLink("Edit") { open(editor, line, draft) }.apply { font = JBFont.small() },
                    ActionLink("Delete") { comments.delete(draft) }.apply { font = JBFont.small() },
                ), BorderLayout.EAST)
            }
            stack(who, vgap(2), text(draft.body).apply { foreground = ReviewColors.editorText }).apply { border = JBUI.Borders.empty(8, 12) }
        }.toMutableList<JComponent>()
        if (open != null) {
            val add = JButton(if (open.editing != null) "Save" else "Add comment").apply {
                putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
                addActionListener {
                    val body = open.text.text.trim()
                    if (body.isEmpty()) return@addActionListener
                    box = null
                    open.editing?.let { comments.edit(it, body) } ?: comments.add(change, line, body)
                }
            }
            val cancel = JButton("Cancel").apply { addActionListener { close() } }
            listOf(add, cancel).forEach { it.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
            val actions = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                background = ReviewColors.surface
                border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), JBUI.Borders.empty(6, 10))
                add(mutedLabel("Posted when you finish the review"))
                add(row(cancel, add, gap = 8), BorderLayout.EAST)
            }
            parts += JPanel(BorderLayout()).apply { isOpaque = false; add(open.text); add(actions, BorderLayout.SOUTH) }
        }
        parts.drop(1).forEach { it.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), it.border) }
        val card = stack(*parts.toTypedArray()).apply {
            isOpaque = true
            background = ReviewColors.editorBackground
            border = RoundedLineBorder(ReviewColors.line, JBUI.scale(8))
        }
        card.preferredSize = Dimension(WIDTH, card.preferredSize.height)
        // the editor shows its text cursor over everything inside it; the card is not text to type in
        return JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(2, 0, 6, 0); cursor = Cursor.getDefaultCursor(); add(card) }
    }

    /** A wrapping, selectable comment text sized for the card's width. */
    private fun text(body: String) = wrappingText(body).apply { setSize(WIDTH - JBUI.scale(24), Short.MAX_VALUE.toInt()) }

    override fun dispose() = Unit

    /** The prototype's "+": an accent square with a white plus; clicking opens the comment box. */
    private class PlusRenderer(private val line: Int, private val onClick: () -> Unit) : GutterIconRenderer() {
        override fun getIcon(): Icon = PLUS
        override fun getTooltipText() = "Comment on line $line"
        override fun isNavigateAction() = true
        override fun getClickAction(): AnAction = DumbAwareAction.create { onClick() }
        override fun equals(other: Any?) = other is PlusRenderer && other.line == line
        override fun hashCode() = line
    }

    companion object {
        private val WIDTH get() = JBUI.scale(620)

        private val PLUS: Icon = object : Icon {
            override fun getIconWidth() = JBUI.scale(16)
            override fun getIconHeight() = JBUI.scale(16)
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ReviewColors.accent
                g2.fillRoundRect(x, y, iconWidth, iconHeight, JBUI.scale(4), JBUI.scale(4))
                g2.color = Color.WHITE
                val mid = JBUI.scale(8); val arm = JBUI.scale(4); val t = JBUI.scale(2)
                g2.fillRect(x + mid - arm, y + mid - t / 2, arm * 2, t)
                g2.fillRect(x + mid - t / 2, y + mid - arm, t, arm * 2)
                g2.dispose()
            }
        }

        fun of(project: Project): CommentInlays = project.service()
    }
}

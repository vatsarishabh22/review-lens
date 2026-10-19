package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.ui.BrowserHyperlinkListener
import javax.swing.JEditorPane
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.keymap.KeymapUtil
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
 * mouse, a comment box under that line, everyone's GitHub threads and your drafts as cards under their lines, and one
 * strip above line 1 counting the threads this version can't place. The box and cards are Swing components embedded
 * in the editor (EditorEmbeddedComponentManager, public, what JetBrains' notebooks build on).
 */
@Service(Service.Level.PROJECT)
class CommentInlays(private val project: Project) : Disposable {

    /**
     * The open comment box; its field outlives re-renders so typing survives a refresh. An EditorTextField (a small
     * editor of its own), not a text area: Enter and Backspace then act on it, never on the read-only file around it.
     */
    private class Box(val change: FileChange, val line: Int, val text: EditorTextField, val editing: Draft? = null, val thread: ReviewThread? = null)

    private var box: Box? = null
    private val plus = HashMap<Editor, RangeHighlighter>()
    private val blocks = HashMap<Editor, List<Inlay<*>>>()
    /** What each editor's blocks were built from: a refresh that changes none of it (a tick, a sync) leaves them, and the box's focus, alone. */
    private val shown = HashMap<Editor, Any>()
    /** Resolved threads the reviewer unfolded, by URL. */
    private val unfolded = HashSet<String>()

    init {
        val factory = EditorFactory.getInstance()
        factory.eventMulticaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = hover(e.editor, e.logicalPosition.line)
        }, this)
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = SwingUtilities.invokeLater { render(event.editor) }
            override fun editorReleased(event: EditorFactoryEvent) {
                plus.remove(event.editor); blocks.remove(event.editor); shown.remove(event.editor)
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

    /** Opens the comment box under [line] (1-based), or inside [thread]'s card as a reply, empty or holding the pending [editing] draft; one box at a time. */
    private fun open(editor: Editor, line: Int, editing: Draft? = null, thread: ReviewThread? = null) {
        val change = changeIn(editor) ?: return
        if (box?.text?.text?.isNotBlank() == true) return backToBox()
        val document = EditorFactory.getInstance().createDocument(editing?.body.orEmpty())
        val field = EditorTextField(document, project, PlainTextFileType.INSTANCE, false, false).apply {
            setPlaceholder(thread?.let { "Reply to ${it.comments[0].author}…" } ?: "Comment on line $line…")
            setShowPlaceholderWhenFocused(true)
            // wraps like prose: no wrap arrows, no hanging indent
            addSettingsProvider { it.settings.isUseSoftWraps = true; it.settings.isPaintSoftWraps = false; it.settings.isUseCustomSoftWrapIndent = true; it.settings.customSoftWrapIndent = 0; it.setBorder(JBUI.Borders.empty(8, 10))
                it.setVerticalScrollbarVisible(true) } // past MAX_BOX the text scrolls instead of disappearing
            preferredSize = Dimension(WIDTH, MIN_BOX)
        }
        DumbAwareAction.create { save() }.registerCustomShortcutSet(CommonShortcuts.getCtrlEnter(), field)
        DumbAwareAction.create { close() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, field)
        field.addDocumentListener(object : DocumentListener { override fun documentChanged(event: DocumentEvent) = grow(field) })
        box = Box(change, line, field, editing, thread)
        editors().forEach(::render)
        SwingUtilities.invokeLater { grow(field) } // an edited draft opens at its full height
        box?.text?.let { SwingUtilities.invokeLater { IdeFocusManager.getInstance(project).requestFocus(it, true) } }
    }

    /** One box at a time, never by throwing away what is typed in it: show the open box again, focused, and say why. */
    private fun backToBox() {
        val open = box ?: return
        val shown = editors().firstOrNull { changeIn(it)?.path == open.change.path }
        if (shown == null) openPath(project, open.change.path, line = open.line) // its file was closed: open it there
        else {
            shown.scrollingModel.scrollTo(LogicalPosition(open.line - 1, 0), ScrollType.CENTER)
            IdeFocusManager.getInstance(project).requestFocus(open.text, true)
        }
        notify(project, "Finish this comment first", "Add it or cancel it, then start another.")
    }

    private fun close() {
        box = null
        editors().forEach(::render)
    }

    /** Adds the box's comment (or saves the edited draft); an empty box stays open. */
    private fun save() {
        val open = box ?: return
        val body = open.text.text.trim()
        if (body.isEmpty()) return
        box = null
        val comments = ReviewDrafts.of(project)
        when {
            open.editing != null -> comments.edit(open.editing, body)
            open.thread != null -> comments.reply(open.change, open.line, open.thread, body)
            else -> comments.add(open.change, open.line, body)
        }
    }

    /** The box grows with its text from [MIN_BOX] to [MAX_BOX], then scrolls; the embedded block is resized so the line below moves with it. */
    private fun grow(field: EditorTextField) {
        val editor = field.editor ?: return
        val lines = editor.offsetToVisualPosition(editor.document.textLength).line + 1
        val height = (lines * editor.lineHeight + JBUI.scale(16)).coerceIn(MIN_BOX, MAX_BOX)
        if (field.preferredSize.height == height) return
        field.preferredSize = Dimension(WIDTH, height)
        var block: Component = field
        while (block.parent != null && block.parent !is EditorComponentImpl) block = block.parent
        if (block.parent == null) return
        block.size = Dimension(block.width, block.preferredSize.height)
        block.revalidate()
    }

    /** Rebuilds [editor]'s comment blocks (one per line with threads, drafts or the open box) when what they show changed. */
    private fun render(editor: Editor) {
        val ex = editor as? EditorEx
        val change = ex?.let(::changeIn)
        if (ex == null || change == null) {
            blocks.remove(editor)?.forEach(Disposer::dispose); shown.remove(editor)
            return
        }
        val drafts = ReviewDrafts.of(project).on(change).groupBy { it.line }
        val threads = ReviewService.of(project).threadsOn(change)
        val placed = threads.mapNotNull { (thread, line) -> line?.let { it to thread } }.groupBy({ it.first }, { it.second })
        val listedOnly = threads.count { it.second == null }
        val open = box?.takeIf { it.change.path == change.path }
        val replies = placed.values.flatten().associate { it.id to ReviewDrafts.of(project).repliesTo(it) }
        val key = listOf(change, drafts, placed, replies, listedOnly, open, unfolded.toSet(), editor.document.lineCount)
        if (shown[editor] == key && editor in blocks) return
        shown[editor] = key
        blocks.remove(editor)?.forEach(Disposer::dispose)
        val manager = EditorEmbeddedComponentManager.getInstance()
        fun at(offset: Int, above: Boolean) = EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null, !above, above, 0, offset)
        val lines = (drafts.keys + placed.keys + listOfNotNull(open?.takeIf { it.thread == null }?.line)).filter { it in 1..editor.document.lineCount }.toSortedSet()
        blocks[editor] = lines.mapNotNull { line ->
            manager.addComponent(ex, block(editor, line, drafts[line].orEmpty(), placed[line].orEmpty(), open?.takeIf { it.line == line }),
                at(editor.document.getLineEndOffset(line - 1), false))
        } + listOfNotNull(listedOnly.takeIf { it > 0 }?.let { manager.addComponent(ex, outdatedStrip(it), at(0, true)) })
    }

    private fun block(editor: Editor, line: Int, drafts: List<Draft>, threads: List<ReviewThread>, open: Box?): JComponent {
        val cards = threads.map { threadCard(editor, line, it, open?.takeIf { box -> box.thread?.id == it.id }) } +
            listOfNotNull(mineCard(editor, line, drafts, open?.takeIf { it.thread == null }))
        val column = stack(*cards.flatMapIndexed { i, card -> if (i == 0) listOf(card) else listOf(vgap(6), card) }.toTypedArray())
        // the width is fixed here, the height left to the cards, so a growing box moves the line below it
        val width = WIDTH // inside a JPanel, WIDTH would be Swing's ImageObserver.WIDTH (1)
        return object : JPanel(BorderLayout()) { override fun getPreferredSize() = Dimension(width, super.getPreferredSize().height) }.apply {
            isOpaque = false; border = JBUI.Borders.empty(2, 0, 6, 0)
            cursor = Cursor.getDefaultCursor() // the editor shows its text cursor over everything inside it; the cards are not text to type in
            add(column)
        }
    }

    /**
     * Someone's GitHub thread: open ones show the conversation, your pending replies and a Reply bar (or the reply [open]
     * box); resolved ones fold to one line until clicked, unless you have a reply waiting on them.
     */
    private fun threadCard(editor: Editor, line: Int, thread: ReviewThread, open: Box?): JComponent {
        val drafts = ReviewDrafts.of(project)
        val mine = drafts.repliesTo(thread)
        val look = CommentLook.of(thread.state)
        fun author(i: Int) = if (i == 0 && thread.mine) "You" else thread.comments[i].author
        fun bold(text: String) = JBLabel(text).apply { font = JBFont.label().asBold(); foreground = ReviewColors.editorText }
        val card = if (thread.resolved && thread.url !in unfolded && mine.isEmpty()) {
            val more = thread.comments.size - 1
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                border = JBUI.Borders.empty(6, 12)
                isOpaque = false
                add(row(Pill(look.label, look.fill, look.fg), bold(author(0))), BorderLayout.WEST)
                add(mutedLabel(previewLine(thread.comments[0].body)))
                add(mutedLabel(if (more > 0) "+$more ▸" else "▸"), BorderLayout.EAST)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Show the resolved thread"
                addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { unfolded += thread.url; editors().forEach(::render) } })
            }
        } else {
            val head = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(row(bold(author(0)), Pill(look.label, look.fill, look.fg)), BorderLayout.WEST)
                add(row(*listOfNotNull(
                    ActionLink("Fold") { unfolded -= thread.url; editors().forEach(::render) }.takeIf { thread.resolved && mine.isEmpty() },
                    ActionLink("Open on GitHub ↗") { BrowserUtil.browse(thread.url) },
                ).onEach { it.font = JBFont.small() }.toTypedArray(), gap = 12), BorderLayout.EAST)
            }
            val replies = thread.comments.drop(1).mapIndexed { i, reply ->
                stack(bold(author(i + 1)), vgap(2), markdown(reply.body, JBUI.scale(40))).apply {
                    isOpaque = true; background = ReviewColors.surface
                    border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), JBUI.Borders.empty(6, 28, 6, 12))
                }
            }
            fun indented(part: JComponent) = part.apply {
                isOpaque = true; background = ReviewColors.surface
                border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), JBUI.Borders.empty(6, 28, 6, 12))
            }
            val pending = mine.filter { it != open?.editing }.map { draft ->
                indented(stack(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(row(bold("You"), CommentLook.PENDING.let { Pill(it.label, it.fill, it.fg) }), BorderLayout.WEST)
                    add(row(ActionLink("Edit") { open(editor, line, draft, thread) }, ActionLink("Delete") { drafts.delete(draft) }).apply {
                        components.filterIsInstance<ActionLink>().forEach { it.font = JBFont.small() }
                    }, BorderLayout.EAST)
                }, vgap(2), text(draft.body).apply { foreground = ReviewColors.editorText }))
            }
            // Reply is offered only on open threads, so a reply waiting on a resolved one means it was resolved since
            val raced = (thread.resolved && mine.isNotEmpty()).takeIf { it }?.let {
                indented(wrappingText("${thread.resolvedBy ?: "Someone"} resolved this after you replied. Your reply still posts; the thread stays resolved.", JBFont.small())
                    .apply { foreground = ReviewColors.warn; setSize(WIDTH - JBUI.scale(40), Short.MAX_VALUE.toInt()) })
            }
            val tail = when {
                open != null -> boxPanel(open, "Reply").apply { border = JBUI.Borders.customLineTop(ReviewColors.line) }
                thread.state == ThreadState.OPEN -> replyBar { open(editor, line, null, thread) }
                else -> null
            }
            stack(stack(head, vgap(2), markdown(thread.comments[0].body, JBUI.scale(24))).apply { border = JBUI.Borders.empty(8, 12) },
                *replies.toTypedArray(), *pending.toTypedArray(), *listOfNotNull(raced, tail).toTypedArray())
        }
        return card.apply {
            isOpaque = true
            background = ReviewColors.editorBackground
            border = JBUI.Borders.compound(RoundedLineBorder(ReviewColors.line, JBUI.scale(8)), JBUI.Borders.customLine(look.stripe, 0, 3, 0, 0), border)
        }
    }

    /** A comment body as GitHub renders it (markdown, links open in the browser), sized to the card's width less [inset]. */
    private fun markdown(body: String, inset: Int) = JEditorPane().apply {
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.label(); foreground = ReviewColors.editorText
        isEditable = false; isOpaque = false; isFocusable = false; border = null
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
        editorKit = markdownKit(); text = markdownHtml(body).orEmpty()
        setSize(WIDTH - inset, Short.MAX_VALUE.toInt())
    }

    /** Above line 1: the threads on this file the version shown can't place (outdated, on removed lines, or on a newer push). */
    private fun outdatedStrip(count: Int): JComponent {
        val threads = if (count == 1) "1 thread on this file isn't" else "$count threads on this file aren't"
        val show = ActionLink("Show in Comments") {
            val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@ActionLink
            window.activate { UIUtil.findComponentOfType(window.component, ChangedFilesView::class.java)?.showComments() }
        }.apply { font = JBFont.small() }
        val strip = row(JBLabel("$threads on this version: written on an older push or on removed lines.", AllIcons.General.Balloon, JBLabel.LEFT)
            .apply { foreground = ReviewColors.muted; font = JBFont.small() }, show).apply {
            border = JBUI.Borders.empty(4, 10)
            isOpaque = true; background = ReviewColors.surface
        }
        return JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(2, 0, 4, 0); cursor = Cursor.getDefaultCursor(); add(strip) }
    }

    /** Your drafts on [line] and the open box, in one card; null when there are neither. */
    private fun mineCard(editor: Editor, line: Int, drafts: List<Draft>, open: Box?): JComponent? {
        if (drafts.isEmpty() && open == null) return null
        val comments = ReviewDrafts.of(project)
        val parts = drafts.filter { it != open?.editing }.map { draft -> // the draft being edited shows as the box instead
            val state = if (draft.posted) mutedLabel(CommentLook.POSTED.label) else CommentLook.PENDING.let { Pill(it.label, it.fill, it.fg) }
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
        if (open != null) parts += boxPanel(open, "Add comment")
        parts.drop(1).forEach { it.border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), it.border) }
        val card = stack(*parts.toTypedArray()).apply {
            isOpaque = true
            background = ReviewColors.editorBackground
            border = RoundedLineBorder(ReviewColors.line, JBUI.scale(8))
        }
        return card
    }

    /** The open box with Cancel and [verb] (Save when editing), and the keys that do the same. */
    private fun boxPanel(open: Box, verb: String): JComponent {
        val add = JButton(if (open.editing != null) "Save" else verb).apply {
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            addActionListener { save() }
        }
        val cancel = JButton("Cancel").apply { addActionListener { close() } }
        listOf(add, cancel).forEach { it.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
        val actions = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            background = ReviewColors.surface
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), JBUI.Borders.empty(6, 10))
            add(mutedLabel("Posted when you finish the review · ${KeymapUtil.getShortcutsText(CommonShortcuts.getCtrlEnter().shortcuts)} ${verb.lowercase().substringBefore(' ')} · Esc cancel"))
            add(row(cancel, add, gap = 8), BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply { isOpaque = false; add(open.text); add(actions, BorderLayout.SOUTH) }
    }

    /** The prototype's "Reply…" bar at the foot of an open thread: looks like an empty box, opens the real one. */
    private fun replyBar(onClick: () -> Unit): JComponent {
        val bar = mutedLabel("Reply…", small = false).apply {
            border = JBUI.Borders.compound(RoundedLineBorder(ReviewColors.line, JBUI.scale(6)), JBUI.Borders.empty(4, 10))
            isOpaque = false
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), JBUI.Borders.empty(6, 12))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(bar)
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onClick() })
        }
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
        private val MIN_BOX get() = JBUI.scale(64)
        private val MAX_BOX get() = JBUI.scale(260)

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

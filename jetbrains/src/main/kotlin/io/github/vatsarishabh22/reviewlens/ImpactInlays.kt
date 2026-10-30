package io.github.vatsarishabh22.reviewlens

import com.intellij.find.FindManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The impact map in the editor, as the prototype draws it (spec/impact.md, "What the reviewer sees"): a chip after each
 * changed function's name line, its peek under the line when unfolded, and above line 1 either the removed functions
 * still in use or "Impact not available". What to show comes from [ImpactService]; ticks go to [ReviewMeta.checked].
 */
@Service(Service.Level.PROJECT)
class ImpactInlays(private val project: Project) : Disposable {

    private val inlays = HashMap<Editor, List<Inlay<*>>>()
    /** What each editor's inlays were built from: a refresh that changes none of it leaves them, and their scroll, alone. */
    private val shown = HashMap<Editor, Any>()
    /** Functions whose peek is unfolded, by tick key. */
    private val unfolded = HashSet<String>()
    /** The place ⌥I moved to (function key to place key), shaded in its peek. */
    private var focus: Pair<String, String>? = null
    /** The peek that says "Calls made dynamically may not show": the first unfolded on such a file, once per review. */
    private var noteOn: String? = null
    private var review: String? = null

    init {
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = SwingUtilities.invokeLater { render(event.editor) }
            override fun editorReleased(event: EditorFactoryEvent) { inlays.remove(event.editor); shown.remove(event.editor) }
        }, this)
        factory.eventMulticaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val chip = e.inlay?.renderer as? ChipRenderer ?: return
                e.consume()
                chip.onClick()
            }
        }, this)
        factory.eventMulticaster.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                (e.editor as? EditorEx)?.setCustomCursor(this@ImpactInlays, if (e.inlay?.renderer is ChipRenderer) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
            }
        }, this)
        ReviewService.of(project).onChanged(this) { renderAll() }
        ImpactService.of(project).onChanged(this) { renderAll() }
    }

    override fun dispose() = Unit

    private fun renderAll() {
        val key = ReviewService.of(project).meta?.target?.key
        if (key != review) { review = key; unfolded.clear(); focus = null; noteOn = null }
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach(::render)
    }

    /** The changed file [editor] shows: a review tab, or the diff of a deleted file (its only view); else null. */
    private fun pathIn(editor: Editor): String? {
        val service = ReviewService.of(project)
        if (editor.project != project || !service.isActive) return null
        return when (editor.editorKind) {
            EditorKind.MAIN_EDITOR -> FileDocumentManager.getInstance().getFile(editor.document)?.let(service::changeFor)?.path
            EditorKind.DIFF -> editor.document.getUserData(DELETED_FILE)
            else -> null
        }
    }

    private fun render(editor: Editor) {
        val ex = editor as? EditorEx ?: return
        val path = pathIn(editor)
        val impact = path?.let { ImpactService.of(project).of(it) }
        val checked = ReviewService.of(project).meta?.checked
        val key = listOf(impact, unfolded.toSet(), checked, focus, noteOn, editor.document.lineCount)
        if (impact == null) {
            inlays.remove(editor)?.forEach(Disposer::dispose); shown.remove(editor)
            return
        }
        if (shown[editor] == key && editor in inlays) return
        shown[editor] = key
        inlays.remove(editor)?.forEach(Disposer::dispose)
        val manager = EditorEmbeddedComponentManager.getInstance()
        fun at(offset: Int, above: Boolean) = EditorEmbeddedComponentManager.Properties(EditorEmbeddedComponentManager.ResizePolicy.none(), null, !above, above, 0, offset)
        val added = ArrayList<Inlay<*>>()
        when (impact) {
            is ImpactService.FileImpact.Unavailable -> manager.addComponent(ex, strip(mutedLabel("Impact not available for ${impact.language} in this IDE")), at(0, true))?.let(added::add)
            is ImpactService.FileImpact.Ready -> {
                val (removed, kept) = impact.impacts.partition { it.change.kind == ChangeKind.REMOVED }
                if (editor.editorKind == EditorKind.MAIN_EDITOR) for (fn in kept) {
                    val chip = chipOf(fn, ticks(fn)) ?: continue
                    val line = fn.change.declaration.nameLine - 1
                    if (line !in 0 until editor.document.lineCount) continue
                    val end = editor.document.getLineEndOffset(line)
                    editor.inlayModel.addAfterLineEndElement(end, true, ChipRenderer(chip, fn.key in unfolded) { clicked(editor, impact.language, fn, chip) })?.let(added::add)
                    if (fn.key in unfolded && chip.foldable) manager.addComponent(ex, peek(editor, impact.language, fn), at(end, false))?.let(added::add)
                }
                val shownRemoved = removed.mapNotNull { fn -> chipOf(fn, ticks(fn))?.let { fn to it } }
                if (shownRemoved.isNotEmpty()) {
                    val rows = shownRemoved.flatMap { (fn, chip) ->
                        // its own panel, never a row: a BoxLayout row squeezed the chip to half its height
                        listOfNotNull(JPanel(BorderLayout()).apply { isOpaque = false; add(ChipButton(chip, fn.key in unfolded) { clicked(editor, impact.language, fn, chip) }, BorderLayout.WEST) },
                            if (fn.key in unfolded && chip.foldable) peek(editor, impact.language, fn) else null)
                    }
                    manager.addComponent(ex, strip(*rows.toTypedArray()), at(0, true))?.let(added::add)
                }
            }
        }
        inlays[editor] = added
    }

    private fun ticks(fn: Impact) = ReviewService.of(project).meta?.checkedPlaces(fn.key, fn.change.declaration.hash).orEmpty()

    /** A chip click: "Open Find Usages" on a stopped search, else the peek folds or unfolds. */
    private fun clicked(editor: Editor, language: ImpactLanguage, fn: Impact, chip: Chip) {
        when {
            chip.link != null -> findUsages(editor, language, fn)
            chip.foldable -> { if (!unfolded.remove(fn.key)) unfold(language, fn); focus = null; renderAll() }
        }
    }

    private fun unfold(language: ImpactLanguage, fn: Impact) {
        unfolded += fn.key
        if (noteOn == null && language.dynamicCalls) noteOn = fn.key
    }

    /** The places outside the PR, in Code and Tests, as the peek lists them under the function's line. */
    private fun peek(editor: Editor, language: ImpactLanguage, fn: Impact): JComponent {
        val uses = fn.uses as Uses.Found
        val list = uses.checklist()
        val ticked = ticks(fn)
        val head = JPanel(BorderLayout()).apply {
            isOpaque = true; background = ReviewColors.surface; border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.line), JBUI.Borders.empty(5, 12))
            add(titleText("Outside this PR", " · ${list.count { it.key in ticked }} of ${list.size} checked", JBFont.small()).apply { isOpaque = false }, BorderLayout.WEST)
            add(mutedLabel("⌥I next place"), BorderLayout.EAST)
        }
        val parts = mutableListOf<JComponent>(head)
        for ((label, group) in listOf("Code" to uses.code, "Tests" to uses.tests)) {
            when (group) {
                is PlaceGroup.Listed -> if (group.places.isNotEmpty()) {
                    parts += sectionLabel(label).apply { border = JBUI.Borders.empty(6, 12, 0, 12) }
                    group.places.forEach { parts += place(editor, fn, it, it.key in ticked) }
                }
                is PlaceGroup.Wide -> {
                    parts += sectionLabel(label).apply { border = JBUI.Borders.empty(6, 12, 0, 12) }
                    parts += row(mutedLabel("Widely used: ${group.count} ${label.lowercase()}"),
                        ActionLink("Open Find Usages") { findUsages(editor, language, fn) }.apply { font = JBFont.small() }).apply { border = JBUI.Borders.empty(4, 12, 8, 12) }
                }
            }
        }
        if (noteOn == fn.key) parts += JPanel(BorderLayout()).apply { // a panel, so the divider spans the card, not the text
            isOpaque = false; border = JBUI.Borders.compound(JBUI.Borders.customLineTop(ReviewColors.line), JBUI.Borders.empty(6, 12))
            add(mutedLabel("Calls made dynamically may not show"))
        }
        val card = stack(*parts.toTypedArray()).apply {
            isOpaque = true; background = ReviewColors.editorBackground
            border = RoundedLineBorder(ReviewColors.line, JBUI.scale(8)) // as the comment cards
        }
        val width = JBUI.scale(PEEK_WIDTH)
        return object : JPanel(BorderLayout()) { override fun getPreferredSize() = Dimension(width, super.getPreferredSize().height) }.apply {
            isOpaque = false; border = JBUI.Borders.empty(2, 0, 6, 0); cursor = Cursor.getDefaultCursor()
            add(card)
        }
    }

    /** One place: its tick, `path:line ↗`, then the use's line and the one after, the use shaded. */
    private fun place(editor: Editor, fn: Impact, place: Place, ticked: Boolean): JComponent {
        val box = JBCheckBox(null, ticked).apply {
            isOpaque = false; toolTipText = "Checked"
            addActionListener { ReviewService.of(project).update { it.tick(fn.key, fn.change.declaration.hash, place.key, isSelected) } }
        }
        val where = row(JBLabel("${place.path}:${place.line}").apply { font = codeFont(editor) },
            ActionLink("↗") { openPath(project, place.path, line = place.line, columns = place.columns) }.apply { toolTipText = "Open, read-only" }, gap = 6)
        val indent = place.lines.filter { it.isNotBlank() }.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0
        val snippet = stack(*place.lines.mapIndexed { i, text ->
            SimpleColoredComponent().apply {
                font = codeFont(editor); isOpaque = false; ipad = JBUI.emptyInsets()
                val line = text.drop(indent)
                if (i == 0) {
                    val from = (place.columns.first - indent).coerceIn(0, line.length)
                    val to = (place.columns.last + 1 - indent).coerceIn(from, line.length)
                    append(line.substring(0, from)); append(line.substring(from, to), SimpleTextAttributes(ReviewColors.accentSoft, null, null, SimpleTextAttributes.STYLE_OPAQUE))
                    append(line.substring(to))
                } else append(line)
            }
        }.toTypedArray())
        val focused = focus == fn.key to place.key
        return JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = focused; background = ReviewColors.accentSoft; border = JBUI.Borders.empty(4, 12, 6, 12)
            add(JPanel(BorderLayout()).apply { isOpaque = false; add(box, BorderLayout.NORTH) }, BorderLayout.WEST)
            add(stack(where, snippet), BorderLayout.CENTER)
        }
    }

    /** The IDE's own Find Usages on the function, for a search that stopped or a group too wide to list. */
    private fun findUsages(editor: Editor, language: ImpactLanguage, fn: Impact) {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        ReadAction.nonBlocking<PsiElement?> {
            PsiManager.getInstance(project).findFile(file)?.let { FunctionScanner.functions(it, language).toMap()[fn.change.name] }
        }.finishOnUiThread(ModalityState.defaultModalityState()) { it?.let(FindManager.getInstance(project)::findUsages) }
            .expireWith(this).submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * ⌥I: unfolds the next function with a place left to check, starting at the one under the caret and going down the
     * file (the removed ones last), and shades that place; says so when every place in the file is checked.
     */
    fun nextPlace(editor: Editor) {
        val path = pathIn(editor) ?: return
        val ready = ImpactService.of(project).of(path) as? ImpactService.FileImpact.Ready ?: return
        val (removed, kept) = ready.impacts.partition { it.change.kind == ChangeKind.REMOVED }
        val inFile = kept.sortedBy { it.change.declaration.nameLine }
        val caret = editor.caretModel.logicalPosition.line + 1
        val start = inFile.indexOfLast { it.change.declaration.lines.first <= caret }.coerceAtLeast(0)
        for (fn in inFile.drop(start) + inFile.take(start) + removed) {
            val next = (fn.uses as? Uses.Found)?.checklist()?.firstOrNull { it.key !in ticks(fn) } ?: continue
            unfold(ready.language, fn)
            focus = fn.key to next.key
            renderAll()
            if (fn.change.kind != ChangeKind.REMOVED) {
                editor.caretModel.moveToLogicalPosition(LogicalPosition(fn.change.declaration.nameLine - 1, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            } else editor.scrollingModel.scrollTo(LogicalPosition(0, 0), ScrollType.MAKE_VISIBLE)
            return
        }
        notify(project, "Every place is checked", "Nothing left to check in this file.", NotificationType.INFORMATION)
    }

    private fun codeFont(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scaleFontSize(12f).toFloat())

    /** Above line 1, full width: the removed functions' chips and peeks, or the "not available" line. */
    private fun strip(vararg parts: JComponent): JComponent {
        val body = stack(*parts.flatMapIndexed { i, part -> if (i == 0) listOf(part) else listOf(vgap(4), part) }.toTypedArray()).apply {
            isOpaque = true; background = ReviewColors.surface; border = JBUI.Borders.empty(4, 10)
        }
        return JPanel(BorderLayout()).apply { isOpaque = false; border = JBUI.Borders.empty(2, 0, 4, 0); cursor = Cursor.getDefaultCursor(); add(body) }
    }

    /** Draws a chip: hollow or filled dots for the checklist, the text, a link, and ⌄ / ⌃ when it folds. */
    private class ChipPaint(val chip: Chip, val open: Boolean) {
        private val font: Font = JBFont.small()
        private val dot = JBUI.scale(7)
        private val gap = JBUI.scale(3)
        private val pad = JBUI.scale(8)
        private val fold = if (chip.foldable) if (open) " ⌃" else " ⌄" else ""

        fun width(c: JComponent): Int {
            val fm = c.getFontMetrics(font)
            val dots = if (chip.dots.isEmpty()) 0 else chip.dots.size * (dot + gap) + gap
            return pad * 2 + dots + fm.stringWidth(chip.text + (chip.link ?: "") + fold)
        }

        fun height(c: JComponent) = c.getFontMetrics(font).height + JBUI.scale(2)

        fun paint(g: Graphics, c: JComponent, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                if (chip.busy || chip.done) g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, if (chip.busy) .6f else .75f)
                val w = width(c); val h = height(c); val arc = h.toDouble()
                g2.color = ReviewColors.surface
                g2.fill(RoundRectangle2D.Double(x + .5, y + .5, w - 1.0, h - 1.0, arc, arc))
                g2.color = ReviewColors.line
                g2.draw(RoundRectangle2D.Double(x + .5, y + .5, w - 1.0, h - 1.0, arc, arc))
                var cx = x + pad
                val mid = y + h / 2.0
                g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
                for (ticked in chip.dots) {
                    g2.color = if (chip.done) ReviewColors.ok else ReviewColors.accent
                    val circle = Ellipse2D.Double(cx.toDouble(), mid - dot / 2.0, dot - 1.0, dot - 1.0)
                    if (ticked) g2.fill(circle) else g2.draw(circle)
                    cx += dot + gap
                }
                if (chip.dots.isNotEmpty()) cx += gap
                g2.font = font
                val fm = g2.fontMetrics
                val base = (y + (h - fm.height) / 2 + fm.ascent)
                g2.color = ReviewColors.muted
                g2.drawString(chip.text, cx, base); cx += fm.stringWidth(chip.text)
                chip.link?.let { g2.color = ReviewColors.accent; g2.drawString(it, cx, base); cx += fm.stringWidth(it) }
                g2.color = ReviewColors.muted
                g2.drawString(fold, cx, base)
            } finally {
                g2.dispose()
            }
        }
    }

    /** The chip after a function's name line, painted by the editor; clicks reach it through the mouse listener above. */
    private class ChipRenderer(chip: Chip, open: Boolean, val onClick: () -> Unit) : EditorCustomElementRenderer {
        private val chipPaint = ChipPaint(chip, open)
        private val margin = JBUI.scale(18)

        override fun calcWidthInPixels(inlay: Inlay<*>) = margin + chipPaint.width(inlay.editor.contentComponent)

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val c = inlay.editor.contentComponent
            chipPaint.paint(g, c, targetRegion.x + margin, targetRegion.y + (targetRegion.height - chipPaint.height(c)) / 2)
        }
    }

    /** The same chip as a component, for the strip above line 1. */
    private class ChipButton(chip: Chip, open: Boolean, onClick: () -> Unit) : JComponent() {
        private val chipPaint = ChipPaint(chip, open)

        init {
            cursor = Cursor.getPredefinedCursor(if (chip.foldable || chip.link != null) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onClick() })
        }

        override fun getPreferredSize() = Dimension(chipPaint.width(this), chipPaint.height(this))
        override fun getMinimumSize() = preferredSize
        override fun getMaximumSize() = preferredSize
        override fun paintComponent(g: Graphics) = chipPaint.paint(g, this, 0, 0)
    }

    companion object {
        /** Marks a deleted file's diff document with its path: its removed functions' strip goes there, the file has no tab. */
        val DELETED_FILE = Key.create<String>("review-lens.deleted-file")
        private const val PEEK_WIDTH = 620

        fun of(project: Project): ImpactInlays = project.service()
    }
}

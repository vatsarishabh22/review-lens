package io.github.vatsarishabh22.reviewlens

import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.OverlayLayout
import javax.swing.SwingConstants

/** One PR of a stack as the card shows it: its row, its review so far, its own file count (null while unknown). */
data class StackRow(val listed: ListedTarget, val progress: ReviewProgress?, val files: Int?) {
    val done get() = progress?.done == true
}

/** The stack's next PR to review: the lowest not reviewed, bottom first. */
fun nextInStack(rows: List<StackRow>): StackRow? = rows.firstOrNull { !it.done }

/** "on #p" for a row whose parent isn't the row above it: the second of two PRs on one parent. */
fun builtOn(rows: List<StackRow>, index: Int): Int? =
    rows[index].listed.parent?.number?.takeIf { index > 0 && it != rows[index - 1].listed.pr?.number }

/**
 * A stack in the PR list (spec/README.md, Stacks; the prototype's `.stack`): the bottom PR's title, "Stack of n PRs",
 * "r of n reviewed" and one bar segment per PR, then every PR on a rail in build order. Past about seven rows it scrolls
 * inside, so a long stack never pushes the other PRs out of the list; it opens at the next PR to review, or at
 * [scrolledTo] when the list is drawn again; what scrolled out is counted at its edges.
 */
class StackCard(rows: List<StackRow>, scrolledTo: Int?, onPick: (ListedTarget) -> Unit) : JPanel(BorderLayout()) {
    private val lines = ScrollStack(JBUI.Borders.empty(4, 6))
    private val scroll = object : JBScrollPane(lines) {
        // ponytail: about seven rows (the prototype's 322 px), not a setting
        override fun getPreferredSize() = super.getPreferredSize().apply { height = minOf(height, JBUI.scale(322)) }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }
    private val above = edge { at(scroll.viewport.viewPosition.y - scroll.viewport.height + JBUI.scale(60)) }
    private val below = edge { at(scroll.viewport.viewPosition.y + scroll.viewport.height - JBUI.scale(60)) }
    private val railRows: List<RailRow>

    /** The stack's bottom PR, which names it across redraws. */
    val bottom = rows.first().listed.pr!!.number

    /** Where the rows are scrolled, for drawing the list again without moving them; null until first placed. */
    val scrolled get() = scroll.viewport.viewPosition.y.takeIf { placed }
    private var placed = false

    init {
        val next = nextInStack(rows)
        val reviewed = rows.count { it.done }
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.compound(JBUI.Borders.empty(2, 8, 8, 8), RoundedLineBorder(ReviewColors.line, JBUI.scale(8)))
        // named by the bottom PR's title: always there, whatever the team calls its branches
        val name = JBLabel(rows.first().listed.title).apply { font = JBFont.label().asBold() }
        val sub = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(mutedLabel("Stack of ${rows.size} PRs"), BorderLayout.WEST)
            add(mutedLabel("$reviewed of ${rows.size} reviewed"), BorderLayout.EAST)
        }
        val head = stack(name, vgap(2), sub, vgap(6), SegmentBar(rows.map { if (it.done) ReviewColors.ok else if (it == next) ReviewColors.accent else ReviewColors.line }))
            .apply { border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.line), JBUI.Borders.empty(8, 10, 7, 10)) }
        railRows = rows.mapIndexed { i, row -> RailRow(row, i, rows.size, row == next, builtOn(rows, i)) { onPick(row.listed) } }
        railRows.forEach(lines::add)
        scroll.border = null
        scroll.isOpaque = false; scroll.viewport.isOpaque = false; lines.isOpaque = false
        scroll.horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        add(head, BorderLayout.NORTH)
        // the counts sit over the rows' top and bottom edges, as in the prototype, so showing one never moves the list
        val edgesOver = JPanel(BorderLayout()).apply { isOpaque = false; add(above, BorderLayout.NORTH); add(below, BorderLayout.SOUTH) }
        add(JPanel().apply { layout = OverlayLayout(this); isOpaque = false; add(edgesOver); add(scroll) }, BorderLayout.CENTER)
        scroll.viewport.addChangeListener { edges() }
        // the first time the rows have a size: where they were, or the next PR in view (a little of the one before it showing)
        scroll.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (placed || scroll.viewport.height == 0) return
                placed = true
                at(scrolledTo ?: railRows.firstOrNull { it.next }?.let { it.y - JBUI.scale(40) } ?: 0)
                edges()
            }
        })
    }

    /** "↑ 10 reviewed above" / "↓ 5 more below": the rows scrolled out of the card, which a hidden scrollbar wouldn't show. */
    private fun edges() {
        val view = scroll.viewport.viewRect
        fun word(rs: List<RailRow>) = if (rs.all { it.row.done }) "reviewed" else "more"
        // a row more than half under a count is out, as the prototype counts it
        val over = above.preferredSize.height
        val up = railRows.filter { it.y + it.height / 2 < view.y + over }
        val down = railRows.filter { it.y + it.height / 2 > view.y + view.height - over }
        above.text = "↑ ${up.size} ${word(up)} above"; above.isVisible = up.isNotEmpty()
        below.text = "↓ ${down.size} ${word(down)} below"; below.isVisible = down.isNotEmpty()
    }

    private fun at(y: Int) { scroll.viewport.viewPosition = Point(0, y.coerceIn(0, maxOf(0, lines.height - scroll.viewport.height))) }

    private fun edge(jump: () -> Unit) = ActionLink("") { jump() }.apply {
        font = JBFont.small().asBold(); isVisible = false; horizontalAlignment = SwingConstants.CENTER; border = JBUI.Borders.empty(3, 0)
        isOpaque = true; background = ReviewColors.surface
    }
}

/** One segment per PR: green reviewed, blue next, grey the rest. */
private class SegmentBar(private val colors: List<java.awt.Color>) : JComponent() {
    init { alignmentX = LEFT_ALIGNMENT }
    override fun getPreferredSize() = Dimension(JBUI.scale(100), JBUI.scale(4))
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, JBUI.scale(4))
    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val gap = JBUI.scale(2)
        val w = (width - gap * (colors.size - 1)).toFloat() / colors.size
        colors.forEachIndexed { i, c ->
            g2.color = c
            g2.fillRoundRect((i * (w + gap)).toInt(), 0, maxOf(1, w.toInt()), height, height, height)
        }
        g2.dispose()
    }
}

/** A PR on the stack's rail: ✓ reviewed, the [next] one highlighted with "Next · k of n", the rest hollow. */
private class RailRow(val row: StackRow, index: Int, size: Int, val next: Boolean, onParent: Int?, onPick: () -> Unit) : PickRow(BorderLayout(JBUI.scale(10), 0)) {
    init {
        // the padding sits in the text, so the rail spans the whole row and joins the rows above and below
        val pad = JBUI.scale(if (next) 8 else 6)
        border = JBUI.Borders.empty(0, 6)
        val pr = row.listed.pr!!
        val title = JBLabel(row.listed.title).apply {
            foreground = if (row.done) ReviewColors.muted else ReviewColors.text
            if (next) font = JBFont.label().asBold()
        }
        val meta = mutableListOf<JComponent>(mutedLabel(ReviewTarget.refOf(pr.number)))
        onParent?.let { meta += mutedLabel("on ${ReviewTarget.refOf(it)}") }
        row.files?.let { meta += mutedLabel(files(it)) }
        val progress = row.progress
        when {
            progress == null -> if (row.listed.requested) meta += Pill("Review requested", ReviewColors.accentSoft, ReviewColors.accent)
            row.done -> meta += mutedLabel(progress.verdict?.done ?: "reviewed")
            else -> meta += Pill(progress.label, ReviewColors.review, ReviewColors.reviewFg)
        }
        row.listed.parent?.takeIf { it.moved }?.let { meta += JBLabel("${ReviewTarget.refOf(it.number)} changed under it").apply { foreground = ReviewColors.warn; font = JBFont.small() } }
        val text = stack(*listOfNotNull(
            if (next) JBLabel("NEXT · ${index + 1} OF $size").apply { font = JBFont.small().asBold(); foreground = ReviewColors.accent } else null,
            title, vgap(2), row(*meta.toTypedArray(), gap = 6),
        ).toTypedArray()).apply { border = JBUI.Borders.empty(pad, 0) }
        add(RailMark(row.done, next, first = index == 0, last = index == size - 1, dotTop = pad + JBUI.scale(if (next) 4 else 3)), BorderLayout.WEST)
        add(text)
        pickOn(onPick, text, title)
    }

    override fun paintComponent(g: Graphics) {
        if (next || hover) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (next) ReviewColors.accentSoft else ReviewColors.chip
            g2.fillRoundRect(0, 0, width, height, JBUI.scale(12), JBUI.scale(12))
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** The rail beside a row: a dashed line joining the stack (stopping at its ends) and the row's dot. */
private class RailMark(private val done: Boolean, private val next: Boolean, private val first: Boolean, private val last: Boolean, private val dotTop: Int) : JComponent() {
    override fun getPreferredSize() = Dimension(JBUI.scale(16), JBUI.scale(16))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val d = JBUI.scale(12)
        val x = (width - d) / 2
        val mid = dotTop + d / 2
        // the line runs the row's full height into the rows above and below, and stops at the stack's ends
        g2.color = ReviewColors.line
        g2.stroke = BasicStroke(JBUI.scale(1).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(JBUI.scale(3).toFloat(), JBUI.scale(3).toFloat()), 0f)
        g2.drawLine(width / 2, if (first) mid else 0, width / 2, if (last) mid else height)
        g2.stroke = BasicStroke(JBUI.scale(1.5f))
        when {
            done -> {
                g2.color = ReviewColors.ok; g2.fillOval(x, dotTop, d, d)
                g2.color = ReviewColors.surface; g2.stroke = BasicStroke(JBUI.scale(1.8f))
                g2.drawPolyline(intArrayOf(x + d * 3 / 12, x + d * 5 / 12, x + d * 9 / 12), intArrayOf(dotTop + d / 2, dotTop + d * 3 / 4, dotTop + d * 4 / 12), 3)
            }
            next -> {
                g2.color = ReviewColors.accentSoft; g2.fillOval(x - JBUI.scale(3), dotTop - JBUI.scale(3), d + JBUI.scale(6), d + JBUI.scale(6))
                g2.color = ReviewColors.accent; g2.fillOval(x, dotTop, d, d)
            }
            else -> {
                g2.color = ReviewColors.surface; g2.fillOval(x, dotTop, d, d)
                g2.color = ReviewColors.muted; g2.drawOval(x, dotTop, d, d)
            }
        }
        g2.dispose()
    }
}

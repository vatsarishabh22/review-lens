package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Every colour the plugin paints, in one place. Each is a JBColor, resolved when painted, never when a component is built:
 * the platform repaints on a theme or editor-scheme switch, so nothing of ours has to listen for one.
 */
object ReviewColors {
    val accent = JBColor(0x3574f0, 0x3574f0)
    val accentSoft = JBColor(0xe6eeff, 0x25324d)
    val review = JBColor(0xfff6e5, 0x3a3220)
    val reviewLine = JBColor(0xf2d9a6, 0x5c4b22)
    val reviewFg = JBColor(0x6b4e00, 0xf0d28c)
    val add = JBColor(0x3b9b46, 0x5fb865)
    val del = JBColor(0xb0303b, 0xf08f8f)
    val mod = JBColor(0x6c9bd2, 0x6c9bd2)
    val ok = JBColor(0x3b9b46, 0x5fb865)
    /** The "✓ Reviewed" pill behind [ok] text. */
    val okSoft = JBColor(0xe3f3e5, 0x253a28)
    val star = JBColor(0xe5a50a, 0xf2c55c)
    /** Someone else's open review thread. */
    val other = JBColor(0x7a5af5, 0xa78bfa)
    val otherSoft = JBColor(0xefeaff, 0x2e2747)
    val warn = JBColor(0xc27c0e, 0xe0a63a)
    val chip = JBColor(0xebecf0, 0x393b40)
    val flash = JBColor(0xf8d79a, 0x6b5320)
    val muted: Color = JBColor.lazy { UIUtil.getContextHelpForeground() }
    val line: Color = JBColor.lazy { JBColor.border() }
    val text: Color = JBColor.lazy { UIUtil.getLabelForeground() }
    /** The panel and bars around the editor: theme surfaces, like the platform's own editor banners. */
    val surface: Color = JBColor.lazy { JBUI.CurrentTheme.ToolWindow.background() }
    /** Inside the editor (the comment cards): the editor scheme, which can differ from the theme (light UI, dark editor). */
    val editorBackground: Color = JBColor.lazy { EditorColorsManager.getInstance().globalScheme.defaultBackground }
    val editorText: Color = JBColor.lazy { EditorColorsManager.getInstance().globalScheme.defaultForeground }

    fun status(status: Char): Color = when (status) { 'A' -> add; 'D' -> del; else -> mod }
}

private val AVATAR_COLORS = listOf(0xc2185b, 0x00897b, 0xef6c00, 0x3949ab, 0x7b61c9, 0x2e7d32, 0x6d4c41).map(::Color)

/** A filled circle with initials, coloured by a stable hash of [key] (the login), like GitHub's fallback avatars. */
class AvatarIcon(name: String, key: String, private val size: Int = 24) : Icon {
    private val initials = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
    private val color = AVATAR_COLORS[Math.floorMod(key.hashCode(), AVATAR_COLORS.size)]

    override fun getIconWidth() = JBUI.scale(size)
    override fun getIconHeight() = JBUI.scale(size)
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val s = iconWidth
        g2.color = color
        g2.fillOval(x, y, s, s)
        g2.color = Color.WHITE
        g2.font = JBFont.label().deriveFont(Font.BOLD, s * 0.4f)
        val fm = g2.fontMetrics
        g2.drawString(initials, x + (s - fm.stringWidth(initials)) / 2, y + (s - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}

/** ⭐ not viewed · ✅ viewed · 🔄 changed since viewed, drawn like the prototype's icons. */
class BadgeIcon(private val badge: Badge) : Icon {
    override fun getIconWidth() = JBUI.scale(16)
    override fun getIconHeight() = JBUI.scale(16)
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.translate(x, y)
        JBUIScale.scale(1f).toDouble().let { g2.scale(it, it) }
        when (badge) {
            Badge.TODO -> {
                g2.color = ReviewColors.star
                g2.fill(polygon(8.0, 1.2, 10.1, 5.6, 14.9, 6.2, 11.4, 9.5, 12.3, 14.3, 8.0, 12.0, 3.7, 14.3, 4.6, 9.5, 1.1, 6.2, 5.9, 5.6))
            }
            Badge.REVIEWED -> {
                g2.color = ReviewColors.ok
                g2.fill(RoundRectangle2D.Double(1.5, 1.5, 13.0, 13.0, 6.0, 6.0))
                g2.color = Color.WHITE
                g2.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.draw(Path2D.Double().apply { moveTo(4.5, 8.2); lineTo(6.8, 10.5); lineTo(11.5, 5.6) })
            }
            Badge.CHANGED_SINCE -> {
                g2.color = ReviewColors.warn
                g2.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawArc(3, 3, 10, 10, 50, 300)
                g2.draw(Path2D.Double().apply { moveTo(13.0, 2.5); lineTo(13.0, 5.5); lineTo(10.0, 5.5) })
            }
        }
        g2.dispose()
    }

    private fun polygon(vararg xy: Double) = Path2D.Double().apply {
        moveTo(xy[0], xy[1]); for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1]); closePath()
    }
}

/** A small rounded label: "Review requested", "Resume · …", branch chips. */
class Pill(text: String, private val fill: Color, fg: Color, mono: Boolean = false) : JBLabel(text) {
    init {
        foreground = fg
        font = if (mono) JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, 11)) else JBFont.small().asBold()
        border = JBUI.Borders.empty(1, 6)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fill
        g2.fillRoundRect(0, 0, width, height, JBUI.scale(if (font.isBold) 18 else 8), JBUI.scale(if (font.isBold) 18 else 8))
        g2.dispose()
        super.paintComponent(g)
    }
}

/** The thin green bar under the review title. */
class ProgressStrip : JComponent() {
    var fraction = 0.0
        set(value) { field = value.coerceIn(0.0, 1.0); repaint() }

    init { alignmentX = LEFT_ALIGNMENT }

    override fun getPreferredSize() = Dimension(JBUI.scale(100), JBUI.scale(6))
    override fun getMaximumSize() = Dimension(Int.MAX_VALUE, JBUI.scale(6))

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val h = height
        g2.color = ReviewColors.chip
        g2.fillRoundRect(0, 0, width, h, h, h)
        g2.color = ReviewColors.ok
        g2.fillRoundRect(0, 0, (width * fraction).toInt(), h, h, h)
        g2.dispose()
    }
}

/**
 * How a comment reads wherever it is drawn (its card in the editor, its row in the Comments tab): someone's thread by
 * its GitHub state, or one of your drafts. The one place a state gets its words and colours.
 */
enum class CommentLook(val label: String, val fill: Color, val fg: Color, val stripe: Color) {
    OPEN("open", ReviewColors.otherSoft, ReviewColors.other, ReviewColors.other),
    RESOLVED("resolved", ReviewColors.okSoft, ReviewColors.ok, ReviewColors.ok),
    OUTDATED("outdated", ReviewColors.chip, ReviewColors.muted, ReviewColors.line),
    POSTED("on GitHub", ReviewColors.accentSoft, ReviewColors.accent, ReviewColors.accent),
    PENDING("Pending", ReviewColors.review, ReviewColors.reviewFg, ReviewColors.reviewLine);

    companion object {
        fun of(state: ThreadState): CommentLook = when (state) { ThreadState.OPEN -> OPEN; ThreadState.RESOLVED -> RESOLVED; ThreadState.OUTDATED -> OUTDATED }
    }
}

fun files(n: Int) = if (n == 1) "1 file" else "$n files"

fun mutedLabel(text: String = "", small: Boolean = true) = JBLabel(text).apply {
    foreground = ReviewColors.muted
    if (small) font = JBFont.small()
}

fun sectionLabel(text: String) = JBLabel(text.uppercase()).apply {
    foreground = ReviewColors.muted
    font = JBFont.small().asBold()
}

/** Plain text that wraps to the panel's width (no HTML, so nothing from a PR body can render as markup). */
fun wrappingText(text: String, font: Font = JBFont.label()) = JBTextArea(text).apply {
    isEditable = false
    isFocusable = false
    lineWrap = true
    wrapStyleWord = true
    isOpaque = false
    border = null
    this.font = font
    alignmentX = Component.LEFT_ALIGNMENT
}

fun vgap(height: Int): JComponent = Box.createVerticalStrut(JBUI.scale(height)) as JComponent

/** Children top to bottom, each as wide as the panel. */
fun stack(vararg parts: JComponent) = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    parts.forEach { it.alignmentX = Component.LEFT_ALIGNMENT; add(it) }
}

/** A wrapping bold title with a muted suffix (the PR number), plain text only. */
fun titleText(title: String, suffix: String?, font: Font) = JTextPane().apply {
    isEditable = false
    isFocusable = false
    isOpaque = false
    border = null
    this.font = font
    alignmentX = Component.LEFT_ALIGNMENT
    val bold = SimpleAttributeSet().also { StyleConstants.setBold(it, true); StyleConstants.setFontSize(it, font.size); StyleConstants.setFontFamily(it, font.family) }
    styledDocument.insertString(0, title, bold)
    if (suffix != null) {
        val muted = SimpleAttributeSet(bold).also { StyleConstants.setBold(it, false); StyleConstants.setForeground(it, ReviewColors.muted) }
        styledDocument.insertString(styledDocument.length, "  $suffix", muted)
    }
}

/** Components left to right with a gap between them (none before the first), left-aligned in a vertical stack. */
fun row(vararg parts: JComponent, gap: Int = 8) = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.X_AXIS)
    isOpaque = false
    alignmentX = Component.LEFT_ALIGNMENT
    parts.forEachIndexed { i, part ->
        if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(gap)))
        part.alignmentY = Component.CENTER_ALIGNMENT
        add(part)
    }
    add(Box.createHorizontalGlue())
}

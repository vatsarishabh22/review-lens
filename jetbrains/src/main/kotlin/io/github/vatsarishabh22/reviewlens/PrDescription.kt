package io.github.vatsarishabh22.reviewlens

import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.ExperimentalApi
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import java.lang.reflect.Method
import java.awt.Dimension
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.BoxLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.min

/** A PR body as HTML: HTML comments (PR templates) dropped, GitHub-flavoured markdown rendered; null when empty. */
// CancellationToken is experimental in 2026.1 and stable from 2026.2; the opt-in is compile-time only
@OptIn(ExperimentalApi::class)
fun markdownHtml(markdown: String): String? {
    val text = markdown.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").replace("\r", "").trim()
    if (text.isEmpty()) return null
    val flavour = GFMFlavourDescriptor()
    val html = HtmlGenerator(text, markdownTree(MarkdownParser(flavour, false, CancellationToken.NonCancellable), text), flavour).generateHtml()
    return html.replace(IMAGE) { it.groupValues[1] } // GitHub's attachments need a sign-in Swing can't give; the alt text stands in
}

/**
 * The tree of [text]. 2026.2 deprecates the String overload for a CharSequence one that 2026.1 (our minimum) lacks, so
 * the overload is picked once at runtime; no deprecated call is compiled in.
 */
private fun markdownTree(parser: MarkdownParser, text: String): ASTNode = TREE_BUILDER.invoke(parser, text) as ASTNode

// ponytail: reflection straddles 2026.1 and 2026.2+; call buildMarkdownTreeFromString(CharSequence) directly once since-build is 262
private val TREE_BUILDER: Method = MarkdownParser::class.java.methods.filter { it.name == "buildMarkdownTreeFromString" && it.parameterCount == 1 }
    .let { all -> all.find { it.parameterTypes[0] == CharSequence::class.java } ?: all.first { it.parameterTypes[0] == String::class.java } }

private val IMAGE = Regex("""<img\b[^>]*?\balt="([^"]*)"[^>]*>|<img\b[^>]*>""")

/** The HTML kit every rendered markdown uses (PR bodies, review comments); colours are baked in, so build it again at a theme switch. */
fun markdownKit() = HTMLEditorKitBuilder().withWordWrapViewFactory().build().apply {
    val chip = ColorUtil.toHex(ReviewColors.chip)
    styleSheet.addRule("body { margin: 0 } p { margin-top: 0; margin-bottom: 6px } ul, ol { margin-top: 0; margin-bottom: 6px; margin-left: 16px }")
    styleSheet.addRule("h1, h2, h3, h4 { margin-top: 4px; margin-bottom: 4px } h1 { font-size: 125% } h2 { font-size: 115% } h3, h4 { font-size: 105% }")
    styleSheet.addRule("code { font-family: monospaced; background-color: #$chip } pre { font-family: monospaced; background-color: #$chip; padding: 6px }")
}

/** The rendered description, folded to a few lines with Show more / Show less, so a long body never pushes Start review away. */
class PrDescription(html: String) : JPanel() {

    private var expanded = false
    // CSS holds colours as text, so the styles are rebuilt in updateUI: Swing's own call on every component at a theme switch
    private val pane = object : JEditorPane() {
        override fun updateUI() {
            super.updateUI()
            (getClientProperty(HTML) as? String)?.let { editorKit = markdownKit(); text = it } // null while Swing constructs the pane
        }
    }.apply {
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = JBFont.label()
        isEditable = false; isOpaque = false; isFocusable = false; border = null
        addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
        putClientProperty(HTML, html)
        editorKit = markdownKit(); text = html
    }
    private val toggle: ActionLink = ActionLink("Show more") { flip() }.apply { font = JBFont.small(); isVisible = false }
    private val fold get() = pane.getFontMetrics(pane.font).height * 6

    /** Shows the top [fold] pixels of the pane, or all of it when expanded; the pane keeps its full height inside. */
    private val clip = object : JPanel(null) {
        private var laidOut = -1
        fun fullHeight(width: Int): Int { pane.setSize(width, Short.MAX_VALUE.toInt()); return pane.preferredSize.height }
        override fun getPreferredSize(): Dimension {
            val full = fullHeight(width.takeIf { it > 0 } ?: JBUI.scale(300))
            return Dimension(JBUI.scale(100), if (expanded) full else min(full, fold))
        }
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun doLayout() {
            val full = fullHeight(width)
            pane.setBounds(0, 0, width, full)
            // the height is only known at the real width: lay out again once it is, and offer the toggle only when something is hidden
            if (full != laidOut) { laidOut = full; SwingUtilities.invokeLater { toggle.isVisible = full > fold; revalidate(); this@PrDescription.revalidate() } }
        }
        // folded: the last visible line fades out instead of being cut through
        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            if (expanded || pane.height <= height) return
            val fade = pane.getFontMetrics(pane.font).height * 2
            val bg = ReviewColors.surface
            (g as Graphics2D).paint = GradientPaint(0f, (height - fade).toFloat(), ColorUtil.withAlpha(bg, 0.0), 0f, height.toFloat(), bg)
            g.fillRect(0, height - fade, width, fade)
        }
    }.apply { isOpaque = false; add(pane); alignmentX = LEFT_ALIGNMENT }

    private fun flip() {
        expanded = !expanded
        toggle.text = if (expanded) "Show less" else "Show more"
        clip.revalidate()
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(clip)
        add(vgap(4))
        add(row(toggle))
    }

    private companion object {
        const val HTML = "review-lens.description"
    }
}

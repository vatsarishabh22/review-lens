package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Cursor
import java.util.function.Function
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Above every editor during a review: the review bar (what, scope ▾, read-only, Prev, Next unviewed, Exit) and the
 * file bar (path, File / Diff, Viewed). Rebuilt by EditorNotifications whenever the review changes or an edit is refused.
 */
class ReviewBanner : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val service = ReviewService.of(project)
        val meta = service.meta ?: return null
        val path = service.relativePath(file) ?: return null // IDE pages, scratches, files outside the repo
        val change = service.changeFor(file)
        return Function { stack(reviewBar(project, meta, change), fileBar(project, path, change)) }
    }

    private fun reviewBar(project: Project, meta: ReviewMeta, change: FileChange?): JComponent {
        val service = ReviewService.of(project)
        val what = JBLabel("Reviewing ${meta.target.prNumber?.let { "#$it" } ?: meta.target.label}").apply {
            font = JBFont.label().asBold(); foreground = ReviewColors.reviewFg
        }
        val scope = flat(meta.scopeLabel, AllIcons.General.ArrowDown, iconRight = true) { scopeMenu(project, meta, it) }
        val lock = JBLabel(if (service.flashing) "Read-only while reviewing. Exit review to edit." else "Read-only", AllIcons.Diff.Lock, SwingConstants.LEFT)
            .apply { font = JBFont.small(); foreground = ReviewColors.reviewFg }
        val prev = flat("Prev", AllIcons.Actions.Back) { service.previous(change?.path)?.let { openChange(project, it) } }
        val next = flat("Next unviewed", AllIcons.Actions.Forward, iconRight = true) {
            service.nextUnreviewed(change?.path)?.let { openChange(project, it) }
        }.apply { toolTipText = "Next unviewed file (⌥N)" }
        val exit = flat("Exit", AllIcons.Actions.Exit) { exitReview(project) }.apply { toolTipText = "Back to ${service.active?.originalRef}" }
        return JPanel(BorderLayout()).apply {
            background = if (service.flashing) FLASH else ReviewColors.review
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.reviewLine), JBUI.Borders.empty(3, 12, 3, 8))
            add(row(what, scope, gap = 10), BorderLayout.WEST)
            add(lock.apply { border = JBUI.Borders.emptyLeft(10) }) // centre: a long flash message truncates, never overlaps Prev
            add(row(prev, next, exit, gap = 4), BorderLayout.EAST)
        }
    }

    private fun fileBar(project: Project, path: String, change: FileChange?): JComponent {
        val service = ReviewService.of(project)
        val crumb = SimpleColoredComponent().apply {
            isOpaque = false
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { append("$it/", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            append(path.substringAfterLast('/'), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (change == null) append("   not changed in this scope", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        val bar = JPanel(BorderLayout()).apply {
            background = EditorColorsManager.getInstance().globalScheme.defaultBackground
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.line), JBUI.Borders.empty(2, 12, 2, 8))
            add(crumb)
        }
        if (change == null) return bar
        // ponytail: Diff opens today's diff window and the toggle returns to File; step 6 makes Diff an editor tab.
        val view = panel {
            row {
                segmentedButton(listOf(false, true)) { text = if (it) "Diff" else "File" }.apply {
                    selectedItem = false
                    whenItemSelected { diff -> if (diff) { showDiff(project, change); selectedItem = false } }
                }
            }
        }.apply { isOpaque = false }
        val viewed = service.badge(change) == Badge.REVIEWED
        val toggle = JButton("Viewed", if (viewed) BadgeIcon(Badge.REVIEWED) else BOX).apply {
            isOpaque = false
            toolTipText = if (viewed) "Viewed — click to unmark (⌥R)" else "Mark viewed (⌥R)"
            if (viewed) foreground = ReviewColors.ok
            addActionListener { service.toggleReviewed(change) }
        }
        bar.add(row(view, toggle, gap = 8), BorderLayout.EAST)
        return bar
    }

    /** All commits, then each commit of the PR: picking one re-scopes the review in place (no fetch). */
    private fun scopeMenu(project: Project, meta: ReviewMeta, anchor: JComponent) {
        val git = ReviewService.of(project).git ?: return
        val head = ReviewService.of(project).active?.head ?: return
        val session = ReviewSession(git)
        background(project, "Listing commits", { session.local(meta.target, head) }) { loaded ->
            val all = Commit("", "All commits (${loaded.commits.size})")
            JBPopupFactory.getInstance().createPopupChooserBuilder(listOf(all) + loaded.commits)
                .setRenderer(SimpleListCellRenderer.create("") { c ->
                    val tick = if ((c === all && meta.scopeLabel.startsWith("All commits")) || meta.scopeLabel == "Commit ${c.sha.take(7)}") "✓  " else "    "
                    tick + if (c === all) c.subject else c.label
                })
                .setItemChosenCallback { picked ->
                    val picks = if (picked === all) emptyList() else listOf(picked)
                    beginReview(project, session, loaded, resolveScope(loaded.mergeBase, loaded.commits, picks), scopeLabel(loaded.commits, picks))
                }
                .createPopup()
                .showUnderneathOf(anchor)
        }
    }

    /** A borderless button in the review bar's colour. */
    private fun flat(text: String, icon: Icon, iconRight: Boolean = false, onClick: (JComponent) -> Unit): JButton = JButton(text, icon).apply {
        isContentAreaFilled = false; isBorderPainted = false; isOpaque = false; isFocusable = false
        foreground = ReviewColors.reviewFg
        border = JBUI.Borders.empty(2, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        if (iconRight) horizontalTextPosition = SwingConstants.LEFT
        addActionListener { onClick(this) }
    }

    private companion object {
        val FLASH = JBColor(Color(0xf8d79a), Color(0x6b5320))

        /** The empty box of an unticked "Viewed". */
        val BOX: Icon = object : Icon {
            override fun getIconWidth() = JBUI.scale(16)
            override fun getIconHeight() = JBUI.scale(16)
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = ReviewColors.muted
                g2.drawRoundRect(x + JBUI.scale(2), y + JBUI.scale(2), JBUI.scale(12), JBUI.scale(12), JBUI.scale(5), JBUI.scale(5))
                g2.dispose()
            }
        }
    }
}

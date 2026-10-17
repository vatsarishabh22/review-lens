package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
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
import javax.swing.JList
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
        return Function { editor -> stack(reviewBar(project, meta, change), fileBar(project, editor, file, path, change)) }
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
                ?: notify(project, "Every file is viewed", if (meta.target.prNumber != null) "Finish the review when you're ready." else "Exit the review when you're done.")
        }.apply { toolTipText = "Next unviewed file (⌥N)" }
        val pending = ReviewDrafts.of(project).pending().size
        val finish = JButton("Finish review", if (pending > 0) CountIcon(pending) else null).apply {
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            isOpaque = false; isFocusable = false
            horizontalTextPosition = SwingConstants.LEFT
            addActionListener { showFinishReview(project, this) }
        }.takeIf { meta.target.prNumber != null } // a branch has nowhere to post a review
        val exit = flat("Exit", AllIcons.Actions.Exit) { exitReview(project) }.apply { toolTipText = "Back to ${service.active?.originalRef}" }
        return JPanel(BorderLayout()).apply {
            background = if (service.flashing) ReviewColors.flash else ReviewColors.review
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.reviewLine), JBUI.Borders.empty(3, 12, 3, 8))
            add(row(what, scope, gap = 6), BorderLayout.WEST)
            add(lock.apply { border = JBUI.Borders.emptyLeft(6) }) // centre: a long flash message truncates, never overlaps Prev
            add(row(*listOfNotNull(prev, next, finish, exit).toTypedArray(), gap = 0), BorderLayout.EAST)
        }
    }

    private fun fileBar(project: Project, editor: FileEditor, file: VirtualFile, path: String, change: FileChange?): JComponent {
        val diffShown = editor is ReviewDiffEditor
        val service = ReviewService.of(project)
        val crumb = SimpleColoredComponent().apply {
            isOpaque = false
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { append("$it/", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
            append(path.substringAfterLast('/'), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            if (change == null) append("   not changed in this scope", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
        val bar = JPanel(BorderLayout()).apply {
            background = ReviewColors.surface
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(ReviewColors.line), JBUI.Borders.empty(2, 12, 2, 8))
            add(crumb)
        }
        if (change == null) return bar
        val view = panel {
            row {
                segmentedButton(listOf(false, true)) { text = if (it) "Diff" else "File" }.apply {
                    selectedItem = diffShown
                    whenItemSelected { diff -> if (diff != diffShown) showView(project, file, diff) }
                }
            }
        }.apply { isOpaque = false; toolTipText = "⌥D" }
        val toggle = JButton("Viewed").apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { service.toggleReviewed(change) }
        }
        fun show() {
            val viewed = service.badge(change) == Badge.REVIEWED
            toggle.icon = if (viewed) BadgeIcon(Badge.REVIEWED) else BOX
            toggle.toolTipText = if (viewed) "Viewed — click to unmark (⌥R)" else "Mark viewed (⌥R)"
            toggle.foreground = if (viewed) ReviewColors.ok else ReviewColors.text
        }
        show()
        // The platform rebuilds this bar ~220 ms after a change (measured); the button repaints at once instead,
        // for a click and for ⌥R alike. One listener per tab: a rebuilt bar replaces it, closing the tab ends it.
        editor.getUserData(LIVE_VIEWED)?.let(Disposer::dispose)
        val live = Disposer.newDisposable().also { Disposer.register(editor, it); editor.putUserData(LIVE_VIEWED, it) }
        service.onChanged(live) { show() }
        bar.add(row(view, toggle, gap = 8), BorderLayout.EAST)
        return bar
    }

    /** One line of the scope menu and what picking it does. */
    private class ScopeItem(val label: String, val ticked: Boolean, val pick: () -> Unit)

    /**
     * All commits, "Since your last review", then each commit; picking one re-scopes the review in place. The last line
     * fetches the PR again, so commits pushed after the review started show up (and move the checkout to the new head).
     */
    private fun scopeMenu(project: Project, meta: ReviewMeta, anchor: JComponent, fetch: Boolean = false) {
        val service = ReviewService.of(project)
        val git = service.git ?: return
        val head = service.active?.head ?: return
        val session = ReviewSession(git)
        background(project, if (fetch) "Checking GitHub for new commits" else "Listing commits", {
            if (fetch) session.load(meta.target) else session.local(meta.target, head)
        }) { loaded ->
            fun scope(range: ScopeRange, label: String) = beginReview(project, session, loaded, range, label)
            val all = scopeLabel(loaded.commits, emptyList())
            val items = buildList {
                add(ScopeItem(all, meta.scopeLabel == all) { scope(resolveScope(loaded.mergeBase, loaded.commits, emptyList()), all) })
                sinceLastReview(loaded.head, loaded.commits, meta.seenHead)?.let { (range, label) ->
                    add(ScopeItem(label, meta.scopeLabel == label) { scope(range, label) })
                }
                loaded.commits.forEach { c ->
                    val label = scopeLabel(loaded.commits, listOf(c))
                    add(ScopeItem(c.label, meta.scopeLabel == label) { scope(resolveScope(loaded.mergeBase, loaded.commits, listOf(c)), label) })
                }
                add(ScopeItem("Check GitHub for new commits…", false) { scopeMenu(project, meta, anchor, fetch = true) })
            }
            if (fetch && loaded.head == head) notify(project, "No new commits", "${meta.target.label} has nothing newer than what you're reviewing.")
            JBPopupFactory.getInstance().createPopupChooserBuilder(items)
                .setRenderer(object : SimpleListCellRenderer<ScopeItem>() {
                    // the factory shortcuts (create) are scheduled for removal in 2026.2; customize is the class's own hook
                    override fun customize(list: JList<out ScopeItem>, item: ScopeItem, index: Int, selected: Boolean, focused: Boolean) {
                        text = (if (item.ticked) "✓  " else "    ") + item.label
                    }
                })
                .setItemChosenCallback { it.pick() }
                .createPopup()
                .showUnderneathOf(anchor)
        }
    }

    /** A borderless button in the review bar's colour. */
    private fun flat(text: String, icon: Icon, iconRight: Boolean = false, onClick: (JComponent) -> Unit): JButton = JButton(text, icon).apply {
        isContentAreaFilled = false; isBorderPainted = false; isOpaque = false; isFocusable = false
        foreground = ReviewColors.reviewFg
        border = JBUI.Borders.empty(2, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        if (iconRight) horizontalTextPosition = SwingConstants.LEFT
        addActionListener { onClick(this) }
    }

    private companion object {
        val LIVE_VIEWED = Key.create<com.intellij.openapi.Disposable>("review-lens.liveViewed")

        /** The pending-comment count on Finish review: a translucent white pill, as in the prototype. */
        class CountIcon(private val count: Int) : Icon {
            private val font get() = JBFont.small().asBold()
            override fun getIconWidth() = JBUI.scale(10 + 7 * "$count".length)
            override fun getIconHeight() = JBUI.scale(16)
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Color(255, 255, 255, 64)
                g2.fillRoundRect(x, y + JBUI.scale(1), iconWidth, iconHeight - JBUI.scale(2), JBUI.scale(14), JBUI.scale(14))
                g2.color = Color.WHITE
                g2.font = font
                val fm = g2.fontMetrics
                g2.drawString("$count", x + (iconWidth - fm.stringWidth("$count")) / 2, y + (iconHeight + fm.ascent - fm.descent) / 2)
                g2.dispose()
            }
        }

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

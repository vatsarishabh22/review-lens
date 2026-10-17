package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent

/** Finish review: summary, verdict, then everything goes to GitHub as one review. */
fun showFinishReview(project: Project, anchor: JComponent) {
    val service = ReviewService.of(project)
    val drafts = ReviewDrafts.of(project)
    val pending = drafts.pending().size
    val summary = JBTextArea(drafts.summary, 4, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = JBFont.label(); emptyText.text = "Leave a summary (optional)"
    }
    val group = ButtonGroup()
    val verdicts = Verdict.entries.associateWith { v ->
        JRadioButton(v.label, v == Verdict.COMMENT).apply {
            isOpaque = false; group.add(this)
            isEnabled = v == Verdict.COMMENT || !service.ownPullRequest
            if (!isEnabled) toolTipText = submitBlocker(v, "", 0, ownPullRequest = true) // why it is greyed, on hover
        }
    }
    lateinit var popup: JBPopup
    val verdict = { verdicts.entries.first { it.value.isSelected }.key }
    val cancel = JButton("Cancel").apply { addActionListener { popup.cancel() }; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    val submit = JButton("Submit to GitHub").apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { popup.cancel(); finishReview(project, summary.text.trim(), verdict()) }
    }
    val update = {
        val blocker = submitBlocker(verdict(), summary.text, pending, service.ownPullRequest)
        submit.isEnabled = blocker == null
        submit.toolTipText = blocker // a greyed-out Submit says why on hover
    }
    summary.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) { drafts.summary = summary.text; update() }
    })
    verdicts.values.forEach { it.addActionListener { update() } }
    update()
    // drafts written on another scope's version of a file go out only from that scope: say so, never skip them silently
    val waiting = service.meta?.drafts.orEmpty().count { !it.posted } - pending
    val comments = (if (pending > 0) "$pending pending comment${if (pending > 1) "s" else ""} will be posted with it." else "No inline comments yet.") +
        (if (waiting > 0) " $waiting more ${if (waiting == 1) "waits for the scope it was" else "wait for the scopes they were"} written in." else "")
    val content = stack(
        JBLabel("Finish your review").apply { font = JBFont.label().asBold() }, vgap(4),
        wrappingText("$comments ${service.reviewedCount()} of ${service.changes.size} files viewed.", JBFont.small()).apply {
            foreground = ReviewColors.muted; setSize(JBUI.scale(356), Short.MAX_VALUE.toInt())
        }, vgap(10),
        JBScrollPane(summary), vgap(8),
        // the hint is its own muted label under the radio, so its colour follows the theme like every other label
        *verdicts.flatMap { (v, radio) -> listOf(radio, mutedLabel(v.hint).apply { border = EmptyBorder(0, textIndent(radio), 0, 0) }, vgap(4)) }.toTypedArray(), vgap(4),
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false; add(cancel); add(submit) },
    ).apply { border = JBUI.Borders.empty(12); preferredSize = Dimension(JBUI.scale(380), preferredSize.height) }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, summary)
        .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true)
        .setCancelOnWindowDeactivation(false) // switching to another app and back keeps it open
        .createPopup()
    popup.showUnderneathOf(anchor)
}

/** Where a radio button's label starts, so a hint under it lines up with the label in every theme. */
private fun textIndent(radio: JRadioButton): Int =
    radio.insets.left + ((radio.icon ?: UIManager.getIcon("RadioButton.icon"))?.iconWidth ?: 0) + radio.iconTextGap

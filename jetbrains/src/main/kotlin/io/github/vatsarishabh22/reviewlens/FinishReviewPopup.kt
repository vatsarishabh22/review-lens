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
import javax.swing.JSeparator
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent

/**
 * Finish review: summary and verdict go to GitHub as one review, or "Mark as reviewed" posts nothing; either way the
 * review then ends. Shown under [anchor], or centred in the window when there is none (the "All files viewed" balloon).
 */
fun showFinishReview(project: Project, anchor: JComponent?) {
    val service = ReviewService.of(project)
    val drafts = ReviewDrafts.of(project)
    val pending = drafts.pending().size
    val summary = JBTextArea(drafts.summary, 4, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = JBFont.label(); emptyText.text = "Leave a summary (optional)"
    }
    val group = ButtonGroup()
    // null is "Mark as reviewed": no GitHub event, so own-PR and empty-review rules don't apply to it
    val choices = (Verdict.entries + null).associateWith { v ->
        JRadioButton(v?.label ?: "Mark as reviewed", v == Verdict.COMMENT).apply {
            isOpaque = false; group.add(this)
            isEnabled = v == null || v == Verdict.COMMENT || !service.ownPullRequest
            if (!isEnabled) toolTipText = submitBlocker(v!!, "", 0, ownPullRequest = true) // why it is greyed, on hover
        }
    }
    var popup: JBPopup? = null
    var content: JComponent? = null
    val verdict = { choices.entries.first { it.value.isSelected }.key }
    val cancel = JButton("Cancel").apply { addActionListener { popup?.cancel() }; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    val submit = JButton("Submit to GitHub").apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { popup?.cancel(); finishReview(project, summary.text.trim(), verdict()) }
    }
    // drafts written on another scope's version of a file go out only from that scope: say so, never skip them silently
    val waiting = service.meta?.drafts.orEmpty().count { !it.posted } - pending
    val posting = if (pending > 0) "$pending pending comment${if (pending > 1) "s" else ""} will be posted with it." else "No inline comments yet."
    val later = if (waiting > 0) " $waiting more ${if (waiting == 1) "waits for the scope it was" else "wait for the scopes they were"} written in." else ""
    val viewed = "${service.reviewedCount()} of ${service.changes.size} files viewed."
    val fineprint = wrappingText("", JBFont.small()).apply { foreground = ReviewColors.muted }
    val keep = wrappingText("Your $pending draft comment${if (pending > 1) "s" else ""} won't be posted; ${if (pending > 1) "they're" else "it's"} kept for next time.", JBFont.small())
        .apply { foreground = ReviewColors.warn }
    val update = {
        val v = verdict()
        val blocker = v?.let { submitBlocker(it, summary.text, pending, service.ownPullRequest) }
        submit.isEnabled = blocker == null
        submit.toolTipText = blocker // a greyed-out Submit says why on hover
        submit.text = if (v == null) "Finish review" else "Submit to GitHub"
        summary.isEnabled = v != null // nothing is posted, so a summary has nowhere to go
        keep.isVisible = v == null && pending > 0
        // the warning says what happens to the drafts instead of "will be posted"
        fineprint.text = (if (keep.isVisible) "" else "$posting$later ") + viewed
        listOf(fineprint, keep).forEach { it.setSize(JBUI.scale(356), Short.MAX_VALUE.toInt()) }
        content?.let(::fitWidth)
        popup?.pack(false, true) // the drafts warning comes and goes with Mark as reviewed
    }
    summary.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) { drafts.summary = summary.text; update() }
    })
    choices.values.forEach { it.addActionListener { update() } }
    update()
    // the hint is its own muted label under the radio, so its colour follows the theme like every other label
    fun option(v: Verdict?, radio: JRadioButton) = listOf(radio, mutedLabel(v?.hint ?: "Nothing is posted to GitHub").apply { border = EmptyBorder(0, textIndent(radio), 0, 0) }, vgap(4))
    content = stack(
        JBLabel("Finish your review").apply { font = JBFont.label().asBold() }, vgap(4),
        fineprint, vgap(10),
        JBScrollPane(summary), vgap(8),
        *choices.filterKeys { it != null }.flatMap { (v, radio) -> option(v, radio) }.toTypedArray(),
        JSeparator(), vgap(6),
        *option(null, choices.getValue(null)).toTypedArray(),
        keep, vgap(8),
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false; add(cancel); add(submit) },
    ).apply { border = JBUI.Borders.empty(12); fitWidth(this) }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content!!, summary)
        .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true)
        .setCancelOnWindowDeactivation(false) // switching to another app and back keeps it open
        .createPopup()
    if (anchor != null && anchor.isShowing) popup!!.showUnderneathOf(anchor) else popup!!.showCenteredInCurrentWindow(project)
}

/** The popup's fixed width, its height whatever the visible parts need. */
private fun fitWidth(content: JComponent) {
    content.preferredSize = null
    content.preferredSize = Dimension(JBUI.scale(380), content.preferredSize.height)
}

/** Where a radio button's label starts, so a hint under it lines up with the label in every theme. */
private fun textIndent(radio: JRadioButton): Int =
    radio.insets.left + ((radio.icon ?: UIManager.getIcon("RadioButton.icon"))?.iconWidth ?: 0) + radio.iconTextGap

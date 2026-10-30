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
 * The threads are read again first, so a thread resolved or deleted since the review loaded is said before anything posts.
 */
fun showFinishReview(project: Project, anchor: JComponent?) {
    if (ReviewService.of(project).meta?.target?.reviewable != true) return openFinishReview(project, anchor)
    background(project, "Checking ${ReviewService.of(project).hostName} for new comments", { HostThreads.of(project).reload() }) { openFinishReview(project, anchor) }
}

/** What the Finish popup says: its fine print, the drafts warning (only when nothing is posted), and the button. */
data class FinishWords(val fineprint: String, val keep: String?, val submit: String)

/**
 * The words for finishing with [verdict] (null: Mark as reviewed): what goes out ([outbox], plus the [started] comments
 * of a review begun on the host's website), or why it is [held]; the [waiting] comments of other scopes; files viewed.
 */
fun finishWords(verdict: Verdict?, outbox: Outbox, started: Int, held: String?, waiting: Int, viewed: Int, total: Int, host: String): FinishWords {
    val fromHost = "${draftsText(started, 0)} you started on $host"
    val posting = when {
        held != null -> held
        outbox.size > 0 -> "${outbox.text} will be posted with it, as one review." + if (started > 0) " So ${if (started == 1) "is" else "are"} the $fromHost." else ""
        started > 0 -> "The $fromHost will be posted with it."
        else -> "No inline comments yet."
    }
    val later = if (waiting > 0) " $waiting more ${if (waiting == 1) "waits for the scope it was" else "wait for the scopes they were"} written in." else ""
    // with nothing posted, the warning says what happens to the drafts instead of "will be posted"
    val keep = if (verdict == null && outbox.size > 0) "Your ${outbox.text} won't be posted; ${if (outbox.size > 1) "they're" else "it's"} kept for next time." else null
    return FinishWords((if (keep != null) "" else "$posting$later ") + "$viewed of $total files viewed.", keep, if (verdict == null) "Finish review" else "Submit to $host")
}

/** What changed on [host] under your pending replies: a thread resolved meanwhile (the reply still posts), or one deleted (it waits). */
fun replyNotes(replies: List<Draft>, threads: List<ReviewThread>?, host: String): List<String> {
    if (threads == null) return emptyList()
    val byId = threads.associateBy { it.id }
    val replied = replies.filter { !it.posted && it.replyTo != null }
    val resolved = replied.mapNotNull { byId[it.replyTo] }.filter { it.resolvedByOther }.distinct().map {
        "${it.resolvedBy ?: "Someone"} resolved a thread you replied to since the review loaded. Your reply still posts; the thread stays resolved."
    }
    val gone = replied.count { it.replyTo !in byId }
    return resolved + listOfNotNull(gone.takeIf { it > 0 }?.let {
        if (it == 1) "1 reply is on a thread that's gone from $host. It won't post and stays as a draft."
        else "$it replies are on threads that are gone from $host. They won't post and stay as drafts."
    })
}

private fun openFinishReview(project: Project, anchor: JComponent?) {
    val service = ReviewService.of(project)
    val drafts = ReviewDrafts.of(project)
    val outbox = drafts.outbox()
    val summary = JBTextArea(drafts.summary, 4, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = JBFont.label(); emptyText.text = "Leave a summary (optional)"
    }
    val group = ButtonGroup()
    // null is "Mark as reviewed": nothing goes to the host, so none of its rules apply; the slot was read just before this opened
    val slot = HostThreads.of(project).slot
    val scopeEnd = service.meta?.range?.to.orEmpty()
    val choices = (Verdict.entries + null).associateWith { v ->
        JRadioButton(v?.label ?: "Mark as reviewed", v == Verdict.COMMENT).apply {
            isOpaque = false; group.add(this)
            toolTipText = v?.let { slot?.denied(it) } // why it is greyed, on hover
            isEnabled = toolTipText == null
        }
    }
    var popup: JBPopup? = null
    var content: JComponent? = null
    val verdict = { choices.entries.first { it.value.isSelected }.key }
    val cancel = JButton("Cancel").apply { addActionListener { popup?.cancel() }; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    val submit = JButton("Submit to ${service.hostName}").apply {
        putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { popup?.cancel(); finishReview(project, summary.text.trim(), verdict()) }
    }
    // drafts written on another scope's version of a file go out only from that scope: say so, never skip them silently
    val waiting = service.meta?.drafts.orEmpty().count { !it.posted && it.replyTo == null } - outbox.comments.size
    // a review started on the host's website is adopted, and a held one is said in place of "will be posted"
    val words = { v: Verdict? -> finishWords(v, outbox, slot?.started ?: 0, slot?.held(outbox, scopeEnd), waiting, service.reviewedCount(), service.changes.size, service.hostName) }
    val fineprint = wrappingText("", JBFont.small()).apply { foreground = ReviewColors.muted }
    val keep = wrappingText("", JBFont.small()).apply { foreground = ReviewColors.warn }
    val notes = replyNotes(service.meta?.drafts.orEmpty(), HostThreads.of(project).threads, service.hostName).map { note ->
        wrappingText(note, JBFont.small()).apply { foreground = ReviewColors.warn; setSize(JBUI.scale(356), Short.MAX_VALUE.toInt()) }
    }
    val update = {
        val v = verdict()
        val blocker = v?.let { slot?.refusal(it, summary.text, outbox, scopeEnd) }
        submit.isEnabled = blocker == null
        submit.toolTipText = blocker // a greyed-out Submit says why on hover
        val said = words(v)
        submit.text = said.submit
        summary.isEnabled = v != null // nothing is posted, so a summary has nowhere to go
        keep.text = said.keep.orEmpty()
        keep.isVisible = said.keep != null
        fineprint.text = said.fineprint
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
    fun option(v: Verdict?, radio: JRadioButton) = listOf(radio, mutedLabel(v?.hint ?: "Nothing is posted to ${service.hostName}").apply { border = EmptyBorder(0, textIndent(radio), 0, 0) }, vgap(4))
    content = stack(
        JBLabel("Finish your review").apply { font = JBFont.label().asBold() }, vgap(4),
        fineprint, vgap(6),
        *notes.flatMap { listOf(it, vgap(6)) }.toTypedArray(), vgap(4),
        JBScrollPane(summary), vgap(8),
        *choices.filterKeys { it != null }.flatMap { (v, radio) -> option(v, radio) }.toTypedArray(),
        JSeparator(), vgap(6),
        *option(null, choices.getValue(null)).toTypedArray(),
        keep, vgap(8),
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false; add(cancel); add(submit) },
    ).apply { border = JBUI.Borders.empty(12); fitWidth(this) }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, summary)
        .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true)
        .setCancelOnWindowDeactivation(false) // switching to another app and back keeps it open
        .createPopup()
    if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
}

/** The popup's fixed width, its height whatever the visible parts need. */
private fun fitWidth(content: JComponent) {
    content.preferredSize = null
    content.preferredSize = Dimension(JBUI.scale(380), content.preferredSize.height)
}

/** Where a radio button's label starts, so a hint under it lines up with the label in every theme. */
private fun textIndent(radio: JRadioButton): Int =
    radio.insets.left + ((radio.icon ?: UIManager.getIcon("RadioButton.icon"))?.iconWidth ?: 0) + radio.iconTextGap

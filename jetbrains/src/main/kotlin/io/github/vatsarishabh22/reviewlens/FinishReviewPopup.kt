package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
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
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent

/**
 * Finish review: summary and verdict go to GitHub as one review, or "Mark as reviewed" posts nothing; either way the
 * review then ends. Shown under [anchor], or centred in the window when there is none (the "All files viewed" balloon).
 * It opens at once and reads the threads again inside, so a thread resolved or deleted since the review loaded is said
 * before anything posts; Submit waits for that read, Mark as reviewed doesn't (it posts nothing).
 */
fun showFinishReview(project: Project, anchor: JComponent?) {
    if (!refusedBusy(project, "Finish review")) openFinishReview(project, anchor)
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

/** Why choice [choice] (null: Mark as reviewed) can't be picked now, or null: the one lock its radio and Submit both read. */
fun choiceLock(choice: Verdict?, markLocked: String?, slot: ReviewSlot?): String? = if (choice == null) markLocked else slot?.denied(choice)

/**
 * Why Submit can't go with [verdict] now (said on hover), or null: first the choice's own lock ([choiceLock]), which can
 * close while it stays picked (a file unticked with the popup open); then the host's comments being read again; then
 * the host's refusal of that review.
 */
fun submitBlocker(verdict: Verdict?, markLocked: String?, slot: ReviewSlot?, checked: Boolean?, summary: String, outbox: Outbox, scopeEnd: String, host: String): String? =
    choiceLock(verdict, markLocked, slot) ?: when {
        verdict == null -> null
        checked == null -> "Waiting for $host's latest comments"
        else -> slot?.refusal(verdict, summary, outbox, scopeEnd)
    }

private fun openFinishReview(project: Project, anchor: JComponent?) {
    val service = ReviewService.of(project)
    val host = service.hostName
    val threads = HostThreads.of(project)
    val drafts = ReviewDrafts.of(project)
    val summary = JBTextArea(drafts.summary, 4, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = JBFont.label(); emptyText.text = "Leave a summary (optional)"
    }
    // the threads read again, for what changed since the review loaded: null while reading, false when it failed
    var checked: Boolean? = if (service.meta?.target?.reviewable == true) null else true
    val group = ButtonGroup()
    // null is "Mark as reviewed": nothing goes to the host, so none of its rules apply
    val choices = (Verdict.entries + null).associateWith { v -> JRadioButton(v?.label ?: "Mark as reviewed", v == Verdict.COMMENT).apply { isOpaque = false; group.add(this) } }
    var popup: JBPopup? = null
    var content: JComponent? = null
    val verdict = { choices.entries.first { it.value.isSelected }.key }
    val cancel = JButton("Cancel").apply { addActionListener { popup?.cancel() }; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    val submit = JButton("Submit to $host").apply { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) }
    val fineprint = wrappingText("", JBFont.small()).apply { foreground = ReviewColors.muted }
    val check = JBLabel("Checking $host for new comments…", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT).apply { font = JBFont.small(); foreground = ReviewColors.muted }
    val notes = stack()
    val keep = wrappingText("", JBFont.small()).apply { foreground = ReviewColors.warn }
    val refused = wrappingText("", JBFont.small()).apply { foreground = ReviewColors.warn; isVisible = false }
    val markHint = mutedLabel("")
    // every redraw reads the review as it is now: the threads read again, a tick or a draft since the popup opened
    val update = {
        val outbox = drafts.outbox()
        val scopeEnd = service.meta?.range?.to.orEmpty()
        val viewed = service.reviewedCount()
        // Mark as reviewed records the files viewed, so before every one is it would record nothing
        val markLocked = if (allViewed(viewed, service.changes.size)) null else "View every file first: $viewed of ${service.changes.size} viewed"
        // drafts written on another scope's version of a file go out only from that scope: say so, never skip them silently
        val waiting = service.meta?.drafts.orEmpty().count { !it.posted && it.replyTo == null } - outbox.comments.size
        markHint.text = markLocked ?: "Nothing is posted to $host"
        val v = verdict()
        val slot = threads.slot
        val idle = service.ending == null
        val posting = service.ending == ReviewService.Ending.POSTING
        // a review started on the host's website is adopted, and a held one is said in place of "will be posted"
        val said = finishWords(v, outbox, slot?.started ?: 0, slot?.held(outbox, scopeEnd), waiting, viewed, service.changes.size, host)
        choices.forEach { (c, radio) ->
            radio.toolTipText = choiceLock(c, markLocked, slot) // why it is greyed, on hover
            radio.isEnabled = radio.toolTipText == null && idle
        }
        val blocker = submitBlocker(v, markLocked, slot, checked, summary.text, outbox, scopeEnd, host)
        submit.isEnabled = blocker == null && idle
        submit.toolTipText = blocker // a greyed-out Submit says why on hover
        submit.text = if (posting) ReviewService.Ending.POSTING.busy else said.submit
        submit.icon = if (posting) AnimatedIcon.Default.INSTANCE else null
        cancel.isEnabled = !posting
        summary.isEnabled = v != null && idle // nothing is posted, so a summary has nowhere to go
        keep.text = said.keep.orEmpty()
        keep.isVisible = said.keep != null
        fineprint.text = said.fineprint
        check.isVisible = checked != true
        if (checked == false) { check.text = "Couldn't check $host for new comments. Your review goes as it is."; check.icon = null; check.foreground = ReviewColors.warn }
        notes.removeAll()
        if (checked != null) replyNotes(service.meta?.drafts.orEmpty(), threads.threads, host).forEach { note ->
            notes.add(wrappingText(note, JBFont.small()).apply { foreground = ReviewColors.warn; setSize(JBUI.scale(356), Short.MAX_VALUE.toInt()) }); notes.add(vgap(6))
        }
        listOf(fineprint, keep, refused).forEach { it.setSize(JBUI.scale(356), Short.MAX_VALUE.toInt()) }
        content?.let(::fitWidth)
        popup?.pack(false, true) // the notes and the drafts warning come and go
    }
    submit.addActionListener {
        refused.isVisible = false
        finishReview(project, summary.text.trim(), verdict(), posted = { popup?.cancel() }) { reason ->
            // the host said no: say why here, where the click was; the comments are still drafts
            refused.text = reason
            refused.isVisible = true
            update()
        }
    }
    summary.document.addDocumentListener(object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) { drafts.summary = summary.text; update() }
    })
    choices.values.forEach { it.addActionListener { update() } }
    update()
    // the hint is its own muted label under the radio, so its colour follows the theme like every other label
    fun option(v: Verdict?, radio: JRadioButton) = listOf(radio, (v?.hint?.let { mutedLabel(it) } ?: markHint).apply { border = EmptyBorder(0, textIndent(radio), 0, 0) }, vgap(4))
    content = stack(
        JBLabel("Finish your review").apply { font = JBFont.label().asBold() }, vgap(4),
        fineprint, vgap(6),
        check, notes, vgap(4),
        JBScrollPane(summary), vgap(8),
        *choices.filterKeys { it != null }.flatMap { (v, radio) -> option(v, radio) }.toTypedArray(),
        JSeparator(), vgap(6),
        *option(null, choices.getValue(null)).toTypedArray(),
        keep, refused, vgap(8),
        JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply { isOpaque = false; add(cancel); add(submit) },
    ).apply { border = JBUI.Borders.empty(12); fitWidth(this) }
    popup = JBPopupFactory.getInstance().createComponentPopupBuilder(content, summary)
        .setRequestFocus(true).setFocusable(true).setCancelOnClickOutside(true)
        .setCancelOnWindowDeactivation(false) // switching to another app and back keeps it open
        .setCancelCallback { service.ending != ReviewService.Ending.POSTING } // while it posts, the popup stays: it is where the answer shows
        .createPopup()
    service.onChanged(popup) { if (!popup.isDisposed) update() } // posting, exiting, a refusal: the one ending state redraws it
    if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
    if (checked == null) background(project, "Checking $host for new comments", { threads.reload() }, current = { !popup.isDisposed }) { read ->
        checked = read
        update()
    }
}

/** The popup's fixed width, its height whatever the visible parts need. */
private fun fitWidth(content: JComponent) {
    content.preferredSize = null
    content.preferredSize = Dimension(JBUI.scale(380), content.preferredSize.height)
}

/** Where a radio button's label starts, so a hint under it lines up with the label in every theme. */
private fun textIndent(radio: JRadioButton): Int =
    radio.insets.left + ((radio.icon ?: UIManager.getIcon("RadioButton.icon"))?.iconWidth ?: 0) + radio.iconTextGap

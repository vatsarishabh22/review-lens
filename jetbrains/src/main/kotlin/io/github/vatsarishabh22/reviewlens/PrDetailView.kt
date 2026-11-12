package io.github.vatsarishabh22.reviewlens

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JToggleButton
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * One PR before its review starts: what it is, Start review (with the park card when the reviewer has uncommitted
 * work, then the start steps), and the scope list. Loads the PR's commits and checks when opened.
 */
class PrDetailView(private val project: Project, private val onBack: () -> Unit, private val onOpen: (Int) -> Unit) : JPanel(BorderLayout()) {

    private val content = ScrollStack(JBUI.Borders.empty(8, 14, 16, 14))
    private val actionSlot = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
    private val scopeSlot = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
    private val checksLabel = JBLabel().apply { font = JBFont.small() }
    /** "on top of #p" waits for the fetch; the counts are [prSize]'s. */
    private val onTop = JPanel(BorderLayout()).apply { isOpaque = false }
    private val stackSlot = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
    private val statsSlot = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
    private var own: List<FileChange>? = null
    private var stacked = false
    private var listFiles: Int? = null

    private var listed: ListedTarget? = null
    /** Bumped at each opening of the page: an answer belongs to the opening that asked, not to the PR (reopened, it is the same object). */
    private var opened = 0
    private var session: ReviewSession? = null
    private var loaded: LoadedTarget? = null
    private var progress: ReviewProgress? = null
    private var picked: Set<Int> = emptySet()
    /** "Since your last review" for a returning reviewer (null when nothing is new), and whether it is the chosen scope. */
    private var since: Pair<ScopeRange, String>? = null
    private var sinceChosen = false
    /** The scope was picked by hand: a later count from the host leaves it alone. */
    private var scopePicked = false
    /** Start review was clicked (checking, the park card, the steps): a later count changes nothing on screen. */
    private var starting = false
    /** The fetch failed (offline, no access): say so and offer a retry instead of "Fetching…" forever. */
    private var fetchFailed = false

    init {
        add(JBScrollPane(content).apply { border = null; horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER })
    }

    /**
     * Shows [target] and starts fetching it; [progress] when the reviewer has started this PR before. [stacked] and
     * [listFiles] are the list's: whether it counts the PR's own files, and its count.
     */
    fun open(git: GitClient, target: ListedTarget, progress: ReviewProgress?, stacked: Boolean, listFiles: Int?) {
        listed = target; this.progress = progress; this.stacked = stacked; this.listFiles = listFiles; loaded = null; own = null; picked = emptySet(); since = null; sinceChosen = false; scopePicked = false; fetchFailed = false
        session = ReviewSession(git)
        val opening = ++opened
        build()
        val session = session!!
        background(project, "Fetching ${target.target.label}", {
            val loaded = session.load(target.target, target.parent)
            Triple(loaded, sinceLastReview(loaded.head, loaded.commits, ReviewMeta.load(git, target.target.key)?.seenHead),
                loaded.parent?.let { git.changes(ScopeRange(loaded.start, loaded.head)) })
        }, current = { opened == opening }, failed = { fetchFailed = true; showStart(); showScope() }) { (result, sinceRange, ownChanges) ->
            // new commits since a finished review: what's new is the scope to review, already chosen; the progress as it
            // is now, which the host's answer on what changed may have updated while this fetched
            loaded = result; own = ownChanges; since = sinceRange; sinceChosen = sinceRange != null && this.progress?.newCommits == true
            showStack(); showStats(); showStart(); showScope()
        }
        target.pr?.let { pr ->
            checksLabel.text = ""
            background(project, "Loading checks for ${ReviewTarget.refOf(pr.number)}", { ReviewHosts.of(git)?.checks(pr.number) ?: Checks(ChecksState.NONE) }, current = { opened == opening }) { checks ->
                checksLabel.text = checks.text
                checksLabel.foreground = when (checks.state) {
                    ChecksState.FAILING -> ReviewColors.del
                    ChecksState.PASSED -> ReviewColors.ok
                    else -> ReviewColors.muted
                }
            }
        }
    }

    /** The open PR's progress again, once the host said what changed since: its button, and its pre-chosen scope unless picked by hand. */
    fun progress(all: Map<String, ReviewProgress>) {
        val p = all[listed?.target?.key ?: return] ?: return
        progress = p
        if (starting) return
        if (loaded != null && !scopePicked) sinceChosen = since != null && p.newCommits
        showStart(); showScope()
    }

    private fun build() {
        val target = listed!!
        val pr = target.pr
        content.removeAll()
        content.add(JButton("All pull requests", AllIcons.Actions.Back).apply { addActionListener { onBack() } }.let { row(it) })
        content.add(vgap(10))
        content.add(titleText(target.title, pr?.let { ReviewTarget.refOf(it.number) }, JBFont.label().biggerOn(2f)))
        content.add(vgap(6))
        val base = target.target.base.removePrefix("${GitClient.REMOTE}/")
        if (pr != null) {
            content.add(row(JBLabel(pr.author.name, AvatarIcon(pr.author.name, pr.author.login, 18), SwingConstants.LEFT)
                .apply { foreground = ReviewColors.muted; font = JBFont.small() }, mutedLabel("wants to merge"), gap = 4))
            content.add(vgap(4))
        }
        content.add(row(Pill(target.target.head.removePrefix("${GitClient.REMOTE}/"), ReviewColors.chip, ReviewColors.text, mono = true),
            mutedLabel("into"), Pill(base, ReviewColors.chip, ReviewColors.text, mono = true), onTop, gap = 6))
        content.add(stackSlot)
        pr?.body?.let(::markdownHtml)?.let { content.add(vgap(10)); content.add(PrDescription(it)) }
        if (pr != null) {
            content.add(vgap(10))
            content.add(statsSlot)
            content.add(vgap(2))
            content.add(row(mutedLabel("Updated ${ago(Instant.parse(pr.updatedAt).toEpochMilli())}")))
        }
        content.add(vgap(12))
        content.add(actionSlot)
        content.add(vgap(14))
        content.add(row(sectionLabel("What to review")))
        content.add(vgap(6))
        content.add(scopeSlot)
        showStack()
        showStats()
        showStart()
        showScope()
        content.revalidate(); content.repaint()
    }

    /** "on top of #p", a link to it; or, when it can only compare with its base, why. */
    private fun showStack() {
        val loaded = loaded
        onTop.removeAll(); stackSlot.removeAll()
        loaded?.parent?.let { p -> onTop.add(row(mutedLabel("· on top of"), ActionLink(ReviewTarget.refOf(p)) { onOpen(p) }, gap = 4)) }
        loaded?.note?.let { stackSlot.add(wrappingText(it, JBFont.small()).apply { foreground = ReviewColors.warn; border = JBUI.Borders.emptyTop(6) }) }
        listOf(onTop, stackSlot).forEach { it.revalidate(); it.repaint() }
    }

    private fun showStats() {
        val pr = listed?.pr ?: return
        val size = prSize(pr, stacked, loaded != null, listFiles, own)
        statsSlot.removeAll()
        statsSlot.add(row(*listOfNotNull(size?.let { mutedLabel(files(it.files)) },
            size?.added?.let { JBLabel("+$it").apply { foreground = ReviewColors.add; font = JBFont.small() } },
            size?.deleted?.let { JBLabel("−$it").apply { foreground = ReviewColors.del; font = JBFont.small() } }, checksLabel).toTypedArray()))
        statsSlot.revalidate(); statsSlot.repaint()
    }

    private fun showStart() {
        starting = false
        // a start, a re-scope, a Finish or an Exit under way: Start waits for it, as Finish and Exit do
        val busy = ReviewService.of(project).ending
        val ready = loaded != null && loaded!!.commits.isNotEmpty() && busy == null
        val start = primaryButton(progress?.startLabel(since != null) ?: "Start review").apply {
            isEnabled = ready
            addActionListener { checkWorkThenStart() }
        }
        // while greyed out, the button says why: on hover, and beside it while the fetch runs
        val (status, hint) = when {
            busy != null -> null to "${busy.busy} Start review is back once that's done."
            fetchFailed -> null to "Couldn't fetch the PR from ${ReviewHosts.nameOf(session?.git)}."
            loaded == null -> JBLabel("Fetching the PR from ${ReviewHosts.nameOf(session?.git)}…", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT)
                .apply { foreground = ReviewColors.muted; font = JBFont.small() } to null
            loaded!!.commits.isEmpty() -> null to "Nothing to review: no commits beyond ${listed!!.target.base}."
            else -> null to "Opens the PR in this window, read-only."
        }
        if (!ready) start.toolTipText = hint ?: status?.text
        val retry = ActionLink("Try again") { open(session!!.git, listed!!, progress, stacked, listFiles) }.takeIf { fetchFailed }
        setAction(stack(
            row(*listOfNotNull<JComponent>(start, status).toTypedArray(), gap = 10), vgap(8),
            row(*listOfNotNull<JComponent>(hint?.let(::mutedLabel), retry).toTypedArray(), gap = 6),
        ))
    }

    /** The review's busy state changed: the Start button follows it, unless this page is already starting. */
    fun busyChanged() {
        if (listed != null && !starting) showStart()
    }

    private fun checkWorkThenStart() {
        val git = session!!.git
        val opening = opened
        starting = true
        setAction(stack(row(JBLabel("Checking your working tree…", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT))))
        background(project, "Checking your working tree", { git.dirtyFiles() to git.currentRef() }, current = { opened == opening }, failed = ::showStart) { (dirty, branch) ->
            if (dirty.isEmpty()) start(dirty, branch) else showParkCard(dirty, branch)
        }
    }

    private fun showParkCard(dirty: List<String>, branch: String) {
        val card = stack(
            wrappingText("You have ${dirty.size} uncommitted ${if (dirty.size == 1) "change" else "changes"} on $branch", JBFont.label().asBold()),
            vgap(6),
            *dirty.take(12).map { wrappingText("•  ${it.take(2).trim()}  ${it.drop(3)}", JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, 12))) }.toTypedArray(),
            *(if (dirty.size > 12) arrayOf(wrappingText("…and ${dirty.size - 12} more", JBFont.small())) else emptyArray()),
            vgap(6),
            wrappingText("Review Lens parks them in a stash named after this review and puts them back when you exit. Nothing is committed.", JBFont.small())
                .apply { foreground = ReviewColors.muted },
            vgap(8),
            row(primaryButton("Park and start review").apply { addActionListener { start(dirty, branch) } },
                JButton("Cancel").apply { isOpaque = false; addActionListener { showStart() } }),
        ).apply {
            isOpaque = true
            background = ReviewColors.review
            border = JBUI.Borders.compound(RoundedLineBorder(ReviewColors.reviewLine, JBUI.scale(8)), JBUI.Borders.empty(10, 12))
        }
        setAction(card)
    }

    private fun start(dirty: List<String>, branch: String) {
        val loaded = loaded ?: return
        val pr = listed!!.target.prNumber
        val steps = listOfNotNull(
            if (pr != null) "Fetching PR ${ReviewTarget.refOf(pr)}" else "Fetching ${listed!!.target.head}",
            if (dirty.isNotEmpty()) "Parking your ${dirty.size} ${if (dirty.size == 1) "change" else "changes"} on $branch" else null,
            "Checking out the PR head",
            "Opening the first file",
        )
        fun showSteps(now: Int) = setAction(stack(*steps.mapIndexed { i, text ->
            val icon = when {
                i < now -> AllIcons.General.InspectionsOK
                i == now -> AnimatedIcon.Default.INSTANCE
                else -> EmptyIcon.ICON_16
            }
            row(JBLabel(text, icon, SwingConstants.LEFT).apply { if (i > now) foreground = ReviewColors.muted; iconTextGap = JBUI.scale(8) })
        }.flatMap { listOf(it, vgap(6)) }.toTypedArray()))
        showSteps(1)
        val picks = picked.map { loaded.commits[it] }
        val (range, label) = since?.takeIf { sinceChosen } ?: (resolveScope(loaded.start, loaded.commits, picks) to scopeLabel(loaded.commits, picks))
        beginReview(project, session!!, loaded, range, label,
            progress = { step -> showSteps(steps.indexOfFirst { if (step == StartStep.PARK) it.startsWith("Parking") else it.startsWith("Checking out") }) },
            failed = ::showStart,
        )
    }

    private fun showScope() {
        val loaded = loaded
        if (loaded == null) {
            val text = if (fetchFailed) "Commits appear once the PR is fetched." else "Loading commits…"
            scopeSlot.removeAll(); scopeSlot.add(row(mutedLabel(text))); scopeSlot.revalidate(); return
        }
        val box = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = RoundedLineBorder(ReviewColors.line, JBUI.scale(8))
            background = ReviewColors.surface
            alignmentX = LEFT_ALIGNMENT
        }
        val base = listed!!.target.base.removePrefix("${GitClient.REMOTE}/")
        val all = JRadioButton().apply { isSelected = picked.isEmpty() && !sinceChosen; isOpaque = false }
        val whole = loaded.parent?.let { "Everything this PR adds on top of ${ReviewTarget.refOf(it)}" } ?: "The whole PR against $base"
        box.add(scopeRow(all, stack(JBLabel("All commits").apply { font = JBFont.label().asBold() }, mutedLabel(whole))) {
            picked = emptySet(); sinceChosen = false; scopePicked = true; showScope()
        })
        since?.let { (range, _) ->
            val radio = JRadioButton().apply { isSelected = sinceChosen; isOpaque = false }
            val count = loaded.commits.indexOfFirst { it.sha == range.from }
            box.add(divider())
            box.add(scopeRow(radio, stack(JBLabel("Since your last review").apply { font = JBFont.label().asBold() },
                mutedLabel("$count ${if (count == 1) "commit" else "commits"} pushed after you last looked"))) {
                picked = emptySet(); sinceChosen = true; scopePicked = true; showScope()
            })
        }
        loaded.commits.forEachIndexed { i, commit ->
            val check = JCheckBox().apply { isSelected = i in picked; isOpaque = false }
            val subject = wrappingText("${commit.sha.take(7)}  ${commit.subject}")
            val who = mutedLabel(listOf(commit.author, if (commit.time > 0) ago(commit.time * 1000) else "").filter { it.isNotEmpty() }.joinToString(" · "))
            box.add(divider())
            box.add(scopeRow(check, stack(subject, row(who))) { picked = toggleScope(picked, i); sinceChosen = false; scopePicked = true; showScope() })
        }
        scopeSlot.removeAll(); scopeSlot.add(box); scopeSlot.revalidate(); scopeSlot.repaint()
    }

    /** A scope option: the control on the left, its text on the right; a click anywhere on the row picks it. */
    private fun scopeRow(control: JToggleButton, text: JComponent, onPick: () -> Unit) = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(6, 8)
        add(control.apply { verticalAlignment = SwingConstants.TOP; addActionListener { onPick() } }, BorderLayout.WEST)
        add(text)
        val click = object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = onPick() }
        addMouseListener(click)
        text.addMouseListener(click)
        text.components.forEach { it.addMouseListener(click) }
    }

    private fun setAction(component: JComponent) {
        actionSlot.removeAll(); actionSlot.add(component); actionSlot.revalidate(); actionSlot.repaint()
    }

    private fun ago(epochMillis: Long) = DateFormatUtil.formatBetweenDates(epochMillis, System.currentTimeMillis())
}

// Not opaque: an opaque button fills its corners with the panel colour, a dark box on the park card.
private fun primaryButton(text: String) = JButton(text).apply { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true); isOpaque = false }

private fun divider() = JPanel().apply {
    background = ReviewColors.line
    preferredSize = Dimension(1, 1); maximumSize = Dimension(Int.MAX_VALUE, 1)
    alignmentX = Component.LEFT_ALIGNMENT
}

/** A PR's size on its detail page: its files, and lines added and deleted when known. */
data class PrSize(val files: Int, val added: Int?, val deleted: Int?)

/**
 * A stacked PR's own changes once [fetched]; while it loads, its own count from the list without lines (the host's
 * numbers are the whole PR's, its parent's changes included), or nothing while the list has none. Any other PR, and one
 * fetched with no parent (it left its stack since the list), shows the host's.
 */
fun prSize(pr: PullRequest, stacked: Boolean, fetched: Boolean, listFiles: Int?, own: List<FileChange>?): PrSize? = when {
    own != null -> PrSize(own.size, own.sumOf { it.added ?: 0 }, own.sumOf { it.deleted ?: 0 })
    stacked && !fetched -> listFiles?.let { PrSize(it, null, null) }
    else -> PrSize(pr.changedFiles, pr.additions, pr.deletions)
}

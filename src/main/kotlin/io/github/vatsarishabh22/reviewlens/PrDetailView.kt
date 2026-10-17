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
class PrDetailView(private val project: Project, private val onBack: () -> Unit) : JPanel(BorderLayout()) {

    private val content = StackPanel()
    private val actionSlot = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
    private val scopeSlot = JPanel(BorderLayout()).apply { isOpaque = false; alignmentX = LEFT_ALIGNMENT }
    private val checksLabel = JBLabel().apply { font = JBFont.small() }

    private var listed: ListedTarget? = null
    private var session: ReviewSession? = null
    private var loaded: LoadedTarget? = null
    private var resuming = false
    private var picked: Set<Int> = emptySet()
    /** "Since your last review" for a returning reviewer (null when nothing is new), and whether it is the chosen scope. */
    private var since: Pair<ScopeRange, String>? = null
    private var sinceChosen = false
    /** The fetch failed (offline, no access): say so and offer a retry instead of "Fetching…" forever. */
    private var fetchFailed = false

    init {
        add(JBScrollPane(content).apply { border = null; horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER })
    }

    /** Shows [target] and starts fetching it; [resuming] when the reviewer has started this PR before. */
    fun open(git: GitClient, target: ListedTarget, resuming: Boolean) {
        listed = target; this.resuming = resuming; loaded = null; picked = emptySet(); since = null; sinceChosen = false; fetchFailed = false
        session = ReviewSession(git)
        build()
        val session = session!!
        background(project, "Fetching ${target.target.label}", {
            session.load(target.target).let { it to sinceLastReview(it.head, it.commits, ReviewMeta.load(git, target.target.key)?.seenHead) }
        }, failed = { if (listed === target) { fetchFailed = true; showStart(); showScope() } }) { (result, sinceRange) ->
            if (listed === target) { loaded = result; since = sinceRange; showStart(); showScope() }
        }
        target.pr?.let { pr ->
            checksLabel.text = ""
            background(project, "Loading checks for #${pr.number}", { GhClient(git.root).checks(pr.number) }) { checks ->
                if (listed !== target) return@background
                checksLabel.text = checks.text
                checksLabel.foreground = when (checks.state) {
                    ChecksState.FAILING -> ReviewColors.del
                    ChecksState.PASSED -> ReviewColors.ok
                    else -> ReviewColors.muted
                }
            }
        }
    }

    private fun build() {
        val target = listed!!
        val pr = target.pr
        content.removeAll()
        content.add(JButton("All pull requests", AllIcons.Actions.Back).apply { addActionListener { onBack() } }.let { row(it) })
        content.gap(10)
        content.add(titleText(target.title, pr?.let { "#${it.number}" }, JBFont.label().biggerOn(2f)))
        content.gap(6)
        val base = target.target.base.removePrefix("${GitClient.REMOTE}/")
        if (pr != null) {
            content.add(row(JBLabel(pr.author.displayName, AvatarIcon(pr.author.displayName, pr.author.login, 18), SwingConstants.LEFT)
                .apply { foreground = ReviewColors.muted; font = JBFont.small() }, mutedLabel("wants to merge"), gap = 4))
            content.gap(4)
        }
        content.add(row(Pill(target.target.head.removePrefix("${GitClient.REMOTE}/"), ReviewColors.chip, JBUI.CurrentTheme.Label.foreground(), mono = true),
            mutedLabel("into"), Pill(base, ReviewColors.chip, JBUI.CurrentTheme.Label.foreground(), mono = true), gap = 6))
        description(pr?.body)?.let { content.gap(10); content.add(wrappingText(it)) }
        if (pr != null) {
            content.gap(10)
            content.add(row(mutedLabel(files(pr.changedFiles)),
                JBLabel("+${pr.additions}").apply { foreground = ReviewColors.add; font = JBFont.small() },
                JBLabel("−${pr.deletions}").apply { foreground = ReviewColors.del; font = JBFont.small() }, checksLabel))
            content.gap(2)
            content.add(row(mutedLabel("Updated ${ago(Instant.parse(pr.updatedAt).toEpochMilli())}")))
        }
        content.gap(12)
        content.add(actionSlot)
        content.gap(14)
        content.add(row(sectionLabel("What to review")))
        content.gap(6)
        content.add(scopeSlot)
        showStart()
        showScope()
        content.revalidate(); content.repaint()
    }

    private fun showStart() {
        val ready = loaded != null && loaded!!.commits.isNotEmpty()
        val start = primaryButton(if (resuming) "Resume review" else "Start review").apply {
            isEnabled = ready
            addActionListener { checkWorkThenStart() }
        }
        // while greyed out, the button says why: on hover, and beside it while the fetch runs
        val (status, hint) = when {
            fetchFailed -> null to "Couldn't fetch the PR from GitHub."
            loaded == null -> JBLabel("Fetching the PR from GitHub…", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT)
                .apply { foreground = ReviewColors.muted; font = JBFont.small() } to null
            loaded!!.commits.isEmpty() -> null to "Nothing to review: no commits beyond ${listed!!.target.base}."
            else -> null to "Opens the PR in this window, read-only."
        }
        if (!ready) start.toolTipText = hint ?: status?.text
        val retry = ActionLink("Try again") { open(session!!.git, listed!!, resuming) }.takeIf { fetchFailed }
        setAction(stack(
            row(*listOfNotNull<JComponent>(start, status).toTypedArray(), gap = 10), vgap(8),
            row(*listOfNotNull<JComponent>(hint?.let(::mutedLabel), retry).toTypedArray(), gap = 6),
        ))
    }

    private fun checkWorkThenStart() {
        val git = session!!.git
        setAction(stack(row(JBLabel("Checking your working tree…", AnimatedIcon.Default.INSTANCE, SwingConstants.LEFT))))
        background(project, "Checking your working tree", { git.dirtyFiles() to git.currentRef() }, failed = ::showStart) { (dirty, branch) ->
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
            if (pr != null) "Fetching PR #$pr" else "Fetching ${listed!!.target.head}",
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
        val (range, label) = since?.takeIf { sinceChosen } ?: (resolveScope(loaded.mergeBase, loaded.commits, picks) to scopeLabel(loaded.commits, picks))
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
            background = JBUI.CurrentTheme.ToolWindow.background()
            alignmentX = LEFT_ALIGNMENT
        }
        val base = listed!!.target.base.removePrefix("${GitClient.REMOTE}/")
        val all = JRadioButton().apply { isSelected = picked.isEmpty() && !sinceChosen; isOpaque = false }
        box.add(scopeRow(all, stack(JBLabel("All commits").apply { font = JBFont.label().asBold() }, mutedLabel("The whole PR against $base"))) {
            picked = emptySet(); sinceChosen = false; showScope()
        })
        since?.let { (range, _) ->
            val radio = JRadioButton().apply { isSelected = sinceChosen; isOpaque = false }
            val count = loaded.commits.indexOfFirst { it.sha == range.from }
            box.add(divider())
            box.add(scopeRow(radio, stack(JBLabel("Since your last review").apply { font = JBFont.label().asBold() },
                mutedLabel("$count ${if (count == 1) "commit" else "commits"} pushed after you last looked"))) {
                picked = emptySet(); sinceChosen = true; showScope()
            })
        }
        loaded.commits.forEachIndexed { i, commit ->
            val check = JCheckBox().apply { isSelected = i in picked; isOpaque = false }
            val subject = wrappingText("${commit.sha.take(7)}  ${commit.subject}")
            val who = mutedLabel(listOf(commit.author, if (commit.time > 0) ago(commit.time * 1000) else "").filter { it.isNotEmpty() }.joinToString(" · "))
            box.add(divider())
            box.add(scopeRow(check, stack(subject, row(who))) { picked = toggleScope(picked, i); sinceChosen = false; showScope() })
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

    /** The PR body as plain text: HTML comments (PR templates) dropped, blank runs collapsed, capped. */
    private fun description(body: String?): String? {
        val text = body.orEmpty().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        return text.ifEmpty { null }?.let { if (it.length > 600) it.take(600).trimEnd() + "…" else it }
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


/** A vertical stack that tracks the scroll pane's width, so wrapped text reflows instead of scrolling sideways. */
private class StackPanel : JPanel(), Scrollable {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8, 14, 16, 14)
    }

    fun gap(height: Int) { add(vgap(height)) }

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

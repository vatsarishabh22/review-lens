package io.github.vatsarishabh22.reviewlens

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import com.intellij.util.text.DateFormatUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Timer

/** The review in progress in this checkout, if any: its changed files, reviewed state and blame. */
@Service(Service.Level.PROJECT)
class ReviewService(private val project: Project) : Disposable {

    override fun dispose() = flashEnd.stop()

    @Volatile var git: GitClient? = null; private set
    private val blames = ConcurrentHashMap<String, Blame>()
    private val blamesPending = ConcurrentHashMap.newKeySet<String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var active: ActiveReview? = null; private set
    @Volatile var meta: ReviewMeta? = null; private set
    @Volatile var changes: List<FileChange> = emptyList(); private set
    /** Every tracked path at the review's checkout, for the panel's whole-project view. */
    @Volatile var trackedFiles: List<String> = emptyList(); private set
    @Volatile private var scopeCommits: Set<String> = emptySet()

    val isActive get() = meta != null

    /** True for a moment after a blocked edit: the review bar shows "Read-only while reviewing" (step 4, option A). */
    @Volatile var flashing = false; private set
    private val flashEnd = Timer(2200) { flashing = false; EditorNotifications.getInstance(project).updateAllNotifications() }
        .apply { isRepeats = false }

    init {
        // Inline blame shows on the caret's line only, so a caret move must repaint.
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (isActive && event.editor.project == project) event.editor.contentComponent.repaint()
            }
        }, this)
    }

    /** Re-reads which review is active and recomputes its changes. Runs git: call off EDT. */
    fun reload() {
        val git = git ?: project.basePath?.let { runCatching { GitClient.forProject(it) }.getOrNull() }?.also { git = it } ?: return
        val review = ReviewLifecycle(git).active()
        val fresh = review?.let { ReviewMeta.load(git, it.target.key) }
        changes = fresh?.let { git.changes(it.range) }.orEmpty()
        scopeCommits = fresh?.let { git.commitShas(it.range) }.orEmpty()
        trackedFiles = if (fresh != null) git.trackedFiles() else emptyList()
        active = review
        meta = fresh
        blames.clear()
        fireChanged()
    }

    fun relativePath(file: VirtualFile): String? {
        val base = git?.root?.toString() ?: return null
        return file.path.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")
    }

    fun changeFor(file: VirtualFile): FileChange? = relativePath(file)?.let { path -> changes.find { it.path == path } }

    fun badge(change: FileChange): Badge = when (meta?.reviewed?.get(change.path)) {
        null -> Badge.TODO
        change.newBlob -> Badge.REVIEWED
        else -> Badge.CHANGED_SINCE
    }

    fun reviewedCount() = changes.count { badge(it) == Badge.REVIEWED }

    /** Unreviewed files under [dir] ("" = the whole repository), for folder badges. */
    fun pendingUnder(dir: String) = changes.count { (dir.isEmpty() || it.path.startsWith("$dir/")) && badge(it) != Badge.REVIEWED }

    /** Marks a file reviewed, or un-marks it when it already is. */
    fun toggleReviewed(change: FileChange) {
        val current = meta ?: return
        val reviewed = if (badge(change) == Badge.REVIEWED) current.reviewed - change.path else current.reviewed + (change.path to change.newBlob)
        meta = current.copy(reviewed = reviewed).also { ReviewMeta.save(git!!, it) }
        fireChanged()
    }

    /** The file before [before] in path order, wrapping around. */
    fun previous(before: String?): FileChange? = changes.getOrNull(previousIndex(changes.size, changes.indexOfFirst { it.path == before }))

    /** Called when an edit was refused; extends the flash while the reviewer keeps typing. */
    fun flashReadOnly() = ApplicationManager.getApplication().invokeLater({
        flashing = true
        flashEnd.restart()
        EditorNotifications.getInstance(project).updateAllNotifications()
    }, project.disposed)

    /** The first file after [after] (in path order, wrapping around) that is not reviewed yet. */
    fun nextUnreviewed(after: String?): FileChange? {
        val start = changes.indexOfFirst { it.path == after } + 1
        return (changes.drop(start) + changes.take(start)).firstOrNull { badge(it) != Badge.REVIEWED && it.path != after }
    }

    /**
     * Blame text for a 0-based line, only when a commit in the review scope last changed it.
     * Blame runs once per file in the background; until it lands this returns null and the editor repaints afterwards.
     */
    fun blameText(file: VirtualFile, line: Int): String? {
        val change = changeFor(file) ?: return null
        val blame = blames[change.path] ?: return null.also { computeBlame(file, change.path) }
        val sha = blame.lines[line + 1]?.takeIf { it in scopeCommits } ?: return null
        val commit = blame.commits[sha] ?: return null
        return "${sha.take(7)} · ${commit.author} · ${DateFormatUtil.formatPrettyDate(commit.time * 1000)} · ${commit.summary}"
    }

    private fun computeBlame(file: VirtualFile, path: String) {
        val to = meta?.range?.to ?: return
        if (!blamesPending.add(path)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            // A failed blame (binary file) caches as empty, so painting never re-runs git for it.
            blames[path] = runCatching { git!!.blame(to, path) }.getOrDefault(Blame(emptyMap(), emptyMap()))
            blamesPending.remove(path)
            ApplicationManager.getApplication().invokeLater({
                EditorFactory.getInstance().allEditors.filter { it.virtualFile == file }.forEach { it.contentComponent.repaint() }
            }, project.disposed)
        }
    }

    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun fireChanged() = ApplicationManager.getApplication().invokeLater({
        listeners.forEach { it() }
        EditorNotifications.getInstance(project).updateAllNotifications()
        ProjectView.getInstance(project).refresh()
    }, project.disposed)

    companion object {
        fun of(project: Project): ReviewService = project.service()
    }
}

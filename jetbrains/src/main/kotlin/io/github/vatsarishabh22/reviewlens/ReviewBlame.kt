package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.text.DateFormatUtil
import java.util.concurrent.ConcurrentHashMap

/**
 * Which commit in the review's scope last changed a line, for [InlineBlame]. Blame runs once per file in the background
 * and is kept per scope range: a blame at a given commit never changes, so a new range is the only thing that empties it.
 */
@Service(Service.Level.PROJECT)
class ReviewBlame(private val project: Project) : Disposable {

    private val review get() = ReviewService.of(project)
    @Volatile private var range: ScopeRange? = null
    @Volatile private var scopeCommits: Set<String>? = null
    private val blames = ConcurrentHashMap<String, Blame>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    init {
        // Inline blame shows on the caret's line only, so a caret move must repaint.
        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (review.isActive && event.editor.project == project) event.editor.contentComponent.repaint()
            }
        }, this)
    }

    override fun dispose() = Unit

    /** Blame text for a 0-based line, or null until it is computed (the editor repaints then) or when the scope didn't touch the line. */
    fun blameText(file: VirtualFile, line: Int): String? {
        val scope = review.meta?.range ?: return null
        val change = review.changeFor(file) ?: return null
        forRange(scope)
        val blame = blames[change.path]
        val commits = scopeCommits
        if (blame == null || commits == null) return null.also { compute(file, change.path, scope) }
        val sha = blame.lines[line + 1]?.takeIf { it in commits } ?: return null
        val commit = blame.commits[sha] ?: return null
        return "${sha.take(7)} · ${commit.author} · ${DateFormatUtil.formatPrettyDate(commit.time * 1000)} · ${commit.summary}"
    }

    @Synchronized
    private fun forRange(scope: ScopeRange) {
        if (scope == range) return
        range = scope; scopeCommits = null; blames.clear(); pending.clear()
    }

    private fun compute(file: VirtualFile, path: String, scope: ScopeRange) {
        val git = review.git ?: return
        if (!pending.add(path)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val commits = scopeCommits ?: runCatching { git.commitShas(scope) }.getOrDefault(emptySet())
            // A failed blame (binary file) caches as empty, so painting never re-runs git for it.
            val blame = runCatching { git.blame(scope.to, path) }.getOrDefault(Blame(emptyMap(), emptyMap()))
            synchronized(this) { if (scope == range) { scopeCommits = commits; blames[path] = blame } }
            pending.remove(path)
            ApplicationManager.getApplication().invokeLater({
                EditorFactory.getInstance().allEditors.filter { it.virtualFile == file }.forEach { it.contentComponent.repaint() }
            }, project.disposed)
        }
    }

    companion object {
        fun of(project: Project): ReviewBlame = project.service()
    }
}

package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The impact map of the review in progress (spec/impact.md): per changed file, what uses the functions its scope
 * changed. Searched in the background once per scope range, the open file first; a new range starts over.
 */
@Service(Service.Level.PROJECT)
class ImpactService(private val project: Project) : Disposable {

    /** What the impact map has for one changed file; a file that isn't code, or isn't read yet, has nothing. */
    sealed interface FileImpact {
        /** Code the IDE can't analyse: one line says so (spec/impact.md, "Timing and failure"). */
        data class Unavailable(val language: String) : FileImpact
        /** Every function the scope changed in the file, each [Uses.Searching] until its search ends; one whose search threw is left out. */
        data class Ready(val language: ImpactLanguage, val impacts: List<Impact>) : FileImpact
    }

    private val files = RunMap<String, FileImpact>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var range: ScopeRange? = null

    init {
        val review = ReviewService.of(project)
        review.onChanged(this) { refresh(review) }
        refresh(review)
    }

    override fun dispose() { files.start() } // any run still going writes nothing

    fun of(path: String): FileImpact? = files[path]

    /** Every changed function's tick key, once every file was searched without a failure; null before, so Exit keeps all ticks. */
    fun functions(): Set<String>? =
        files.finished()?.filterIsInstance<FileImpact.Ready>()?.flatMap { it.impacts.map(Impact::key) }?.toSet()

    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Starts over when the review's range changed: a new review, a re-scope, a push picked up, or Exit. */
    private fun refresh(review: ReviewService) {
        val now = review.meta?.range
        if (now == range) return
        val run = files.start()
        range = now
        val git = review.git
        if (now == null || git == null) return fireChanged()
        val open = FileEditorManager.getInstance(project).selectedFiles.mapNotNull(review::relativePath).toSet()
        val changes = review.changes.sortedBy { it.path !in open }
        ApplicationManager.getApplication().executeOnPooledThread { search(review, git, now, changes, run) }
    }

    /** Reads every file's changed functions first (parsing only, so it runs while the IDE indexes), then searches each. */
    private fun search(review: ReviewService, git: GitClient, range: ScopeRange, changes: List<FileChange>, run: Int) {
        val lines = runCatching { git.changedLines(range) }.getOrElse { thisLogger().info("Impact: changed lines not read", it); return }
        val inPr = changes.flatMap { listOfNotNull(it.path, it.oldPath) }.toSet()
        val languages = changes.mapNotNull { c -> impactLanguageOf(c.path)?.let { c.path to it } }.toMap()
        for (change in changes) {
            if (change.path !in languages) CodeFiles.languageOf(change.path.substringAfterLast('/'))?.let { files.put(run, change.path, FileImpact.Unavailable(it)) }
        }
        var failed = false
        val detected = changes.mapNotNull { change ->
            val language = languages[change.path] ?: return@mapNotNull null
            val oldPath = change.oldPath ?: change.path
            val before = git.show(range.from, oldPath)
            val pending = read(run, smart = false) {
                val old = PsiFileFactory.getInstance(project).createFileFromText(oldPath.substringAfterLast('/'), language.language, before)
                ImpactSearch.detect(change.path, oldPath, language, old, psiFile(git, change.path), lines[change.path] ?: LineChanges(oldPath, emptySet(), emptySet()))
            } ?: return@mapNotNull null.also { failed = true }
            files.put(run, change.path, FileImpact.Ready(language, pending))
            Triple(change, language, pending)
        }
        if (!files.current(run)) return
        fireChanged()
        for ((change, language, pending) in detected) {
            if (pending.isEmpty()) continue
            val impacts = read(run, smart = true) { ImpactSearch(project, review::relativePath, inPr).impacts(language, psiFile(git, change.path), pending) }
            if (!files.current(run)) return
            failed = failed || impacts == null
            files.put(run, change.path, FileImpact.Ready(language, impacts.orEmpty()))
            fireChanged()
        }
        files.done(run, complete = !failed)
    }

    /** [work] in a background read action, in smart mode when it needs the indexes; null when it threw or the run is stale. */
    private fun <T : Any> read(run: Int, smart: Boolean, work: () -> T): T? {
        if (!files.current(run)) return null
        return try {
            ReadAction.nonBlocking<T> { work() }.let { if (smart) it.inSmartMode(project) else it }.expireWith(this).executeSynchronously()
        } catch (_: ProcessCanceledException) {
            null // the service closed with the project: nothing to say
        } catch (e: Exception) {
            thisLogger().info("Impact: not read", e)
            null
        }
    }

    private fun psiFile(git: GitClient, path: String) =
        LocalFileSystem.getInstance().findFileByNioFile(git.root.resolve(path))?.let(PsiManager.getInstance(project)::findFile)

    /** The [ImpactLanguage] that reads [path] in this IDE, by its file type; null when none does. */
    private fun impactLanguageOf(path: String): ImpactLanguage? =
        (FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/')) as? LanguageFileType)?.language?.let(ImpactLanguage::of)

    private fun fireChanged() = ApplicationManager.getApplication().invokeLater({ listeners.forEach { it() } }, project.disposed)

    companion object {
        fun of(project: Project): ImpactService = project.service()
    }
}

/**
 * The impact map's files, filled by runs: starting a run clears it, and a write or a [done] from any run but the newest
 * is dropped under the same lock, so a run overtaken by a re-scope never lands in the next one's map, nor marks it complete.
 */
internal class RunMap<K : Any, V : Any> {
    private val map = ConcurrentHashMap<K, V>()
    private var run = 0
    private var complete = false

    @Synchronized fun start(): Int { map.clear(); complete = false; return ++run }
    @Synchronized fun current(run: Int) = run == this.run
    @Synchronized fun put(run: Int, key: K, value: V) { if (current(run)) map[key] = value }
    @Synchronized fun done(run: Int, complete: Boolean) { if (current(run)) this.complete = complete }
    operator fun get(key: K): V? = map[key]
    /** The newest run's files once it searched every file without a failure, read in one step with that fact; null before. */
    @Synchronized fun finished(): List<V>? = if (complete) map.values.toList() else null
}

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
import java.util.concurrent.atomic.AtomicInteger

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

    private val files = ConcurrentHashMap<String, FileImpact>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val generation = AtomicInteger()
    @Volatile private var range: ScopeRange? = null
    @Volatile private var complete = false

    init {
        val review = ReviewService.of(project)
        review.onChanged(this) { refresh(review) }
        refresh(review)
    }

    override fun dispose() { generation.incrementAndGet() }

    fun of(path: String): FileImpact? = files[path]

    /** Every changed function's tick key, once every file was searched without a failure; null before, so Exit keeps all ticks. */
    fun functions(): Set<String>? =
        if (!complete) null else files.values.filterIsInstance<FileImpact.Ready>().flatMap { it.impacts.map(Impact::key) }.toSet()

    fun onChanged(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    /** Starts over when the review's range changed: a new review, a re-scope, a push picked up, or Exit. */
    private fun refresh(review: ReviewService) {
        val now = review.meta?.range
        if (now == range) return
        val run = generation.incrementAndGet()
        range = now
        complete = false
        files.clear()
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
            if (change.path !in languages) CodeFiles.languageOf(change.path.substringAfterLast('/'))?.let { files[change.path] = FileImpact.Unavailable(it) }
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
            files[change.path] = FileImpact.Ready(language, pending)
            Triple(change, language, pending)
        }
        if (run != generation.get()) return
        fireChanged()
        for ((change, language, pending) in detected) {
            if (pending.isEmpty()) continue
            val impacts = read(run, smart = true) { ImpactSearch(project, review::relativePath, inPr).impacts(language, psiFile(git, change.path), pending) }
            if (run != generation.get()) return
            failed = failed || impacts == null
            files[change.path] = FileImpact.Ready(language, impacts.orEmpty())
            fireChanged()
        }
        complete = !failed
    }

    /** [work] in a background read action, in smart mode when it needs the indexes; null when it threw or the run is stale. */
    private fun <T : Any> read(run: Int, smart: Boolean, work: () -> T): T? {
        if (run != generation.get()) return null
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

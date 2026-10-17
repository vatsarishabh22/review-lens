package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ex.SimpleLocalLineStatusTracker
import com.intellij.openapi.vfs.VirtualFile

/**
 * Gutter marks for every open file the review changed, against the scope's base: green added, blue modified,
 * a grey arrow where lines were deleted, with the IDE's own "show change" pop-up. Uses the platform's stable
 * line-status tracker (what Git's light mode uses), so the marks look and behave exactly like normal VCS marks.
 * Trackers exist only while a review is in progress. Tracker calls happen on EDT; base text is read off EDT.
 */
@Service(Service.Level.PROJECT)
class ReviewGutter(private val project: Project) : Disposable {

    private val trackers = HashMap<VirtualFile, SimpleLocalLineStatusTracker>()

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) = sync(listOf(file))
            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (!source.isFileOpen(file)) release(file)
            }
        })
        // Start, re-scope and exit all reload the service: re-point every open file's marks at the new base.
        ReviewService.of(project).onChanged(this) { sync(FileEditorManager.getInstance(project).openFiles.toList()) }
    }

    private fun sync(files: List<VirtualFile>) {
        val service = ReviewService.of(project)
        val base = service.meta?.range?.from
        val git = service.git
        if (base == null || git == null) return trackers.keys.toList().forEach(::release)
        for (file in files) {
            val change = service.changeFor(file) ?: run { release(file); null } ?: continue
            ApplicationManager.getApplication().executeOnPooledThread {
                val baseText = git.show(base, change.oldPath ?: change.path) // "" for a file the PR added
                ApplicationManager.getApplication().invokeLater({
                    if (service.meta?.range?.from != base || !FileEditorManager.getInstance(project).isFileOpen(file)) return@invokeLater
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@invokeLater
                    trackers.getOrPut(file) { SimpleLocalLineStatusTracker.createTracker(project, document, file) }.setBaseRevision(baseText)
                }, project.disposed)
            }
        }
    }

    /** 0-based lines the review added or modified in [file], exactly as its gutter marks show them (deletions have none). */
    fun changedLines(file: VirtualFile): Set<Int> = trackers[file]?.getRanges().orEmpty().flatMap { it.line1 until it.line2 }.toSet()

    private fun release(file: VirtualFile) {
        trackers.remove(file)?.release()
    }

    override fun dispose() {
        trackers.values.forEach { it.release() }
        trackers.clear()
    }

    companion object {
        fun of(project: Project): ReviewGutter = project.service()
    }
}

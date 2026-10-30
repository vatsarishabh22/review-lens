package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.ui.EditorNotifications
import javax.swing.Timer

/**
 * While a review is in progress, refuses every write to the reviewed checkout (typing, paste, refactorings: measured,
 * no dialog); the platform shows [getReadOnlyMessage] at the caret and the review bar flashes. No `isPotentiallyWritable`:
 * answering false there makes the platform refuse before [requestWriting] runs, so the bar would never hear of the attempt.
 */
class ReadOnlyGuard(private val project: Project) : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> =
        files.filter(::isGuarded).also { if (it.isNotEmpty()) ReadOnlyFlash.of(project).start() }

    override fun getReadOnlyMessage(): String = "Read-only while reviewing. Exit review to edit."

    private fun isGuarded(file: VirtualFile): Boolean {
        val service = ReviewService.of(project)
        return service.isActive && service.relativePath(file) != null
    }
}

/** True for a moment after a blocked edit: the review bar says "Read-only while reviewing" (step 4, option A). */
@Service(Service.Level.PROJECT)
class ReadOnlyFlash(private val project: Project) : Disposable {
    @Volatile var on = false; private set
    private val end = Timer(2200) { on = false; EditorNotifications.getInstance(project).updateAllNotifications() }.apply { isRepeats = false }

    /** Called when an edit was refused; extends the flash while the reviewer keeps typing. */
    fun start() = ApplicationManager.getApplication().invokeLater({
        on = true
        end.restart()
        EditorNotifications.getInstance(project).updateAllNotifications()
    }, project.disposed)

    override fun dispose() = end.stop()

    companion object {
        fun of(project: Project): ReadOnlyFlash = project.service()
    }
}

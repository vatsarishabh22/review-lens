package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider

/**
 * While a review is in progress, refuses every write to the reviewed checkout (typing, paste, refactorings: measured,
 * no dialog); the platform shows [getReadOnlyMessage] at the caret and the review bar flashes. No `isPotentiallyWritable`:
 * answering false there makes the platform refuse before [requestWriting] runs, so the bar would never hear of the attempt.
 */
class ReadOnlyGuard(private val project: Project) : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> =
        files.filter(::isGuarded).also { if (it.isNotEmpty()) ReviewService.of(project).flashReadOnly() }

    override fun getReadOnlyMessage(): String = "Read-only while reviewing. Exit review to edit."

    private fun isGuarded(file: VirtualFile): Boolean {
        val service = ReviewService.of(project)
        return service.isActive && service.relativePath(file) != null
    }
}

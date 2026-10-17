package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider

/** While a review is in progress, refuses edits to files in the reviewed checkout. Replaced by viewer editors in step 4. */
class ReadOnlyGuard(private val project: Project) : WritingAccessProvider() {

    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> = files.filter(::isGuarded)

    override fun isPotentiallyWritable(file: VirtualFile): Boolean = !isGuarded(file)

    override fun getReadOnlyMessage(): String = "Read-only while reviewing. Exit review to edit."

    private fun isGuarded(file: VirtualFile): Boolean {
        val service = ReviewService.of(project)
        return service.isActive && service.relativePath(file) != null
    }
}

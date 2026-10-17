package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/** The strip above every editor during a review: what you are reviewing, this file's state, and the next moves. */
class ReviewBanner : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val service = ReviewService.of(project)
        val meta = service.meta ?: return null
        val change = service.changeFor(file)
        return Function { editor ->
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                val state = change?.let { "${service.badge(it).symbol} ${it.status} +${it.added ?: 0} −${it.deleted ?: 0}" }
                    ?: "not changed in this scope"
                text = "${meta.target.label} · ${meta.scopeLabel} · read-only · $state"
                if (change != null) {
                    createActionLabel("Diff ⌥D", "ReviewLens.ShowDiff")
                    val reviewed = service.badge(change) == Badge.REVIEWED
                    createActionLabel(if (reviewed) "Unmark ⌥R" else "Mark reviewed ⌥R", "ReviewLens.MarkReviewed")
                }
                createActionLabel("Next file ⌥N", "ReviewLens.NextFile")
                createActionLabel("Exit review", "ReviewLens.Exit")
            }
        }
    }
}

package io.github.vatsarishabh22.reviewlens

import com.intellij.diff.DiffManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

const val DIFF_EDITOR_ID = "review-lens-diff"

/** During a review, a changed file's tab gets a second view, Diff (scope base vs the checkout), beside the file itself. */
class ReviewDiffEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = ReviewService.of(project).changeFor(file) != null
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewDiffEditor(project, file)
    override fun getEditorTypeId() = DIFF_EDITOR_ID
    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

/** IntelliJ's own diff panel, reloaded whenever the review's scope changes. */
class ReviewDiffEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val panel = DiffManager.getInstance().createRequestPanel(project, this, null)
    private var shownRange: ScopeRange? = null

    init {
        ReviewService.of(project).onChanged(this, ::load)
        load()
    }

    private fun load() {
        val service = ReviewService.of(project)
        val range = service.meta?.range?.takeIf { it != shownRange } ?: return
        val change = service.changeFor(file) ?: return
        shownRange = range
        loadDiff(project, change, panel::setRequest)
    }

    override fun getComponent(): JComponent = panel.component
    override fun getPreferredFocusedComponent(): JComponent? = panel.preferredFocusedComponent
    override fun getName() = "Diff"
    override fun getFile() = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() = Unit
}

/** Switches a changed file's tab between the file (whatever editor the IDE uses for it) and its diff, in place. */
fun showView(project: Project, file: VirtualFile, diff: Boolean) {
    val id = if (diff) DIFF_EDITOR_ID
    else FileEditorProviderManager.getInstance().getProviderList(project, file).firstOrNull { it.editorTypeId != DIFF_EDITOR_ID }?.editorTypeId ?: return
    FileEditorManager.getInstance(project).setSelectedEditor(file, id)
}

fun isDiffShown(project: Project, file: VirtualFile) = FileEditorManager.getInstance(project).getSelectedEditor(file) is ReviewDiffEditor

/**
 * A tab gets its Diff view only when it opens, so when a review starts or ends, reopen the open files whose views
 * no longer match (a changed file gains Diff; after exit, Diff goes).
 */
fun refreshViews(project: Project) {
    val manager = FileEditorManager.getInstance(project)
    val provider = ReviewDiffEditorProvider()
    manager.openFiles.filter { file -> manager.getAllEditors(file).any { it is ReviewDiffEditor } != provider.accept(project, file) }.forEach { file ->
        manager.closeFile(file)
        OpenFileDescriptor(project, file).navigate(false)
    }
}

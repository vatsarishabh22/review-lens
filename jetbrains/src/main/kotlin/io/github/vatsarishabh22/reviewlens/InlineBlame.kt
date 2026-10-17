package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Font

/**
 * GitLens-style blame after the caret's line, only when the review scope changed that line, so PR authorship shows
 * where you are reading without annotating the whole file (built-in "Annotate with Git Blame" still works too).
 * ReviewBlame repaints on caret moves, so the text follows the cursor.
 */
class InlineBlame : EditorLinePainter() {

    private val style = TextAttributes(ReviewColors.muted, null, null, null, Font.ITALIC)

    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        if (!ReviewService.of(project).isActive) return null
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        if (editor.virtualFile != file || editor.caretModel.logicalPosition.line != lineNumber) return null
        val text = ReviewBlame.of(project).blameText(file, lineNumber) ?: return null
        return listOf(LineExtensionInfo("     $text", style))
    }
}

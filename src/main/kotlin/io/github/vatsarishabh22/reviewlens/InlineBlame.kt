package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Font

/**
 * GitLens-style blame after the caret's line, only when the review scope changed that line, so PR authorship shows
 * where you are reading without annotating the whole file (built-in "Annotate with Git Blame" still works too).
 * ReviewService repaints on caret moves, so the text follows the cursor.
 */
class InlineBlame : EditorLinePainter() {

    private val style = TextAttributes(JBColor.GRAY, null, null, null, Font.ITALIC)

    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        val service = ReviewService.of(project).takeIf { it.isActive } ?: return null
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        if (editor.virtualFile != file || editor.caretModel.logicalPosition.line != lineNumber) return null
        val text = service.blameText(file, lineNumber) ?: return null
        return listOf(LineExtensionInfo("     $text", style))
    }
}

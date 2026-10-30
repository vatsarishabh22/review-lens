package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import java.security.MessageDigest

/** Reads one version of a file for the impact map, through its language's [ImpactLanguage]. Call in a read action. */
object FunctionScanner {

    fun scan(file: PsiFile, language: ImpactLanguage): FileVersion {
        val text = file.text
        fun line(offset: Int) = StringUtil.offsetToLineNumber(text, offset) + 1
        val functions = functions(file, language).map { (name, fn) ->
            val header = language.header(fn)
            // the header ends at its last character, not at the indentation leading into the body's first line
            var end = header.endOffset
            while (end > header.startOffset && text[end - 1].isWhitespace()) end--
            val nameAt = (fn as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: fn.textRange.startOffset
            Declaration(name, line(fn.textRange.startOffset)..line(fn.textRange.endOffset), line(header.startOffset)..line(maxOf(header.startOffset, end - 1)), sha1(fn.text), line(nameAt))
        }
        return FileVersion(functions, trivialLines(file, ::line))
    }

    /** [file]'s functions in file order, each with the name its ticks are kept under. */
    fun functions(file: PsiFile, language: ImpactLanguage): List<Pair<String, PsiElement>> {
        val seen = HashMap<String, Int>()
        // overloads share a dotted name; the second and later get their place in the file, so ticks stay apart
        return PsiTreeUtil.collectElements(file, language::isFunction).map { fn ->
            language.qualifiedName(fn).let { n -> seen.merge(n, 1, Int::plus)!!.let { if (it == 1) n else "$n#$it" } } to fn
        }
    }

    /** Lines with no code on them: blank, or only comments. A change there is not a change to a function. */
    private fun trivialLines(file: PsiFile, line: (Int) -> Int): Set<Int> {
        val code = HashSet<Int>()
        PsiTreeUtil.collectElements(file) { it.firstChild == null && it !is PsiWhiteSpace && it !is PsiComment && !it.isCommentPart() && it.textLength > 0 }
            .forEach { leaf -> code += line(leaf.textRange.startOffset)..line(leaf.textRange.endOffset - 1) }
        val last = line(file.textLength)
        return (1..last).toSet() - code
    }

    /** A token inside a comment, at any depth: a Javadoc or KDoc tag's tokens sit below the comment itself. */
    private fun PsiElement.isCommentPart() = PsiTreeUtil.getParentOfType(this, PsiComment::class.java) != null

    private fun sha1(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

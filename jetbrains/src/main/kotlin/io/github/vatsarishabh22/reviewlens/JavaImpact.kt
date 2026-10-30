package io.github.vatsarishabh22.reviewlens

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/** Java methods and constructors; annotations sit in the method's modifier list, so they count as its header. */
class JavaImpact : ImpactLanguage {
    override val language get() = JavaLanguage.INSTANCE
    override val testFileName = Regex(".*Test\\.java")

    override fun isFunction(element: PsiElement) = element is PsiMethod

    override fun header(function: PsiElement): TextRange {
        val end = (function as PsiMethod).body?.textRange?.startOffset ?: function.textRange.endOffset // abstract or interface: no body
        return TextRange(function.textRange.startOffset, end)
    }

    override fun isImport(element: PsiElement) = PsiTreeUtil.getParentOfType(element, PsiImportStatementBase::class.java) != null

    override fun qualifiedName(function: PsiElement) = dottedName(function) { (it as? PsiMethod)?.name ?: (it as? PsiClass)?.name }
}

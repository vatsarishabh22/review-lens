package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Kotlin functions; the body is a block or, for `fun f() = …`, everything after the `=`. Annotations count as header. */
class KotlinImpact : ImpactLanguage {
    override val language get() = KotlinLanguage.INSTANCE
    override val testFileName = Regex(".*Test\\.kt")

    override fun isFunction(element: PsiElement) = element is KtNamedFunction

    override fun header(function: PsiElement): TextRange {
        val fn = function as KtNamedFunction
        val end = (fn.equalsToken ?: fn.bodyBlockExpression)?.textRange?.startOffset ?: fn.textRange.endOffset // abstract: no body
        return TextRange(fn.textRange.startOffset, end)
    }

    override fun isImport(element: PsiElement) = PsiTreeUtil.getParentOfType(element, KtImportDirective::class.java) != null

    override fun qualifiedName(function: PsiElement) = dottedName(function) { (it as? KtNamedFunction)?.name ?: (it as? KtClassOrObject)?.name }
}

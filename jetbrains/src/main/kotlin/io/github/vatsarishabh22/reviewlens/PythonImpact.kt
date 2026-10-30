package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PyElementTypes
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.PythonLanguage

/**
 * Python functions and methods; decorators sit inside the function's node, so they count as its header. Read by node
 * type and PsiNamedElement, not PyFunction / PyClass: those are built on Python's experimental AST interfaces, which the
 * Plugin Verifier reports.
 */
class PythonImpact : ImpactLanguage {
    override val language get() = PythonLanguage.getInstance()
    override val testFileName = Regex("test_.*\\.py|.*_test\\.py")
    override val dynamicCalls = true

    override fun isFunction(element: PsiElement) = element.node?.elementType == PyElementTypes.FUNCTION_DECLARATION

    override fun header(function: PsiElement): TextRange {
        val body = function.node.findChildByType(PyElementTypes.STATEMENT_LIST)?.startOffset ?: function.textRange.endOffset
        return TextRange(function.textRange.startOffset, body)
    }

    override fun isImport(element: PsiElement) = PsiTreeUtil.findFirstParent(element) {
        it.node?.elementType.let { t -> t == PyElementTypes.IMPORT_STATEMENT || t == PyElementTypes.FROM_IMPORT_STATEMENT }
    } != null

    /**
     * Another file reaches a function only through an import, so a bare name counts when its file has a broken
     * `from module import name` line for it whose module is there, or is [removedFrom] itself (a deleted file); an
     * uninstalled library is not a removal. The call itself resolves to nothing either way. `x.name()` on a value of unknown type never resolves, so a qualified name
     * counts only on `self` or `cls`.
     */
    override fun isDangling(reference: PsiReference, removedFrom: String): Boolean {
        val element = reference.element
        if (targets(reference).isNotEmpty()) return false
        val qualifier = element.node.findChildByType(PyTokenTypes.DOT)?.let { element.node.firstChildNode.text }
        if (qualifier != null) return qualifier == "self" || qualifier == "cls"
        if (element.parent?.node?.elementType == PyElementTypes.IMPORT_ELEMENT) return moduleKnown(element.parent, removedFrom)
        val name = reference.rangeInElement.substring(element.text)
        return PsiTreeUtil.collectElements(element.containingFile) { it.node?.elementType == PyElementTypes.IMPORT_ELEMENT }.any { import ->
            import.node.findChildByType(PyElementTypes.REFERENCE_EXPRESSION)?.psi?.takeIf { it.text == name }?.references.orEmpty().any { isDangling(it, removedFrom) }
        }
    }

    /**
     * The module a `from module import name` line reads from resolves, or is [removedFrom] by its dotted path
     * (`app/fmt.py` is `app.fmt`, under any source root); plain and relative imports say no.
     */
    private fun moduleKnown(import: PsiElement, removedFrom: String): Boolean {
        val statement = import.parent?.takeIf { it.node.elementType == PyElementTypes.FROM_IMPORT_STATEMENT } ?: return false
        val module = statement.node.findChildByType(PyElementTypes.REFERENCE_EXPRESSION)?.psi ?: return false
        // ponytail: matched by suffix, not by source root: an uninstalled library named like the deleted file's tail, importing
        // the same name, would count; resolve the source root of removedFrom's directory if that ever shows up
        val dotted = removedFrom.removeSuffix(".py").removeSuffix("/__init__").replace('/', '.')
        return dotted == module.text || dotted.endsWith(".${module.text}") || module.references.any { targets(it).isNotEmpty() }
    }

    override fun qualifiedName(function: PsiElement) = dottedName(function) {
        val type = it.node?.elementType
        if (type == PyElementTypes.FUNCTION_DECLARATION || type == PyElementTypes.CLASS_DECLARATION) (it as? PsiNamedElement)?.name else null
    }
}

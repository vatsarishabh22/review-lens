package io.github.vatsarishabh22.reviewlens

import com.intellij.lang.Language
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference

/**
 * What the impact map needs from one language (spec/impact.md, "Per editor"): which declarations are functions, where a
 * function's header ends and its body begins, the name its ticks are kept under, and its test files' names. One
 * implementation per language, each registered by the optional dependency on that language's plugin (plugin.xml).
 */
interface ImpactLanguage {
    val language: Language

    /** True where the IDE can miss calls made dynamically (Python, JavaScript, Ruby, PHP): the peek says so once per review. */
    val dynamicCalls: Boolean get() = false

    /** The test files' names by convention, for files outside a test source root. */
    val testFileName: Regex

    /** True when [element] declares a function or method; classes, variables and lambdas are not mapped. */
    fun isFunction(element: PsiElement): Boolean

    /**
     * The header of [function]: decorators or annotations, name, parameters and return type, ending where the body
     * begins (the whole declaration when it has no body). A changed line here means "inputs changed".
     */
    fun header(function: PsiElement): TextRange

    /** [function]'s name inside its file, outer declarations first: `Class.method`, `outer.inner`. Ticks are kept by it. */
    fun qualifiedName(function: PsiElement): String

    /** True when [element] sits in an import line: naming a function there is not using it, so it is no place. */
    fun isImport(element: PsiElement): Boolean

    /**
     * True when [reference] resolves to no declaration at all, so it can be a use of a removed function declared in
     * [removedFrom] (its repository path at range.from). A language where a call on an untyped value never resolves says
     * no for those: showing a wrong place is worse than missing one.
     */
    fun isDangling(reference: PsiReference, removedFrom: String): Boolean = targets(reference).isEmpty()

    companion object {
        val EP = ExtensionPointName<ImpactLanguage>("io.github.vatsarishabh22.reviewlens.impactLanguage")

        /** The implementation for [language] (or a dialect of it), or null: impact isn't available for it in this IDE. */
        fun of(language: Language): ImpactLanguage? = EP.extensionList.firstOrNull { language.isKindOf(it.language) }
    }
}

/** Every declaration [reference] resolves to; a reference with several candidates names them all. */
fun targets(reference: PsiReference): List<PsiElement> =
    (reference as? PsiPolyVariantReference)?.multiResolve(false)?.mapNotNull { it.element } ?: listOfNotNull(reference.resolve())

/** The names of the declarations [element] sits in, outermost first, then its own: the dotted part of a tick key. */
fun dottedName(element: PsiElement, name: (PsiElement) -> String?): String =
    generateSequence(element) { it.parent }.mapNotNull(name).toList().asReversed().joinToString(".")

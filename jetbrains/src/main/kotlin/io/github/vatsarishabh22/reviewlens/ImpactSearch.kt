package io.github.vatsarishabh22.reviewlens

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A place outside the PR that uses a changed function (spec/impact.md, "Which places are shown"). [key] is the tick key
 * of the declaration holding the use (`path#name`, `path#<top level>` outside any); [line] is 1-based, [lines] are the
 * use's line and the one after, and [columns] is where the use sits in the first.
 */
data class Place(val key: String, val path: String, val line: Int, val lines: List<String>, val columns: IntRange)

/** One group's places: listed for ticking, or more than [ImpactSearch.WIDE], counted only. */
sealed interface PlaceGroup {
    data class Listed(val places: List<Place>) : PlaceGroup
    data class Wide(val count: Int) : PlaceGroup
}

/** What uses a changed function: not known yet, its places in code and in tests, or the search ran out of time. */
sealed interface Uses {
    data object Searching : Uses
    data class Found(val code: PlaceGroup, val tests: PlaceGroup) : Uses
    data object TooSlow : Uses
}

/** A function the scope changed, under its tick key (`path#name`), with what uses it outside the PR. */
data class Impact(val key: String, val change: FunctionChange, val uses: Uses) {
    /** Nothing outside the PR uses it: no chip (spec/impact.md, "No places left → no chip"). */
    val silent get() = uses == Uses.Found(PlaceGroup.Listed(emptyList()), PlaceGroup.Listed(emptyList()))
}

/**
 * Finds what uses the functions one file's scope changed. Call in a smart-mode read action. [pathOf] gives a file's
 * repository path (null outside the repository); a place in [inPr], the scope's changed files, is dropped (by file, as
 * decided). Each function's search stops after [budget].
 */
class ImpactSearch(
    private val project: Project,
    private val pathOf: (VirtualFile) -> String?,
    private val inPr: Set<String>,
    private val budget: Duration = 3.seconds,
) {
    private val index = ProjectFileIndex.getInstance(project)
    private val scope = GlobalSearchScope.projectScope(project)
    private val names = HashMap<PsiFile, Map<PsiElement, String>>()

    /**
     * What uses each of [pending], the file's changed functions from [detect]; [after] is the file now (null once
     * deleted). Needs the indexes: call in smart mode. A search that throws leaves that function out.
     */
    fun impacts(language: ImpactLanguage, after: PsiFile?, pending: List<Impact>): List<Impact> {
        val now = after?.let { FunctionScanner.functions(it, language) }.orEmpty().toMap()
        return pending.mapNotNull { impact ->
            try {
                val change = impact.change
                impact.copy(uses = if (change.kind == ChangeKind.REMOVED) stillNamed(change.name, impact.key.substringBefore('#')) else usesOf(now.getValue(change.name)))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                thisLogger().info("Impact search failed for ${impact.key}", e)
                null
            }
        }
    }

    private fun usesOf(function: PsiElement): Uses = collect { add ->
        ReferencesSearch.search(function, scope).forEach(Processor { add(it.element, it.rangeInElement) })
    }

    /** A removed function's uses: references carrying its name that resolve to nothing now, as its language judges them. */
    private fun stillNamed(name: String, removedFrom: String): Uses {
        val word = name.substringBefore('#').substringAfterLast('.')
        return collect { add ->
            PsiSearchHelper.getInstance(project).processElementsWithWord({ element, offset ->
                val dangling = element.references.firstOrNull { ref ->
                    ref.rangeInElement.containsOffset(offset) && ref.rangeInElement.substring(element.text) == word &&
                        ImpactLanguage.of(element.language)?.isDangling(ref, removedFrom) == true
                }
                dangling == null || add(element, dangling.rangeInElement)
            }, scope, word, UsageSearchContext.IN_CODE, true)
        }
    }

    /** Runs [search], handing each use to `add` (false once time is up), and groups the places kept. */
    private fun collect(search: ((PsiElement, TextRange) -> Boolean) -> Boolean): Uses {
        // ponytail: time is checked per use found, so a search finding nothing runs to its end; a ProgressIndicator cap if that is slow
        val deadline = TimeSource.Monotonic.markNow() + budget
        val places = HashMap<String, Pair<Place, Boolean>>() // one per declaration: its first use
        val finished = search { element, range ->
            placeOf(element, range)?.let { found -> places.merge(found.first.key, found) { a, b -> if (b.first.line < a.first.line) b else a } }
            !deadline.hasPassedNow()
        }
        if (!finished) return Uses.TooSlow
        val (tests, code) = places.values.partition { it.second }
        return Uses.Found(group(code.map { it.first }), group(tests.map { it.first }))
    }

    private fun group(places: List<Place>) =
        if (places.size > WIDE) PlaceGroup.Wide(places.size) else PlaceGroup.Listed(places.sortedWith(compareBy({ it.path }, { it.line })))

    /** The place holding the use at [range] in [element], and whether it is in a test file; null when it is dropped. */
    private fun placeOf(element: PsiElement, range: TextRange): Pair<Place, Boolean>? {
        val file = element.containingFile ?: return null
        if (InjectedLanguageManager.getInstance(project).isInjectedFragment(file)) return null
        val vf = file.virtualFile ?: return null
        val path = pathOf(vf) ?: return null
        if (path in inPr || isVendored(vf, path) || ImpactLanguage.of(file.language)?.isImport(element) == true) return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val start = element.textRange.startOffset + range.startOffset
        val line = document.getLineNumber(start)
        val lineStart = document.getLineStartOffset(line)
        val lines = (line..minOf(line + 1, document.lineCount - 1)).map { document.getText(TextRange(document.getLineStartOffset(it), document.getLineEndOffset(it))) }
        val end = minOf(element.textRange.startOffset + range.endOffset, document.getLineEndOffset(line))
        val place = Place("$path#${declarationOf(element, file)}", path, line + 1, lines, (start - lineStart) until (end - lineStart))
        return place to (index.isInTestSourceContent(vf) || ImpactLanguage.EP.extensionList.any { it.testFileName.matches(vf.name) })
    }

    /** The name of the function holding [element], as its ticks are kept, or `<top level>` outside any. */
    private fun declarationOf(element: PsiElement, file: PsiFile): String {
        val language = ImpactLanguage.of(file.language) ?: return TOP_LEVEL
        val owner = PsiTreeUtil.findFirstParent(element, false, language::isFunction) ?: return TOP_LEVEL
        return names.getOrPut(file) { FunctionScanner.functions(file, language).associate { (name, fn) -> fn to name } }[owner] ?: TOP_LEVEL
    }

    private fun isVendored(file: VirtualFile, path: String) =
        GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project) || path.split('/').any { it == "node_modules" || it == "vendor" }

    companion object {
        /**
         * The functions [lines] changed in the file at [path] ([oldPath] at range.from), not searched yet: [before] is the
         * file at range.from, [after] the file now (null once deleted). Parsing only, so it runs while the IDE indexes.
         */
        fun detect(path: String, oldPath: String, language: ImpactLanguage, before: PsiFile, after: PsiFile?, lines: LineChanges): List<Impact> {
            val versions = listOf(before, after).map { it?.let { f -> FunctionScanner.scan(f, language) } ?: FileVersion(emptyList(), emptySet()) }
            // a removed function keeps its key from range.from (spec/impact.md, "The state")
            return changedFunctions(versions[0], versions[1], lines)
                .map { Impact("${if (it.kind == ChangeKind.REMOVED) oldPath else path}#${it.name}", it, Uses.Searching) }
        }

        /** A group with more places than this is summarised, not listed. */
        const val WIDE = 5
        const val TOP_LEVEL = "<top level>"
    }
}

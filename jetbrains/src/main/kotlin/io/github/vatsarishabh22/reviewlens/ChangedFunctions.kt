package io.github.vatsarishabh22.reviewlens

/**
 * A function as one version of a file declares it. [lines] and [header] are 1-based and inclusive, [nameLine] the line
 * its name is on (below any decorators); [hash] is a hash of its text, so ticks on it can be dropped when it changes
 * again (spec/impact.md, "Ticks").
 */
data class Declaration(val name: String, val lines: IntRange, val header: IntRange, val hash: String, val nameLine: Int = lines.first)

/** How a function changed in the scope, worded as the chip says it. */
enum class ChangeKind(val label: String) { INPUTS("inputs changed"), LOGIC("logic changed"), REMOVED("removed") }

/** A function the scope changed: [declaration] is at range.to, or at range.from for a removed one. */
data class FunctionChange(val name: String, val kind: ChangeKind, val declaration: Declaration)

/** One version of a file as the impact map reads it: its functions, and the lines that hold only comments or blanks. */
data class FileVersion(val functions: List<Declaration>, val trivialLines: Set<Int>)

/**
 * The functions the scope changed in one file (spec/impact.md, "What counts as a change"). A changed line counts for
 * the innermost function holding it, on either side: written lines against [after], removed lines against [before]. A
 * line in a header on either side makes it "inputs changed", else "logic changed". Lines holding only comments or
 * blanks don't count. A function only [after] has is new (not mapped); one only [before] has is removed.
 */
fun changedFunctions(before: FileVersion, after: FileVersion, changes: LineChanges): List<FunctionChange> {
    fun hits(version: FileVersion, lines: Set<Int>): Map<String, Boolean> { // name -> a header line was hit
        val result = HashMap<String, Boolean>()
        for (line in lines - version.trivialLines) {
            val owner = version.functions.filter { line in it.lines }.minByOrNull { it.lines.last - it.lines.first } ?: continue
            result[owner.name] = (result[owner.name] ?: false) || line in owner.header
        }
        return result
    }
    val old = before.functions.associateBy { it.name }
    val written = hits(after, changes.added)
    val removed = hits(before, changes.deleted)
    val changed = after.functions.filter { it.name in old && (it.name in written || it.name in removed) }.map {
        FunctionChange(it.name, if (written[it.name] == true || removed[it.name] == true) ChangeKind.INPUTS else ChangeKind.LOGIC, it)
    }
    val names = after.functions.map { it.name }.toSet()
    return changed + before.functions.filter { it.name !in names }.map { FunctionChange(it.name, ChangeKind.REMOVED, it) }
}

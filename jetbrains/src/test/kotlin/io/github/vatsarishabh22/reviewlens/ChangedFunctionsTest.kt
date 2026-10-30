package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The impact map's change rules (spec/impact.md, "What counts as a change") on hand-made versions of one file. */
class ChangedFunctionsTest {

    // apply: header 3, body 4..6 · run: header 8, body 9..12, holding inner: header 10, body 11
    private val apply = Declaration("apply", 3..6, 3..3, "a")
    private val run = Declaration("run", 8..12, 8..8, "r")
    private val inner = Declaration("run.inner", 10..11, 10..10, "i")
    private val file = FileVersion(listOf(apply, run, inner), trivialLines = setOf(1, 2, 5, 7))

    private fun change(added: Set<Int> = emptySet(), deleted: Set<Int> = emptySet(), before: FileVersion = file, after: FileVersion = file) =
        changedFunctions(before, after, LineChanges(null, added, deleted)).associate { it.name to it.kind }

    @Test
    fun `a body line is logic changed, a header line is inputs changed, both is inputs changed`() {
        assertEquals(mapOf("apply" to ChangeKind.LOGIC), change(added = setOf(4)))
        assertEquals(mapOf("apply" to ChangeKind.INPUTS), change(added = setOf(3)))
        assertEquals(mapOf("apply" to ChangeKind.INPUTS), change(added = setOf(3, 4)))
    }

    @Test
    fun `a removed line counts against the old version, so a deletion inside a body is logic changed`() =
        assertEquals(mapOf("apply" to ChangeKind.LOGIC), change(deleted = setOf(6)))

    @Test
    fun `lines holding only comments or blanks are not changes`() =
        assertEquals(emptyMap<String, ChangeKind>(), change(added = setOf(5, 7), deleted = setOf(5)))

    @Test
    fun `a change inside a nested function counts for the inner one only`() =
        assertEquals(mapOf("run.inner" to ChangeKind.LOGIC), change(added = setOf(11)))

    @Test
    fun `a new function is not mapped, a removed one is`() {
        val before = FileVersion(listOf(apply, Declaration("legacy", 8..9, 8..8, "l")), emptySet())
        val after = FileVersion(listOf(apply, Declaration("fresh", 8..9, 8..8, "f")), emptySet())
        val found = changedFunctions(before, after, LineChanges(null, setOf(8, 9), setOf(8, 9)))
        assertEquals(listOf("legacy" to ChangeKind.REMOVED), found.map { it.name to it.kind })
        assertEquals(8..9, found.single().declaration.lines) // a removed function is placed by its old declaration
    }

    @Test
    fun `lines outside every function change nothing`() =
        assertTrue(change(added = setOf(13, 14)).isEmpty())
}

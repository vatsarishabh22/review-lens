package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The chip's words, one per row of spec/impact.md's "Words" table. */
class ImpactChipTest {
    private fun place(key: String) = Place("app/$key.py#$key", "app/$key.py", 1, listOf("f()"), 0..0)
    private fun listed(vararg keys: String) = PlaceGroup.Listed(keys.map(::place))
    private fun impact(kind: ChangeKind, uses: Uses, name: String = "calculate_refund") =
        Impact("app/payments.py#$name", FunctionChange(name, kind, Declaration(name, 1..2, 1..1, "h")), uses)

    @Test
    fun `places in use, ticked one by one, then all checked`() {
        val uses = Uses.Found(listed("a", "b"), listed("t"))
        assertEquals(Chip(listOf(false, false, false), "3 places use this · logic changed", foldable = true), chipOf(impact(ChangeKind.LOGIC, uses), emptySet()))
        assertEquals(listOf(true, false, false), chipOf(impact(ChangeKind.INPUTS, uses), setOf("app/a.py#a"))!!.dots)
        assertEquals("3 places use this · inputs changed", chipOf(impact(ChangeKind.INPUTS, uses), emptySet())!!.text)
        assertEquals(Chip(listOf(true, true, true), "All 3 places checked", done = true, foldable = true),
            chipOf(impact(ChangeKind.LOGIC, uses), setOf("app/a.py#a", "app/b.py#b", "app/t.py#t")))
        assertEquals("1 place uses this · logic changed", chipOf(impact(ChangeKind.LOGIC, Uses.Found(listed("a"), listed())), emptySet())!!.text)
    }

    @Test
    fun `a widely used group counts in the chip, not in the dots`() {
        val chip = chipOf(impact(ChangeKind.LOGIC, Uses.Found(listed("a", "b", "c"), PlaceGroup.Wide(8))), emptySet())!!
        assertEquals("11 places use this · logic changed", chip.text)
        assertEquals(3, chip.dots.size)
    }

    @Test
    fun `a removed function is named, still in use or checked`() {
        val uses = Uses.Found(listed("a", "b"), listed())
        assertEquals("legacy removed · 2 places still use it", chipOf(impact(ChangeKind.REMOVED, uses, "legacy"), emptySet())!!.text)
        assertEquals("legacy removed · all 2 places checked", chipOf(impact(ChangeKind.REMOVED, uses, "legacy"), setOf("app/a.py#a", "app/b.py#b"))!!.text)
        assertEquals("Refunds.legacy removed · 1 place still uses it", chipOf(impact(ChangeKind.REMOVED, Uses.Found(listed("a"), listed()), "Refunds.legacy#2"), emptySet())!!.text)
    }

    @Test
    fun `searching, too slow, and nothing to check`() {
        assertEquals(Chip(emptyList(), "Finding places…", busy = true), chipOf(impact(ChangeKind.LOGIC, Uses.Searching), emptySet()))
        assertEquals(Chip(emptyList(), "Many places · ", link = "Open Find Usages"), chipOf(impact(ChangeKind.LOGIC, Uses.TooSlow), emptySet()))
        assertEquals(Chip(emptyList(), "legacy removed · many places"), chipOf(impact(ChangeKind.REMOVED, Uses.TooSlow, "legacy"), emptySet()))
        assertNull(chipOf(impact(ChangeKind.LOGIC, Uses.Found(listed(), listed())), emptySet()))
    }
}

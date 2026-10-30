package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The impact map's ticks in `<key>.json`: kept per function while its text is unchanged, pruned at Exit. */
class ImpactStateTest {

    private val meta = ReviewMeta(ReviewTarget("pr-7", "#7", 7, "feat", "origin/main"), ScopeRange("f", "t"), "All")

    @Test
    fun `ticks are kept by function and place, and survive while the function is unchanged`() {
        val ticked = meta.tick("a.py#apply", "h1", "b.py#call", true).tick("a.py#apply", "h1", "tests/test_a.py#test_it", true)
        assertEquals(setOf("b.py#call", "tests/test_a.py#test_it"), ticked.checkedPlaces("a.py#apply", "h1"))
        assertEquals(setOf("tests/test_a.py#test_it"), ticked.tick("a.py#apply", "h1", "b.py#call", false).checkedPlaces("a.py#apply", "h1"))
    }

    @Test
    fun `a function changed again starts over, and a tick on the new version drops the old ones`() {
        val ticked = meta.tick("a.py#apply", "h1", "b.py#call", true)
        assertEquals(emptySet<String>(), ticked.checkedPlaces("a.py#apply", "h2"))
        assertEquals(CheckedFunction("h2", listOf("c.py#other")), ticked.tick("a.py#apply", "h2", "c.py#other", true).checked!!["a.py#apply"])
    }

    @Test
    fun `exit keeps the ticks of the scope's functions and drops the rest, leaving no empty map`() {
        val ticked = meta.tick("a.py#apply", "h1", "b.py#call", true).tick("a.py#gone", "h9", "b.py#x", true)
        assertEquals(setOf("a.py#apply"), ticked.keepChecked(setOf("a.py#apply")).checked!!.keys)
        assertNull(ticked.keepChecked(emptySet()).checked)
    }
}

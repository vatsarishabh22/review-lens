package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Two reloads, or two thread reads, land in either order: the one asked last wins. */
class NewestTest {
    @Test
    fun `the answer asked last wins whichever lands first`() {
        val newest = Newest()
        var shown = ""
        val startup = newest.ask()
        val start = newest.ask()
        newest.keep(start) { shown = "review" }
        newest.keep(startup) { shown = "no review" } // the startup's reload, slow, lands last
        assertEquals("review", shown)
    }

    @Test
    fun `answers landing in the order asked are each kept`() {
        val newest = Newest()
        val kept = mutableListOf<Int>()
        val first = newest.ask()
        val second = newest.ask()
        newest.keep(first) { kept += 1 }
        newest.keep(second) { kept += 2 }
        assertEquals(listOf(1, 2), kept)
    }
}

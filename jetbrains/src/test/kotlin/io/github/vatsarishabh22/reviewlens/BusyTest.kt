package io.github.vatsarishabh22.reviewlens

import io.github.vatsarishabh22.reviewlens.ReviewService.Ending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one busy state: a step claims it, and only that claim frees it or hands it on. */
class BusyTest {
    private var redraws = 0
    private val busy = Busy { redraws++ }

    @Test
    fun `a second claim is refused, hearing what holds it, and free again once released`() {
        val exit = busy.claim(Ending.EXITING)!!
        var heard: Ending? = null
        assertNull(busy.claim(Ending.SWITCHING) { heard = it })
        assertEquals(Ending.EXITING, heard)
        busy.release(exit)
        assertNull(busy.ending)
        assertEquals(Ending.SWITCHING, busy.claim(Ending.SWITCHING)?.let { busy.ending })
    }

    @Test
    fun `an older step's end never clears a newer one's`() {
        val exit = busy.claim(Ending.EXITING)!!
        busy.release(exit)
        busy.claim(Ending.SWITCHING)!!
        busy.release(exit) // the Exit's completion, landing after a Start began
        busy.hand(exit, Ending.POSTING)
        assertEquals(Ending.SWITCHING, busy.ending)
        assertNull(busy.claim(Ending.EXITING))
    }

    @Test
    fun `Finish hands its posting on to its exit, still holding the state`() {
        val finish = busy.claim(Ending.POSTING)!!
        busy.hand(finish, Ending.EXITING)
        assertEquals(Ending.EXITING, busy.ending)
        assertNull(busy.claim(Ending.SWITCHING))
        busy.release(finish)
        assertNull(busy.ending)
    }

    @Test
    fun `every change redraws, a refused or stale one does not`() {
        val exit = busy.claim(Ending.EXITING)!!
        busy.claim(Ending.SWITCHING)
        busy.release(exit)
        busy.release(exit)
        assertEquals(2, redraws)
    }
}

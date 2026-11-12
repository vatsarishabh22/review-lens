package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** One PR listing serves everyone waiting on it (the panel's refresh, a Finish balloon), each answered once. */
class WaitersTest {
    @Test
    fun `only the first starts the load, settle answers each once, and the next add starts a new one`() {
        val waiters = Waiters()
        val heard = mutableListOf<String>()
        assertEquals(true, waiters.add { heard += "a $it" })
        assertEquals(false, waiters.add { heard += "b $it" })
        waiters.settle(false)
        waiters.settle(true) // a second answer reaches no one
        assertEquals(listOf("a false", "b false"), heard)
        assertEquals(true, waiters.add { heard += "c $it" })
    }

    @Test
    fun `a waiter added while answers go out waits for the next load`() {
        val waiters = Waiters()
        var started = false
        waiters.add { started = waiters.add {} }
        waiters.settle(true)
        assertEquals(true, started)
    }
}

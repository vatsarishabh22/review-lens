package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The impact map's files: a run overtaken by a re-scope writes nothing into the next run's map, and can't mark it complete. */
class RunMapTest {
    @Test
    fun `a run overtaken by a newer one writes nothing, and only the newest run's writes land`() {
        val files = RunMap<String, String>()
        val old = files.start()
        files.put(old, "a.py", "old run")
        val now = files.start() // a re-scope
        assertNull("starting a run clears the map", files["a.py"])
        files.put(old, "b.py", "old run, late")
        files.put(now, "a.py", "new run")
        assertNull(files["b.py"])
        assertEquals("new run", files["a.py"])
        assertFalse(files.current(old))
        assertTrue(files.current(now))
    }

    @Test
    fun `only the newest run marks the map complete, and a new run starts incomplete`() {
        val files = RunMap<String, String>()
        val old = files.start()
        val now = files.start()
        files.put(now, "a.py", "searched")
        files.done(old, complete = true)
        assertNull("an overtaken run never says the newer one is done", files.finished())
        files.done(now, complete = true)
        assertEquals(listOf("searched"), files.finished())
        files.start()
        assertNull(files.finished())
    }
}

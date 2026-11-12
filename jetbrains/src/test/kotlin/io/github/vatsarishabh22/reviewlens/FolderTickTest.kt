package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** A folder's tick (spec/README.md, Viewed): its files ticked, or all unticked when all are viewed; and the queue once the host took a batch. */
class FolderTickTest {
    private fun change(path: String) = FileChange(path, null, 'M', "$path@2", 1, 0)
    private val a = change("src/a.kt")
    private val b = change("src/b.kt")
    private val c = change("src/c.kt")

    private fun tick(reviewed: Map<String, String>, files: List<FileChange> = listOf(a, b, c)) = folderTick(files, reviewed).let { (changed, viewed) -> changed.map { it.path } to viewed }

    @Test
    fun `with none viewed, every file is ticked`() {
        assertEquals(listOf("src/a.kt", "src/b.kt", "src/c.kt") to true, tick(emptyMap()))
    }

    @Test
    fun `with some viewed, only the rest are ticked, a file changed since its tick among them`() {
        assertEquals(listOf("src/b.kt", "src/c.kt") to true, tick(mapOf("src/a.kt" to "src/a.kt@2", "src/b.kt" to "src/b.kt@1")))
    }

    @Test
    fun `with all viewed, all are unticked`() {
        assertEquals(listOf("src/a.kt", "src/b.kt", "src/c.kt") to false, tick(listOf(a, b, c).associate { it.path to it.newBlob }))
    }

    @Test
    fun `a taken batch leaves the queue, except a path flipped again while it was out, and the paths it did not carry`() {
        val queue = mapOf("src/a.kt" to true, "src/b.kt" to false, "other.kt" to true)
        assertEquals(mapOf("src/b.kt" to false, "other.kt" to true), confirmed(queue, mapOf("src/a.kt" to true, "src/b.kt" to true)))
        assertEquals(null, confirmed(mapOf("src/a.kt" to true), mapOf("src/a.kt" to true)))
        assertEquals(null, confirmed(null, mapOf("src/a.kt" to true)))
    }
}

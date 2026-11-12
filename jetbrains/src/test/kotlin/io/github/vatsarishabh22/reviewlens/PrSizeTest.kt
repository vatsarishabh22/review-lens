package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The size a PR's detail shows: a stacked PR never shows the host's whole-PR numbers, which include its parent's changes. */
class PrSizeTest {
    // the host counts the stacked PR against main: its parent's 4 files are in these numbers
    private val pr = PullRequest(9, "t", "vat-lines", "s9", "main", Person("a", "a"), additions = 47, deletions = 3, changedFiles = 6)
    private val own = listOf(FileChange("invoices/receipt.py", null, 'A', "b1", 13, 0), FileChange("tests/test_receipt.py", null, 'A', "b2", 8, 0))

    @Test
    fun `a stacked PR shows the list's own count while it loads, without the host's lines`() =
        assertEquals(PrSize(2, null, null), prSize(pr, stacked = true, fetched = false, listFiles = 2, own = null))

    @Test
    fun `a stacked PR whose count the list doesn't have yet shows nothing rather than the host's`() =
        assertNull(prSize(pr, stacked = true, fetched = false, listFiles = null, own = null))

    @Test
    fun `once fetched, a stacked PR shows its own files and lines`() =
        assertEquals(PrSize(2, 21, 0), prSize(pr, stacked = true, fetched = true, listFiles = 2, own = own))

    @Test
    fun `a stacked PR fetched with no parent left its stack, so the host's numbers are its own`() =
        assertEquals(PrSize(6, 47, 3), prSize(pr, stacked = true, fetched = true, listFiles = 2, own = null))

    @Test
    fun `a PR that isn't stacked shows the host's numbers`() =
        assertEquals(PrSize(6, 47, 3), prSize(pr, stacked = false, fetched = false, listFiles = 6, own = null))
}

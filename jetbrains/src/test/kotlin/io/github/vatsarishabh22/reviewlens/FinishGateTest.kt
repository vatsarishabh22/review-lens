package io.github.vatsarishabh22.reviewlens

import io.github.vatsarishabh22.reviewlens.github.GitHubSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finish review's Submit: a choice's own lock gates it, even while that choice stays picked after the lock closed. */
class FinishGateTest {
    private val nothing = Outbox(emptyList(), emptyList())
    private val ownPr = GitHubSlot("PR_1", "h", null, ownPullRequest = true)
    private val locked = "View every file first: 3 of 4 viewed"

    @Test
    fun `Mark as reviewed is held by its lock, picked or not`() {
        assertEquals(locked, choiceLock(null, locked, null))
        assertEquals(locked, submitBlocker(null, locked, null, checked = true, summary = "", outbox = nothing, scopeEnd = "h", host = "GitHub"))
        assertNull(submitBlocker(null, null, null, checked = null, summary = "", outbox = nothing, scopeEnd = "h", host = "GitHub"))
    }

    @Test
    fun `a verdict is held by its own lock first, then the host's re-read, then its refusal`() {
        val denied = ownPr.denied(Verdict.APPROVE)!!
        assertEquals(denied, choiceLock(Verdict.APPROVE, null, ownPr))
        assertEquals(denied, submitBlocker(Verdict.APPROVE, null, ownPr, checked = null, summary = "ok", outbox = nothing, scopeEnd = "h", host = "GitHub"))
        assertEquals("Waiting for GitHub's latest comments", submitBlocker(Verdict.COMMENT, locked, ownPr, checked = null, summary = "ok", outbox = nothing, scopeEnd = "h", host = "GitHub"))
        assertNull("Mark's lock never holds a verdict", submitBlocker(Verdict.COMMENT, locked, ownPr, checked = true, summary = "ok", outbox = nothing, scopeEnd = "h", host = "GitHub"))
    }
}

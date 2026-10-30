package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drafts, the outbox Finish review posts, and the words around them; what GitHub is sent is GitHubWireTest's. */
class ReviewCommentsTest {

    @Test
    fun `the PR list says reviewed only when every file is viewed, with the last posted verdict`() {
        assertEquals("Resume · 3 of 4 viewed", ReviewProgress(3, 4, Verdict.APPROVE).label) // new commits re-open it
        assertEquals("✓ Reviewed · 4 of 4", ReviewProgress(4, 4).label) // marked reviewed, never posted
        assertEquals("✓ Reviewed · approved", ReviewProgress(4, 4, Verdict.APPROVE).label)
        assertEquals("✓ Reviewed · commented", ReviewProgress(4, 4, Verdict.COMMENT).label)
        assertEquals("✓ Reviewed · changes requested", ReviewProgress(4, 4, Verdict.REQUEST_CHANGES).label)
        assertEquals("Resume · 0 of 0 viewed", ReviewProgress(0, 0).label) // an empty scope is never "reviewed"
        val moved = ReviewProgress(4, 4, Verdict.APPROVE, newCommits = true)
        assertEquals("🔄 New commits since your review", moved.label)
        assertFalse("a finished review whose PR moved on is not done", moved.done)
        assertEquals("Review new commits", moved.startLabel(sinceOffered = true))
        assertEquals("a force-push rewrote the reviewed commit: nothing to offer on its own", "Review again", moved.startLabel(sinceOffered = false))
        assertEquals("Review again", ReviewProgress(4, 4).startLabel(sinceOffered = false))
        assertEquals("Resume review", ReviewProgress(3, 4).startLabel(sinceOffered = false))
    }

    @Test
    fun `the PR moved on when GitHub's head is not the head this review last saw`() {
        val meta = ReviewMeta(ReviewTarget("pr-7", "#7", 7, "h", "main"), ScopeRange("base", "7ea9ca9"), "All", seenHead = "7ea9ca9")
        assertFalse(meta.headMoved("7ea9ca9"))
        assertTrue("a teammate pushed 031dfb9 (the sandbox replay)", meta.headMoved("031dfb9"))
        assertFalse("no gh, or a branch: unknown is never 'moved'", meta.headMoved(null))
        assertFalse(meta.headMoved(""))
        assertTrue("an older save without seenHead falls back to the scope's end", meta.copy(seenHead = null).headMoved("031dfb9"))
        assertFalse(meta.copy(seenHead = null).headMoved("7ea9ca9"))
        val olderScope = meta.copy(range = ScopeRange("base", "89a2499")) // reviewed only the first commit, with the head at 7ea9ca9
        assertFalse("a narrower scope is not new commits: the head is what you last saw", olderScope.headMoved("7ea9ca9"))
    }

    @Test
    fun `one outbox, so what the badge counts is what the popup says and what posts`() {
        val box = Outbox(listOf(Draft("a.py", 7, "a2", "c")), listOf(Draft("a.py", 7, "a2", "r", replyTo = "T1")))
        assertEquals(2, box.size)
        assertEquals("1 comment and 1 reply", box.text)
        assertEquals(0, Outbox(emptyList(), emptyList()).size)
    }

    private fun thread(id: String, resolved: Boolean = false, by: String? = null, byMe: Boolean = false) =
        ReviewThread(id, "a.py", 7, 7, false, "abc", resolved, by, "u$id", false, listOf(ThreadComment("lee", "why?")), resolvedByMe = byMe)

    @Test
    fun `Finish review says what changed under your replies since the review loaded`() {
        val drafts = listOf(Draft("a.py", 7, "a2", "x", replyTo = "T2"), Draft("a.py", 7, "a2", "y", replyTo = "T2"), Draft("a.py", 7, "a2", "z", replyTo = "T9"))
        assertEquals(listOf(
            "lee resolved a thread you replied to since the review loaded. Your reply still posts; the thread stays resolved.",
            "1 reply is on a thread that's gone from GitHub. It won't post and stays as a draft.",
        ), replyNotes(drafts, listOf(thread("T2", resolved = true, by = "lee")), "GitHub"))
        assertEquals(emptyList<String>(), replyNotes(drafts.take(1), listOf(thread("T2")), "GitHub"))
        assertEquals("threads unknown: nothing to say", emptyList<String>(), replyNotes(drafts, null, "GitHub"))
        assertEquals("you resolved it yourself: no news", emptyList<String>(), replyNotes(drafts.take(1), listOf(thread("T2", resolved = true, by = "me", byMe = true)), "GitHub"))
    }

    @Test
    fun `counts read as GitHub's review will`() {
        assertEquals("2 comments and 1 reply", draftsText(2, 1))
        assertEquals("1 comment", draftsText(1, 0))
        assertEquals("3 replies", draftsText(0, 3))
        assertEquals("", draftsText(0, 0))
    }

    @Test
    fun `Exit names what it kept, comments and replies apart, and nothing once all are posted`() {
        val comment = Draft("a.py", 7, "a2", "c"); val reply = Draft("a.py", 7, "a2", "r", replyTo = "T1")
        assertEquals(" 1 comment and 1 reply kept as drafts for next time.", keptText(listOf(comment, reply, Draft("a.py", 8, "a2", "sent", posted = true))))
        assertEquals(" 2 replies kept as drafts for next time.", keptText(listOf(reply, reply.copy(body = "r2"))))
        assertEquals("", keptText(listOf(comment.copy(posted = true))))
    }

    @Test
    fun `several lines are one block of the diff when each is within 3 of a changed line`() {
        val changed = setOf(10, 11, 20)
        assertTrue("the changes and 3 lines around them", inOneDiffBlock(changed, 7, 14))
        assertTrue(!inOneDiffBlock(changed, 14, 17)) // 15 and 16 are 4+ lines from every change: two blocks
        assertTrue(!inOneDiffBlock(changed, 22, 24)) // beyond the last change's 3 lines
        assertTrue("changes 6 apart share their context lines: one block", inOneDiffBlock(setOf(10, 16), 7, 19))
    }

    @Test
    fun `Suggest a change writes the block the suggestion cases read back`() =
        assertEquals("```suggestion\n    a = 1\n    b = 2\n```", suggestionBlock(listOf("    a = 1", "    b = 2")))

}

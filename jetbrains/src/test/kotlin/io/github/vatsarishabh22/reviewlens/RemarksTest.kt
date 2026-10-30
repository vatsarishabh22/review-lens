package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Comments tab's rows and filters, and the Finish popup's words: decided here, only laid out by the views. */
class RemarksTest {
    private val scope = listOf(FileChange("a.py", null, 'M', "a2", 1, 0), FileChange("b.py", null, 'M', "b2", 1, 0))
    private fun thread(id: String, path: String, line: Int?, resolved: Boolean = false, mine: Boolean = false, commit: String = "end") =
        ReviewThread(id, path, line, line ?: 40, false, commit, resolved, null, "u$id", mine, listOf(ThreadComment("lee", "why $id?"), ThreadComment("me", "because")))
    private val open = thread("T1", "a.py", 9)
    private val resolved = thread("T2", "a.py", 3, resolved = true)
    private val moved = thread("T3", "b.py", 5, commit = "older")
    private val reply = Draft("a.py", 9, "a2", "my reply", replyTo = "T1")
    private val current = Draft("a.py", 6, "a2", "on this version", startLine = 4)
    private val earlier = Draft("b.py", 2, "b1", "on an earlier version")
    private val posted = Draft("a.py", 1, "a2", "sent", posted = true)
    private val stray = Draft("b.py", 7, "b2", "reply to a deleted thread", replyTo = "T9")
    private val sentReply = Draft("a.py", 9, "a2", "a reply already posted", posted = true, replyTo = "T1") // on the host now: no row of its own
    private val drafts = listOf(reply, current, earlier, posted, stray, sentReply)

    private fun rows(threads: List<ReviewThread>?) = remarks(threads, drafts, scope, "end", "GitHub")

    @Test
    fun `threads, your comments and stray replies, by path, resolved last, then line, rows with no line in that order`() {
        assertEquals(listOf(
            Remark("a.py", 6, "You", RemarkKind.PENDING, "lines 4–6", 0, "on this version", true, draft = current),
            Remark("a.py", 9, "lee", RemarkKind.OPEN, "line 9", 1, "why T1?", false, listOf(reply)),
            Remark("a.py", 3, "lee", RemarkKind.RESOLVED, "line 3", 1, "why T2?", false),
            Remark("b.py", null, "lee", RemarkKind.OPEN, "line 5", 1, "why T3?", false),
            Remark("b.py", null, "You", RemarkKind.PENDING, "line 2 of an earlier version", 0, "on an earlier version", true, draft = earlier),
            Remark("b.py", null, "You", RemarkKind.PENDING, "reply · its thread is gone from GitHub", 0, "reply to a deleted thread", true, draft = stray),
        ), rows(listOf(open, resolved, moved)))
    }

    @Test
    fun `with threads not loaded, your posted comments show as posted and every reply stands alone`() {
        val shown = rows(null)
        assertEquals(RemarkKind.POSTED, shown.single { it.body == "sent" }.kind)
        assertEquals(listOf("reply", "reply"), shown.filter { it.draft?.replyTo != null }.map { it.where })
    }

    @Test
    fun `no scope, no rows`() = assertEquals(emptyList<Remark>(), remarks(listOf(open), drafts, scope, null, "GitHub"))

    @Test
    fun `each filter keeps its own rows`() {
        val shown = rows(listOf(open, resolved, moved, thread("T4", "a.py", 2, mine = true)))
        fun kept(filter: RemarkFilter) = shown.filter(filter.keeps).map { it.body }
        assertEquals(shown.map { it.body }, kept(RemarkFilter.ALL))
        assertEquals(listOf("why T4?", "why T1?", "why T3?"), kept(RemarkFilter.OPEN))
        assertEquals("yours, and threads you replied to", listOf("why T4?", "on this version", "why T1?", "on an earlier version", "reply to a deleted thread"), kept(RemarkFilter.MINE))
        assertEquals(listOf("on this version", "why T1?", "on an earlier version", "reply to a deleted thread"), kept(RemarkFilter.PENDING))
    }

    private val one = Outbox(listOf(current), emptyList())
    private val three = Outbox(listOf(current, earlier), listOf(reply))
    private val none = Outbox(emptyList(), emptyList())

    @Test
    fun `the fine print says what goes out with the review`() {
        assertEquals("1 comment will be posted with it, as one review. 2 of 5 files viewed.", finishWords(Verdict.COMMENT, one, 0, null, 0, 2, 5, "GitHub").fineprint)
        assertEquals("2 comments and 1 reply will be posted with it, as one review. So is the 1 comment you started on GitHub. 2 of 5 files viewed.",
            finishWords(Verdict.APPROVE, three, 1, null, 0, 2, 5, "GitHub").fineprint)
        assertEquals("1 comment will be posted with it, as one review. So are the 2 comments you started on GitHub. 0 of 1 files viewed.",
            finishWords(Verdict.COMMENT, one, 2, null, 0, 0, 1, "GitHub").fineprint)
        assertEquals("The 2 comments you started on GitHub will be posted with it. 0 of 1 files viewed.", finishWords(Verdict.COMMENT, none, 2, null, 0, 0, 1, "GitHub").fineprint)
        assertEquals("No inline comments yet. 0 of 1 files viewed.", finishWords(Verdict.COMMENT, none, 0, null, 0, 0, 1, "GitHub").fineprint)
    }

    @Test
    fun `comments of other scopes are counted, and a held review is said instead of what posts`() {
        assertEquals("1 comment will be posted with it, as one review. 1 more waits for the scope it was written in. 0 of 1 files viewed.",
            finishWords(Verdict.COMMENT, one, 0, null, 1, 0, 1, "GitHub").fineprint)
        assertEquals("No inline comments yet. 2 more wait for the scopes they were written in. 0 of 1 files viewed.",
            finishWords(Verdict.COMMENT, none, 0, null, 2, 0, 1, "GitHub").fineprint)
        assertEquals("Held. 0 of 1 files viewed.", finishWords(Verdict.COMMENT, one, 1, "Held.", 0, 0, 1, "GitHub").fineprint)
    }

    @Test
    fun `marking reviewed with drafts warns they stay, and the button says what it does`() {
        val marked = finishWords(null, three, 0, null, 0, 1, 1, "GitHub")
        assertEquals("Your 2 comments and 1 reply won't be posted; they're kept for next time.", marked.keep)
        assertEquals("1 of 1 files viewed.", marked.fineprint)
        assertEquals("Finish review", marked.submit)
        assertEquals("Your 1 comment won't be posted; it's kept for next time.", finishWords(null, one, 0, null, 0, 1, 1, "GitHub").keep)
        assertEquals(null, finishWords(null, none, 0, null, 0, 1, 1, "GitHub").keep)
        assertEquals(null, finishWords(Verdict.COMMENT, one, 0, null, 0, 1, 1, "GitHub").keep)
        assertEquals("Submit to GitLab", finishWords(Verdict.COMMENT, one, 0, null, 0, 1, 1, "GitLab").submit)
    }
}

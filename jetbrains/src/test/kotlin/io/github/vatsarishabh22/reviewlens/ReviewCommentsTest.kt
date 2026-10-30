package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Finish review sends to GitHub, and how GitHub's refusals read. Bodies below are GitHub's real 422 shapes (PR #1 probe). */
class ReviewCommentsTest {

    private fun change(path: String, blob: String) = FileChange(path, null, 'M', blob, 1, 1)
    private val scope = listOf(change("a.py", "a2"), change("b.py", "b2"))

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

    @Test
    fun `only pending comments on the version this scope shows are posted`() {
        val now = Draft("a.py", 7, "a2", "now")
        val posted = Draft("a.py", 9, "a2", "sent before", posted = true)
        val otherScope = Draft("b.py", 3, "b1", "written on an older version")
        val notInScope = Draft("c.py", 1, "c1", "file not in this scope")
        val reply = Draft("a.py", 7, "a2", "a reply goes with its thread", replyTo = "T1")
        assertEquals(listOf(now), toPost(listOf(now, posted, otherScope, notInScope, reply), scope))
    }

    private fun thread(id: String, resolved: Boolean = false, by: String? = null, byMe: Boolean = false) =
        ReviewThread(id, "a.py", 7, 7, false, "abc", resolved, by, "u$id", false, listOf(ThreadComment("lee", "why?")), resolvedByMe = byMe)

    @Test
    fun `every pending reply posts while its thread exists, whatever scope or blob, resolved or not`() {
        val open = Draft("a.py", 7, "a1", "on an older blob still posts", replyTo = "T1")
        val toResolved = Draft("a.py", 7, "a2", "resolved since", replyTo = "T2")
        val gone = Draft("a.py", 7, "a2", "thread deleted", replyTo = "T3")
        val sent = Draft("a.py", 7, "a2", "sent", posted = true, replyTo = "T1")
        val drafts = listOf(open, toResolved, gone, sent, Draft("a.py", 7, "a2", "a comment"))
        assertEquals(listOf(open, toResolved), repliesToPost(drafts, listOf(thread("T1"), thread("T2", resolved = true))))
        assertEquals("threads unknown: try, GitHub refuses a missing one", listOf(open, toResolved, gone), repliesToPost(drafts, null))
    }

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

    private fun vars(body: String) = Gson().fromJson(body, JsonObject::class.java).also { assertTrue(it["query"].asString.startsWith("mutation")) }["variables"].asJsonObject

    @Test
    fun `the pending review starts at the scope's end with every new comment on the new side`() {
        val v = vars(startReviewBody("PR_1", "abc", listOf(Draft("a.py", 7, "a2", "why?"))))
        assertEquals(listOf("PR_1", "abc"), listOf(v["pr"].asString, v["commit"].asString))
        val comment = v["threads"].asJsonArray.single().asJsonObject
        assertEquals(listOf("a.py", "7", "RIGHT", "why?"), listOf("path", "line", "side", "body").map { comment[it].asString })
    }

    @Test
    fun `a reply goes into the pending review, on its thread`() {
        val v = vars(replyBody("PRR_1", Draft("a.py", 7, "a2", "agreed", replyTo = "PRRT_9")))
        assertEquals(listOf("PRR_1", "PRRT_9", "agreed"), listOf("review", "thread", "body").map { v[it].asString })
    }

    @Test
    fun `submit carries the verdict, and a blank summary is sent as none rather than empty`() {
        val v = vars(submitBody("PRR_1", Verdict.REQUEST_CHANGES, "Looks good"))
        assertEquals(listOf("PRR_1", "REQUEST_CHANGES", "Looks good"), listOf("review", "event", "body").map { v[it].asString })
        assertFalse(vars(submitBody("PRR_1", Verdict.COMMENT, "  ")).has("body")) // Gson drops nulls
        assertEquals("PRR_1", vars(deleteReviewBody("PRR_1"))["review"].asString)
    }

    @Test
    fun `an empty Comment or Request changes is held back with its reason, an empty Approve is not`() {
        assertEquals("Add a summary or an inline comment to post a comment review.", submitBlocker(Verdict.COMMENT, " ", 0, false))
        assertEquals("Add a summary or an inline comment to post a request changes review.", submitBlocker(Verdict.REQUEST_CHANGES, "", 0, false))
        assertNull(submitBlocker(Verdict.COMMENT, "", 1, false)) // PR #1 probe: comments alone are accepted
        assertNull(submitBlocker(Verdict.COMMENT, "Looks good", 0, false))
        assertNull(submitBlocker(Verdict.APPROVE, "", 0, false))
    }

    @Test
    fun `on your own pull request only a comment review goes`() {
        val own = "GitHub doesn't let you approve or request changes on your own pull request."
        assertEquals(own, submitBlocker(Verdict.APPROVE, "LGTM", 2, true))
        assertEquals(own, submitBlocker(Verdict.REQUEST_CHANGES, "x", 0, true))
        assertNull(submitBlocker(Verdict.COMMENT, "x", 0, true))
    }

    @Test
    fun `GitHub's reasons are read from its error body`() {
        assertEquals("Path could not be resolved", githubRefusal("""{"message":"Unprocessable Entity","errors":["Path could not be resolved"],"status":"422"}"""))
        assertEquals("Line could not be resolved", githubRefusal("""{"message":"Validation Failed","errors":[{"message":"Line could not be resolved"}]}"""))
        assertEquals("Not Found", githubRefusal("""{"message":"Not Found","status":"404"}"""))
        assertNull(githubRefusal("error connecting to api.github.com"))
    }

    @Test
    fun `the review slot reads the PR, its latest push and your pending review, or none`() {
        assertEquals(ReviewSlot("PR_1", "h2", null), parseReviewSlot("PR_1\th2\t\t0\n"))
        assertEquals(ReviewSlot("PR_1", "h2", PendingReview("PRR_9", 2)), parseReviewSlot("PR_1\th2\tPRR_9\t2\n"))
        assertThrows(IllegalArgumentException::class.java) { parseReviewSlot("") }
    }

    @Test
    fun `a github_com review is joined only when its new comments would land on the lines they were written for`() {
        val slot = ReviewSlot("PR_1", "h2", PendingReview("PRR_9", 1))
        assertNull(slot.blocker("h2", 3)) // scope ends at the latest push: adopt
        assertNull(slot.blocker("h1", 0)) // replies only: they sit on their threads
        assertNull(slot.copy(pending = null).blocker("h1", 3)) // no pending review: a new one is pinned to h1
        assertEquals("You have a review in progress on github.com, and comments added to it land on the latest push. " +
            "Review up to the latest push to add yours, or submit that one on github.com first.", slot.blocker("h1", 1))
    }

    @Test
    fun `a comment joins an adopted review on the new side, and only the comments this Finish added are undone`() {
        val v = vars(addThreadBody("PRR_9", Draft("a.py", 7, "a2", "why?")))
        assertEquals(listOf("PRR_9", "a.py", "7", "why?"), listOf("review", "path", "line", "body").map { v[it].asString })
        assertTrue(Gson().fromJson(addThreadBody("PRR_9", Draft("a.py", 7, "a2", "x")), JsonObject::class.java)["query"].asString.contains("side:RIGHT"))
        assertEquals("PRRC_1", vars(deleteCommentBody("PRRC_1"))["comment"].asString)
    }

    @Test
    fun `a comment on several lines names its first line on the same side, one on a line names none`() {
        val range = Draft("a.py", 17, "b1", "split", startLine = 15)
        fun thread(body: String) = Gson().fromJson(body, JsonObject::class.java)["variables"].asJsonObject["threads"].asJsonArray[0].asJsonObject
        assertEquals(15 to "RIGHT", thread(startReviewBody("PR_1", "c1", listOf(range))).let { it["startLine"].asInt to it["startSide"].asString })
        assertTrue(thread(startReviewBody("PR_1", "c1", listOf(range.copy(startLine = null)))).let { !it.has("startLine") && !it.has("startSide") })
        assertEquals(15, vars(addThreadBody("PRR_9", range))["startLine"].asInt)
        assertTrue(!vars(addThreadBody("PRR_9", range.copy(startLine = null))).has("startLine"))
        assertEquals("lines 15–17", span(15, 17)); assertEquals("line 17", span(null, 17))
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
    fun `a body splits into its text and each suggestion's lines, which Suggest a change writes back the same way`() {
        assertEquals(listOf(BodyPart.Text("int() raises on \"1,200\".")), bodyParts("int() raises on \"1,200\"."))
        val body = "Both fields:\n" + suggestionBlock(listOf("    a = 1", "    b = 2")) + "\nthen this.\n```suggestion\n```"
        assertEquals(listOf(BodyPart.Text("Both fields:"), BodyPart.Suggestion(listOf("    a = 1", "    b = 2")), BodyPart.Text("then this."), BodyPart.Suggestion(emptyList())), bodyParts(body))
        assertEquals("a code block is not a suggestion", listOf(BodyPart.Text("```python\nx = 1\n```")), bodyParts("```python\nx = 1\n```"))
    }

    @Test
    fun `an edit replaces the posted comment's text by its id`() {
        val v = vars(editCommentBody("PRRC_7", "new text"))
        assertEquals("PRRC_7" to "new text", v["comment"].asString to v["body"].asString)
        assertTrue(Gson().fromJson(editCommentBody("PRRC_7", "x"), JsonObject::class.java)["query"].asString.contains("updatePullRequestReviewComment(input:{pullRequestReviewCommentId:"))
    }

    @Test
    fun `resolve and reopen are separate GitHub calls on the thread id`() {
        fun query(body: String) = Gson().fromJson(body, JsonObject::class.java)["query"].asString
        assertEquals("PRRT_4", vars(resolveBody("PRRT_4", true))["thread"].asString)
        assertTrue(query(resolveBody("PRRT_4", true)).contains("{resolveReviewThread("))
        assertTrue(query(resolveBody("PRRT_4", false)).contains("{unresolveReviewThread("))
    }
}

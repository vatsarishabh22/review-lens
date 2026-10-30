package io.github.vatsarishabh22.reviewlens.github

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.vatsarishabh22.reviewlens.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What GhClient sends GitHub and how it reads the answers. Bodies below are GitHub's real shapes (PR #1 probe, PR #579, plugin-template #598). */
class GitHubWireTest {
    // gh pr list's real shape (JetBrains/intellij-platform-plugin-template #598); the second asks a user and a team (no login)
    private val ghList = """[
        {"additions":1,"author":{"id":"U1","is_bot":false,"login":"SimY4","name":"Alex"},"baseRefName":"main","body":"x","changedFiles":1,"deletions":0,
         "headRefName":"fix/zip","headRefOid":"2f9d9ea","number":598,"reviewRequests":[],"title":"chore: zip","updatedAt":"2026-07-23T01:09:36Z"},
        {"additions":186,"author":{"id":"U2","is_bot":false,"login":"nikitavilkov","name":""},"baseRefName":"main","body":"","changedFiles":12,"deletions":166,
         "headRefName":"feature/chat","headRefOid":"e9c9c9d","number":597,"title":"AI chat demo","updatedAt":"2026-07-20T10:00:00Z",
         "reviewRequests":[{"__typename":"Team","name":"core","slug":"core"},{"__typename":"User","login":"me"}]}
    ]"""

    @Test
    fun `GitHub's PR list becomes host-neutral rows, waiting on you only when a request names you`() {
        val (a, b) = parsePullRequests(ghList, "me")
        assertEquals(PullRequest(598, "chore: zip", "fix/zip", "2f9d9ea", "main", Person("SimY4", "Alex"), "x", 1, 0, 1, "2026-07-23T01:09:36Z", false), a)
        assertEquals(Person("nikitavilkov", "nikitavilkov"), b.author) // a blank name shows the login
        assertEquals(true, b.requested)
        assertEquals("a team request names nobody", false, parsePullRequests(ghList, "core")[1].requested)
        assertEquals("viewer unknown: nothing waits", false, parsePullRequests(ghList, null)[1].requested)
    }

    @Test
    fun `reads GitHub's answer across pages and skips states it does not know`() {
        val text = "#id\tPR_1\n.github/a.yml\tVIEWED\n.github/b.yml\tUNVIEWED\n#id\tPR_1\nsrc/c.kt\tDISMISSED\nsrc/d.kt\tSOMETHING_NEW\n"
        val states = parseViewedStates(text)
        assertEquals("PR_1", states.pullRequestId)
        assertEquals(mapOf(".github/a.yml" to ViewedState.VIEWED, ".github/b.yml" to ViewedState.UNVIEWED, "src/c.kt" to ViewedState.DISMISSED), states.files)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an answer without the pull request is refused`() { parseViewedStates("a.yml\tVIEWED\n") }

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

    private val c = Draft("a.py", 7, "a2", "c")
    private val r = Draft("a.py", 7, "a2", "r", replyTo = "T1")
    private fun box(comments: Int = 0, replies: Int = 0) = Outbox(List(comments) { c }, List(replies) { r })
    private fun slot(pending: PendingReview? = null, own: Boolean = false) = GitHubSlot("PR_1", "h2", pending, own)

    @Test
    fun `an empty Comment or Request changes is held back with its reason, an empty Approve is not`() {
        assertEquals("Add a summary or an inline comment to post a comment review.", slot().refusal(Verdict.COMMENT, " ", box(), "h2"))
        assertEquals("Add a summary or an inline comment to post a request changes review.", slot().refusal(Verdict.REQUEST_CHANGES, "", box(), "h2"))
        assertNull(slot().refusal(Verdict.COMMENT, "", box(comments = 1), "h2")) // PR #1 probe: comments alone are accepted
        assertNull(slot().refusal(Verdict.COMMENT, "", box(replies = 1), "h2"))
        assertNull(slot(PendingReview("PRR_9", 1)).refusal(Verdict.COMMENT, "", box(), "h2")) // the adopted review's comment counts
        assertNull(slot().refusal(Verdict.COMMENT, "Looks good", box(), "h2"))
        assertNull(slot().refusal(Verdict.APPROVE, "", box(), "h2"))
    }

    @Test
    fun `on your own pull request only a comment review goes, greyed before anything is written`() {
        val own = "GitHub doesn't let you approve or request changes on your own pull request."
        assertEquals(own, slot(own = true).denied(Verdict.APPROVE))
        assertEquals(own, slot(own = true).denied(Verdict.REQUEST_CHANGES))
        assertNull(slot(own = true).denied(Verdict.COMMENT))
        assertNull(slot().denied(Verdict.APPROVE))
        assertEquals(own, slot(own = true).refusal(Verdict.APPROVE, "LGTM", box(comments = 2), "h2"))
        assertNull(slot(own = true).refusal(Verdict.COMMENT, "x", box(), "h2"))
    }

    @Test
    fun `GitHub's reasons are read from its error body`() {
        assertEquals("Path could not be resolved", githubRefusal("""{"message":"Unprocessable Entity","errors":["Path could not be resolved"],"status":"422"}"""))
        assertEquals("Line could not be resolved", githubRefusal("""{"message":"Validation Failed","errors":[{"message":"Line could not be resolved"}]}"""))
        assertEquals("Not Found", githubRefusal("""{"message":"Not Found","status":"404"}"""))
        assertNull(githubRefusal("error connecting to api.github.com"))
    }

    @Test
    fun `the review slot reads the PR, its latest push, your pending review or none, and whether you opened it`() {
        assertEquals(GitHubSlot("PR_1", "h2", null, false), parseReviewSlot("PR_1\th2\t\t0\tfalse\n"))
        assertEquals(GitHubSlot("PR_1", "h2", PendingReview("PRR_9", 2), true), parseReviewSlot("PR_1\th2\tPRR_9\t2\ttrue\n"))
        assertEquals(2, parseReviewSlot("PR_1\th2\tPRR_9\t2\ttrue\n").started)
        assertThrows(IllegalArgumentException::class.java) { parseReviewSlot("") }
    }

    @Test
    fun `a review started on GitHub is joined only when its new comments would land on the lines they were written for`() {
        val held = "You have a review in progress on GitHub, and comments added to it land on the latest push. " +
            "Review up to the latest push to add yours, or submit that one on GitHub first."
        val pending = slot(PendingReview("PRR_9", 1))
        assertNull(pending.held(box(comments = 3), "h2")) // scope ends at the latest push: adopt
        assertNull(pending.held(box(replies = 1), "h1")) // replies only: they sit on their threads
        assertNull(slot().held(box(comments = 3), "h1")) // no pending review: a new one is pinned to h1
        assertEquals(held, pending.held(box(comments = 1), "h1"))
        assertEquals("held wins, even over your own PR's refusal", held, slot(PendingReview("PRR_9", 1), own = true).refusal(Verdict.APPROVE, "LGTM", box(comments = 1), "h1"))
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

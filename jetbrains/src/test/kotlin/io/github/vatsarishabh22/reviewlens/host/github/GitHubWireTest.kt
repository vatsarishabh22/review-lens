package io.github.vatsarishabh22.reviewlens.host.github

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.vatsarishabh22.reviewlens.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What GitHubClient sends GitHub and how it reads the answers. Bodies below are GitHub's real shapes (PR #1 probe, PR #579, plugin-template #598). */
class GitHubWireTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    // PULL_REQUESTS_QUERY's shape on JetBrains/intellij-platform-plugin-template #598; the second asks a team (no login) and a user
    private fun list(viewer: String?) = json("""{"viewer":${viewer?.let { "{\"login\":\"$it\"}" } ?: "null"},"repository":{"pullRequests":{"nodes":[
        {"additions":1,"author":{"login":"SimY4","name":"Alex"},"baseRefName":"main","body":"x","changedFiles":1,"deletions":0,
         "headRefName":"fix/zip","headRefOid":"2f9d9ea","number":598,"reviewRequests":{"nodes":[]},"title":"chore: zip","updatedAt":"2026-07-23T01:09:36Z","isCrossRepository":false},
        {"additions":186,"author":{"login":"nikitavilkov","name":""},"baseRefName":"main","body":null,"changedFiles":12,"deletions":166,
         "headRefName":"feature/chat","headRefOid":"e9c9c9d","number":597,"title":"AI chat demo","updatedAt":"2026-07-20T10:00:00Z",
         "reviewRequests":{"nodes":[{"requestedReviewer":{}},{"requestedReviewer":{"login":"me"}}]},"isCrossRepository":true},
        {"additions":0,"author":null,"baseRefName":"main","body":"","changedFiles":1,"deletions":0,"headRefName":"old","headRefOid":"f1","number":5,
         "title":"t","updatedAt":"2026-07-23T01:09:36Z","reviewRequests":{"nodes":[]},"isCrossRepository":false}
    ]}}}""")

    @Test
    fun `each open PR's commits come oldest first, and a PR with none has an empty list`() =
        // COMMIT_IDS_QUERY's real answer on this repo's open PR #14, then a PR GitHub lists with no commits
        assertEquals(mapOf(14 to listOf("9b725a42e8f342cfb69b51c9ae6c1293a6559174", "5ffc2c56d5f8831df37967163b9ed904f92ebfc1"), 15 to emptyList()),
            parseCommitIds(json("""{"repository":{"pullRequests":{"nodes":[{"number":14,"commits":{"nodes":[{"commit":{"oid":"9b725a42e8f342cfb69b51c9ae6c1293a6559174"}},
                {"commit":{"oid":"5ffc2c56d5f8831df37967163b9ed904f92ebfc1"}}]}},{"number":15,"commits":{"nodes":[]}}]}}}""")))

    @Test
    fun `GitHub's PR list becomes host-neutral rows, waiting on you only when a request names you`() {
        val (a, b, gone) = parsePullRequests(list("me"))
        assertEquals(PullRequest(598, "chore: zip", "fix/zip", "2f9d9ea", "main", Person("SimY4", "Alex"), "x", 1, 0, 1, "2026-07-23T01:09:36Z", false), a)
        assertEquals(Person("nikitavilkov", "nikitavilkov"), b.author) // a blank name shows the login
        assertEquals("", b.body)
        assertEquals(true, b.requested)
        assertEquals("a deleted account", Person("ghost", "ghost"), gone.author)
        assertEquals("a team request names nobody", false, parsePullRequests(list("core"))[1].requested)
        assertEquals(listOf(false, true), listOf(a.fromFork, b.fromFork))
        assertEquals("viewer unknown: nothing waits", false, parsePullRequests(list(null))[1].requested)
        assertThrows(IllegalArgumentException::class.java) { parsePullRequests(json("""{"repository":null}""")) }
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
    fun `the review slot reads the PR, its latest push, your pending review or none, and whether you opened it`() {
        fun slot(reviews: String, own: Boolean) = parseReviewSlot(json("""{"repository":{"pullRequest":{"id":"PR_1","headRefOid":"h2","viewerDidAuthor":$own,"reviews":{"nodes":[$reviews]}}}}"""))
        assertEquals(GitHubSlot("PR_1", "h2", null, false), slot("", false))
        assertEquals(GitHubSlot("PR_1", "h2", PendingReview("PRR_9", 2), true), slot("""{"id":"PRR_9","comments":{"totalCount":2}}""", true))
        assertEquals(2, slot("""{"id":"PRR_9","comments":{"totalCount":2}}""", true).started)
        assertThrows(IllegalArgumentException::class.java) { parseReviewSlot(json("""{"repository":{"pullRequest":null}}""")) }
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
    fun `a batch of ticks is one call, a mutation per file in order, each path a variable`() {
        val body = viewedBody("PR_1", linkedMapOf("a.kt" to true, "dir/b \"q\".kt" to false))
        val vars = vars(body)
        assertEquals(listOf("PR_1", "a.kt", "dir/b \"q\".kt"), listOf("id", "p0", "p1").map { vars[it].asString })
        assertEquals("mutation(\$id:ID!,\$p0:String!,\$p1:String!){m0:markFileAsViewed(input:{pullRequestId:\$id,path:\$p0}){clientMutationId} " +
            "m1:unmarkFileAsViewed(input:{pullRequestId:\$id,path:\$p1}){clientMutationId}}", Gson().fromJson(body, JsonObject::class.java)["query"].asString)
    }

    @Test
    fun `resolve and reopen are separate GitHub calls on the thread id`() {
        fun query(body: String) = Gson().fromJson(body, JsonObject::class.java)["query"].asString
        assertEquals("PRRT_4", vars(resolveBody("PRRT_4", true))["thread"].asString)
        assertTrue(query(resolveBody("PRRT_4", true)).contains("{resolveReviewThread("))
        assertTrue(query(resolveBody("PRRT_4", false)).contains("{unresolveReviewThread("))
    }
}

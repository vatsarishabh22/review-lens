package io.github.vatsarishabh22.reviewlens.host.github

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import io.github.vatsarishabh22.reviewlens.core.*
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Finish against a local GitHub: whatever kind of failure a step hits, a write that may have landed is read back before
 * anything is undone (a half-made review left behind is adopted by the next Finish, which posts it twice).
 */
class FinishFailureTest : BasePlatformTestCase() {
    private val sent = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var server: HttpServer

    /** GitHub answering each mutation or query by the first of [answers] whose key the request's body names. */
    private fun github(vararg answers: Pair<String, String>): GitHubClient {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { x ->
                val body = x.requestBody.readAllBytes().decodeToString().also { sent += it }
                val answer = answers.first { (key, _) -> key in body }.second
                x.sendResponseHeaders(200, answer.toByteArray().size.toLong()); x.responseBody.use { it.write(answer.toByteArray()) }
            }
            start()
        }
        val root = "http://127.0.0.1:${server.address.port}"
        return GitHubClient(GitHubApi("github.com", "t", rest = root, graphqlUrl = "$root/graphql"), GitHubRepo("acme", "shop"))
    }

    override fun tearDown() {
        server.stop(0)
        super.tearDown()
    }

    private fun slot(pending: String) = "reviews(states:PENDING" to
        """{"data":{"repository":{"pullRequest":{"id":"PR_1","headRefOid":"h1","viewerDidAuthor":false,"reviews":{"nodes":[$pending]}}}}}"""
    private val reply = Draft("a.py", 3, "a1", "agreed", replyTo = "PRRT_1")

    fun `test a fresh review whose reply came back unreadable is read back, found pending, and undone`() {
        val client = github(slot(""),
            "addPullRequestReview(" to """{"data":{"addPullRequestReview":{"pullRequestReview":{"id":"PRR_new"}}}}""",
            "addPullRequestReviewThreadReply" to """{"data":{"addPullRequestReviewThreadReply":null}}""",
            "node(id:" to """{"data":{"node":{"state":"PENDING","comments":{"totalCount":1}}}}""",
            "deletePullRequestReview(" to """{"data":{"deletePullRequestReview":{"pullRequestReview":{"id":"PRR_new"}}}}""")
        val e = try { client.submitReview(1, "h1", "ok", Verdict.COMMENT, emptyList(), listOf(reply)); null } catch (e: CliException) { e }
        assertEquals("GitHub didn't answer in time. Nothing was posted", e?.message)
        assertTrue("read back, then undone", sent.any { "node(id:" in it } && sent.any { "deletePullRequestReview(" in it })
    }

    fun `test an adopted review whose answer breaks the parser is read back and never undone`() {
        val client = github(slot("""{"id":"PRR_9","comments":{"totalCount":1}}"""),
            "addPullRequestReviewThread(" to """{"data":{"addPullRequestReviewThread":{"thread":{"comments":{"nodes":null}}}}}""",
            "node(id:" to """{"data":{"node":{"state":"PENDING","comments":{"totalCount":2}}}}""")
        val e = try { client.submitReview(1, "h1", "", Verdict.COMMENT, listOf(Draft("a.py", 3, "a1", "why?")), emptyList()); null } catch (e: CliException) { e }
        assertEquals("Review Lens can't tell what reached GitHub. Open the pull request on GitHub before you finish again", e?.message)
        assertTrue(sent.any { "node(id:" in it })
        assertFalse("an adopted review is never undone after a call that may land", sent.any { "delete" in it })
    }
}

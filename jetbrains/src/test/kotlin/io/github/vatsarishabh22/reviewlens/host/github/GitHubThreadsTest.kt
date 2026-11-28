package io.github.vatsarishabh22.reviewlens.host.github

import com.google.gson.JsonParser
import io.github.vatsarishabh22.reviewlens.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GitHub's review-threads answer read into [ReviewThread]s, and how those read. */
class GitHubThreadsTest {

    // REVIEW_THREADS_QUERY's shape for a public PR (nessalabs/skills#26), over two pages: GitHub moved the open thread from 192 to 194 at the head
    private val head = "bfd851a0c0ffee"
    private fun c(author: String?, body: String, extra: String = "") =
        """{${extra}"author":${author?.let { "{\"login\":\"$it\"}" } ?: "null"},"body":"$body","url":"u","commit":{"oid":"$head"}}"""
    private fun thread(fields: String, comments: String, url: String, commit: String = head) =
        """{$fields,"comments":{"nodes":[${comments.replaceFirst("\"url\":\"u\"", "\"url\":\"$url\"").replaceFirst("\"oid\":\"$head\"", "\"oid\":\"$commit\"")}]}}"""
    private fun page(vararg threads: String) = JsonParser.parseString("""{"viewer":{"login":"me"},"repository":{"pullRequest":{"reviewThreads":
        {"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[${threads.joinToString(",")}]}}}}""").asJsonObject

    private val threads = parseReviewThreads(listOf(
        page(
            thread(""""path":"a.md","line":194,"startLine":191,"originalLine":192,"diffSide":"RIGHT","isResolved":false,"viewerCanResolve":true,"viewerCanUnresolve":false""",
                c("bot", "Fix this") + "," + c("me", "Done", "\"id\":\"PRRC_2\",\"viewerCanUpdate\":true,\"viewerCanDelete\":true,"), "u1"),
            thread(""""path":"a.md","line":null,"originalLine":44,"diffSide":"RIGHT","isResolved":false""", c("me", "Old"), "u2", "e52631d"),
            thread(""""path":"a.md","line":12,"startLine":12,"originalLine":12,"diffSide":"RIGHT","isResolved":true,"resolvedBy":{"login":"me"},"viewerCanResolve":false,"viewerCanUnresolve":true""",
                c("bot", "Nit"), "u3"),
        ),
        page(
            thread(""""path":"b.py","line":3,"originalLine":3,"diffSide":"LEFT","isResolved":false""", c("bot", "Removed line"), "u4"),
            thread(""""path":"b.py","line":7,"originalLine":5,"diffSide":"RIGHT","isResolved":false""", c(null, "On a newer push"), "u5", "newer01"),
            thread(""""path":"c.py","line":1,"originalLine":1,"diffSide":"RIGHT","isResolved":false""", "", "u6"),
        ),
    ))

    @Test fun `reads every thread that has a comment on every page, with its replies`() {
        assertEquals(listOf("u1", "u2", "u3", "u4", "u5"), threads.map { it.url })
        assertEquals(listOf("bot", "me"), threads[0].comments.map { it.author })
        assertEquals("only the thread the viewer started is theirs", listOf(false, true, false, false, false), threads.map { it.mine })
        assertEquals("a deleted account", "ghost", threads[4].comments[0].author)
        assertEquals(listOf(head, "e52631d", "newer01"), listOf(threads[0], threads[1], threads[4]).map { it.commit })
    }

    @Test fun `what GitHub lets this viewer do, and nothing when GitHub doesn't say`() {
        assertEquals(listOf(true to false, false to false, false to true), threads.take(3).map { it.canResolve to it.canUnresolve })
    }

    @Test fun `a thread on several lines keeps its first line`() {
        assertEquals(listOf(191, null, null), threads.take(3).map { it.startLine }) // GitHub sends startLine == line for one line
    }

    @Test fun `each comment keeps its id and whether GitHub lets the viewer edit or delete it`() {
        assertEquals(ThreadComment("me", "Done", "PRRC_2", canEdit = true, canDelete = true, mine = true), threads[0].comments[1])
        assertEquals(false to false, threads[0].comments[0].let { it.canEdit to it.canDelete })
    }

    @Test fun `resolved by someone else only when GitHub says the resolver isn't the viewer`() {
        assertEquals(listOf(false, false, false), threads.take(3).map { it.resolvedByOther }) // open, outdated, resolved by me
        assertTrue(threads[2].copy(resolvedByMe = false).resolvedByOther)
    }

    @Test fun `state is resolved first, then outdated when GitHub has no line, else open`() {
        assertEquals(listOf(ThreadState.OPEN, ThreadState.OUTDATED, ThreadState.RESOLVED, ThreadState.OPEN, ThreadState.OPEN), threads.map { it.state })
    }

    @Test fun `a thread sits under GitHub's line only in the version GitHub anchored it to, on the new side`() {
        assertEquals(194, threads[0].lineAt(head))
        assertNull("outdated", threads[1].lineAt(head))
        assertEquals("resolved still placed, folded by the editor", 12, threads[2].lineAt(head))
        assertNull("old side of the diff", threads[3].lineAt(head))
        assertNull("anchored to a push this checkout doesn't show", threads[4].lineAt(head))
        assertNull("a narrower scope ends before the head", threads[0].lineAt("a1f3c2e"))
    }

    @Test fun `the list says where a thread is, or was`() {
        assertEquals("line 194", threads[0].where)
        assertEquals("line 44 on an older push", threads[1].where)
    }

}

package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    }

    @Test
    fun `only pending drafts on the version this scope shows are posted`() {
        val now = Draft("a.py", 7, "a2", "now")
        val posted = Draft("a.py", 9, "a2", "sent before", posted = true)
        val otherScope = Draft("b.py", 3, "b1", "written on an older version")
        val notInScope = Draft("c.py", 1, "c1", "file not in this scope")
        assertEquals(listOf(now), toPost(listOf(now, posted, otherScope, notInScope), scope))
    }

    @Test
    fun `one payload carries the commit, the verdict, the summary and every comment on the new side`() {
        val json = Gson().fromJson(reviewPayload("abc", "Looks good", Verdict.REQUEST_CHANGES, listOf(Draft("a.py", 7, "a2", "why?"))), JsonObject::class.java)
        assertEquals("abc", json["commit_id"].asString)
        assertEquals("REQUEST_CHANGES", json["event"].asString)
        assertEquals("Looks good", json["body"].asString)
        val comment = json["comments"].asJsonArray.single().asJsonObject
        assertEquals(listOf("a.py", "7", "RIGHT", "why?"), listOf("path", "line", "side", "body").map { comment[it].asString })
    }

    @Test
    fun `a blank summary is left out rather than sent empty`() =
        assertFalse(Gson().fromJson(reviewPayload("abc", "  ", Verdict.COMMENT, emptyList()), JsonObject::class.java).has("body"))

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
}

package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.util.Disposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Immediate writes to the host: what each says on its way, the reload after it, and a refusal handed back. */
class HostThreadsTest {
    private val calls = mutableListOf<String>()
    private var refuse: String? = null
    private lateinit var threads: HostThreads

    // a host that records each write and what the views were told meanwhile
    private val host = object : ReviewHost {
        override val name = "TestHost"
        override fun openPullRequests() = emptyList<PullRequest>()
        override fun headRef(number: Int) = ""
        override fun reviewThreads(number: Int) = emptyList<ReviewThread>()
        override fun reviewSlot(number: Int): ReviewSlot = error("unused")
        override fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>) = Unit
        override fun checks(number: Int) = Checks(ChecksState.NONE)
        override fun resolveThread(threadId: String, resolved: Boolean) = record("resolve $threadId $resolved", threadId)
        override fun editComment(commentId: String, body: String) = record("edit $commentId $body", commentId)
        override fun deleteComment(commentId: String) = record("delete $commentId", commentId)
        override val viewed: ViewedSync? = null
        private fun record(call: String, id: String) {
            calls += "$call while ${threads.pending[id]}"
            refuse?.let { throw CliException(it) }
        }
    }

    private fun threads() = HostThreads({ host }, { calls += "reload" }, { it() }, { it() }).also { threads = it }
    private val comment = ThreadComment("me", "old", id = "C1", canEdit = true, canDelete = true, mine = true)
    private val thread = ReviewThread("T1", "a.py", 3, 3, false, "h", false, null, "u", true, listOf(comment))

    @Test
    fun `each write says what it is doing until the host answers, then the threads are read again`() {
        val t = threads()
        var redraws = 0
        t.onChanged(Disposer.newDisposable()) { redraws++ }
        t.edit(comment, "new") { error("not refused") }
        t.delete(comment) { error("not refused") }
        t.resolve(thread, true) { error("not refused") }
        t.resolve(thread, false) { error("not refused") }
        assertEquals(listOf("edit C1 new while Saving…", "reload", "delete C1 while Deleting…", "reload",
            "resolve T1 true while Resolving…", "reload", "resolve T1 false while Unresolving…", "reload"), calls)
        assertEquals(emptyMap<String, String>(), t.pending)
        assertEquals("a redraw when each starts and when each ends", 8, redraws)
    }

    @Test
    fun `a refusal comes back with the host's reason, after the threads are read again`() {
        val t = threads()
        refuse = "Not Found"
        var reason: String? = null
        t.edit(comment, "new") { reason = it.message; calls += "refused" }
        assertEquals(listOf("edit C1 new while Saving…", "reload", "refused"), calls)
        assertEquals("Not Found", reason)
        assertNull(t.pending["C1"])
    }
}

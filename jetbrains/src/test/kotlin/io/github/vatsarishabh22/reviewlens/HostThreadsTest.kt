package io.github.vatsarishabh22.reviewlens

import com.intellij.openapi.util.Disposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The review's threads on the host: read at load and again, and the immediate writes with what each says on its way. */
class HostThreadsTest {
    private val calls = mutableListOf<String>()
    private var refuse: String? = null
    private var number: Int? = 7
    private var answer: List<ReviewThread>? = emptyList()
    private lateinit var threads: HostThreads

    // a host that records each call and what the views were told meanwhile
    private val host = object : ReviewHost {
        override val name = "TestHost"
        override fun openPullRequests() = emptyList<PullRequest>()
        override fun headRef(number: Int) = ""
        override fun reviewThreads(number: Int): List<ReviewThread> { calls += "reload"; return answer ?: throw CliException("offline") }
        override fun reviewSlot(number: Int): ReviewSlot = throw CliException("not read here")
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

    private fun threads() = HostThreads({ host }, { number }, { it() }, { it() }).also { threads = it }
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
        assertEquals("a redraw when each starts, when its threads come back, and when it ends", 12, redraws)
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

    @Test
    fun `threads load for a review on the host, and a branch review reads none`() {
        val t = threads()
        answer = listOf(thread)
        t.load()
        assertEquals(listOf(thread), t.threads)
        number = null
        t.load()
        assertNull(t.threads)
        assertEquals("a branch review never asks the host", listOf("reload"), calls)
    }

    @Test
    fun `a failed reload keeps the threads shown and redraws nothing`() {
        val t = threads()
        answer = listOf(thread)
        t.load()
        var redraws = 0
        t.onChanged(Disposer.newDisposable()) { redraws++ }
        answer = null
        t.reload()
        assertEquals(listOf(thread), t.threads)
        assertEquals(0, redraws)
    }

    @Test
    fun `a thread sits under its line only in the version the host anchored it to`() {
        val t = threads()
        answer = listOf(thread)
        t.load()
        val change = FileChange("a.py", null, 'M', "b2", 1, 0)
        assertEquals(listOf(thread to 3), t.threadsOn(change, "h"))
        assertEquals(listOf(thread to null), t.threadsOn(change, "older"))
        assertEquals(emptyList<Pair<ReviewThread, Int?>>(), t.threadsOn(change, null))
    }
}

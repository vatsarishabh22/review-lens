package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The review's Viewed ticks on the host: folded in at load, sent as they are made, each batch in one call, and a failure said once. The merge rules are spec/cases/viewed-*.json. */
class HostViewedTest {
    private val git = GitClient(Files.createTempDirectory("review-lens-viewed"))
    private val warned = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private var answer: ViewedStates? = ViewedStates("PR_1", mapOf("a.kt" to ViewedState.VIEWED, "b.kt" to ViewedState.UNVIEWED))
    private var takes = true

    private val viewedSync = object : ViewedSync {
        override fun viewedStates(number: Int) = answer ?: throw CliException("offline")
        override fun setViewed(reviewId: String, ticks: Map<String, Boolean>) { sent += "$reviewId $ticks"; if (!takes) throw CliException("offline") }
    }
    private fun host(viewed: ViewedSync?) = object : ReviewHost {
        override val name = "TestHost"
        override fun openPullRequests() = emptyList<PullRequest>()
        override fun headRef(number: Int) = ""
        override fun reviewThreads(number: Int) = emptyList<ReviewThread>()
        override fun reviewSlot(number: Int): ReviewSlot = error("unused")
        override fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>) = Unit
        override fun checks(number: Int) = Checks(ChecksState.NONE)
        override fun changedFiles(from: String, to: String) = 0
        override fun resolveThread(threadId: String, resolved: Boolean) = Unit
        override fun editComment(commentId: String, body: String) = Unit
        override fun deleteComment(commentId: String) = Unit
        override val viewed = viewed
    }

    private fun synced(host: ReviewHost?) = HostViewed({ host }, { it() }, { title, _ -> warned += title })
    /** Calls held back until the test lets them go, as a slow host keeps them. */
    private val held = mutableListOf<() -> Unit>()
    private var now = 0L
    private fun slow(host: ReviewHost?) = HostViewed({ host }, { held += it }, { title, _ -> warned += title }, { now })
    private val a = FileChange("a.kt", null, 'M', "a2", 1, 0)
    private val b = FileChange("b.kt", null, 'M', "b2", 1, 0)
    private val c = FileChange("c.kt", null, 'M', "c2", 1, 0)
    private val pr = ReviewMeta(ReviewTarget.forPr(1, "t", "h", "origin/main"), ScopeRange("f", "t"), "All", mapOf("b.kt" to "b2"))

    @Test
    fun `at load the host's ticks fold in, and only the files it lists send ticks`() {
        val viewed = synced(host(viewedSync))
        val meta = viewed.fold(git, pr, listOf(a, b, c))
        assertEquals(mapOf("a.kt" to "a2"), meta.reviewed)
        assertEquals(HostViewed.Status.HOST, viewed.status)
        assertTrue(viewed.sends(meta, a))
        assertFalse("c.kt is not in the review's files on the host: no Viewed box there", viewed.sends(meta, c))
    }

    @Test
    fun `a queued tick the host still refuses at load keeps the status failed and stays queued`() {
        takes = false
        val viewed = synced(host(viewedSync))
        val meta = viewed.fold(git, pr.copy(reviewed = mapOf("a.kt" to "a2"), unsent = mapOf("a.kt" to true)), listOf(a, b))
        assertEquals(mapOf("a.kt" to true), meta.unsent)
        assertEquals(HostViewed.Status.FAILED, viewed.status)
    }

    @Test
    fun `the queue goes in one call at load, and leaves whole when the host takes it`() {
        val viewed = synced(host(viewedSync))
        val meta = viewed.fold(git, pr.copy(reviewed = mapOf("a.kt" to "a2"), unsent = mapOf("a.kt" to true, "b.kt" to false)), listOf(a, b))
        assertEquals(listOf("PR_1 {a.kt=true, b.kt=false}"), sent)
        assertEquals(null, meta.unsent)
        assertEquals(HostViewed.Status.HOST, viewed.status)
    }

    @Test
    fun `a host with no Viewed checkboxes keeps the ticks here and queues nothing`() {
        val viewed = synced(host(null))
        assertEquals(pr, viewed.fold(git, pr, listOf(a, b)))
        assertEquals(HostViewed.Status.LOCAL, viewed.status)
        assertFalse(viewed.sends(pr, a))
    }

    @Test
    fun `a branch review stays local`() {
        val branch = pr.copy(target = ReviewTarget.forBranch("origin/feat", "origin/main"))
        val viewed = synced(host(viewedSync))
        assertEquals(branch, viewed.fold(git, branch, listOf(a)))
        assertEquals(HostViewed.Status.LOCAL, viewed.status)
        assertFalse(viewed.sends(branch, a))
    }

    @Test
    fun `signed out or offline, the ticks queue here and the failure is said once`() {
        val viewed = synced(null)
        assertEquals(pr, viewed.fold(git, pr, listOf(a, b)))
        assertEquals(HostViewed.Status.FAILED, viewed.status)
        assertTrue("unknown host: the tick queues for when one answers", viewed.sends(pr, a))
        viewed.fold(git, pr, listOf(a, b))
        assertEquals(listOf("Can't reach your code host right now"), warned)
    }

    @Test
    fun `a tick goes to the review's id once the host answered, and a refusal is said once`() {
        val viewed = synced(host(viewedSync))
        val outcomes = mutableListOf<Map<String, Boolean>?>()
        viewed.send(git, mapOf("a.kt" to true)) { outcomes += it }
        assertEquals("nothing goes before the host answered at load", emptyList<String>(), sent)
        viewed.fold(git, pr, listOf(a, b))
        viewed.send(git, mapOf("a.kt" to true)) { outcomes += it }
        takes = false
        viewed.send(git, mapOf("b.kt" to true, "c.kt" to true)) { outcomes += it }
        viewed.send(git, mapOf("b.kt" to false)) { outcomes += it }
        assertEquals(listOf("PR_1 {a.kt=true}", "PR_1 {b.kt=true, c.kt=true}", "PR_1 {b.kt=false}"), sent)
        assertEquals(listOf(mapOf("a.kt" to true), null, null), outcomes)
        assertEquals(HostViewed.Status.FAILED, viewed.status)
        assertEquals(1, warned.size)
    }

    @Test
    fun `ticks on their way are said once a call has waited, and no longer once it is answered or refused`() {
        val viewed = slow(host(viewedSync))
        viewed.fold(git, pr, listOf(a, b))
        viewed.send(git, mapOf("a.kt" to true, "b.kt" to true)) {}
        viewed.send(git, mapOf("b.kt" to false)) {}
        now = 400
        assertEquals("a quick answer never shows", 0, viewed.waiting())
        now = 600
        assertEquals("two distinct files on their way", 2, viewed.waiting())
        assertTrue(viewed.sending("b.kt"))
        held.removeAt(0)()
        assertEquals(listOf("b.kt"), listOf("a.kt", "b.kt").filter(viewed::sending))
        takes = false
        held.removeAt(0)()
        assertEquals("a refused call is no longer on its way", 0, viewed.waiting())
        assertFalse(viewed.sending("b.kt"))
    }

    @Test
    fun `once said, it stays while any call is out, even a young one behind an answered call, and ends with the last`() {
        val viewed = slow(host(viewedSync))
        viewed.fold(git, pr, listOf(a, b))
        viewed.send(git, mapOf("a.kt" to true)) {}
        now = 600
        assertEquals(1, viewed.waiting())
        now = 2_900
        viewed.send(git, mapOf("b.kt" to true)) {}
        now = 3_000
        held.removeAt(0)()
        assertEquals("b.kt is 0.1 s old but the line was already up: no blink", 1, viewed.waiting())
        held.removeAt(0)()
        assertEquals(0, viewed.waiting())
        viewed.send(git, mapOf("a.kt" to false)) {}
        now = 3_100
        assertEquals("a fresh quick call after the last one ended doesn't show", 0, viewed.waiting())
    }

    @Test
    fun `a call for a review since left answers no one here, and counts nowhere`() {
        val viewed = slow(host(viewedSync))
        viewed.fold(git, pr, listOf(a, b))
        val outcomes = mutableListOf<Map<String, Boolean>?>()
        viewed.send(git, mapOf("a.kt" to true)) { outcomes += it }
        answer = ViewedStates("PR_2", mapOf("a.kt" to ViewedState.UNVIEWED))
        viewed.fold(git, pr.copy(target = ReviewTarget.forPr(2, "t", "h", "origin/main")), listOf(a))
        now = 600
        assertEquals(0, viewed.waiting())
        assertFalse(viewed.sending("a.kt"))
        held.removeAt(0)()
        assertEquals("the new review's queue is never confirmed by the old call", emptyList<Map<String, Boolean>?>(), outcomes)
    }

    @Test
    fun `an older call the host took never confirms a tick flipped again since, so a later refusal keeps it queued`() {
        val viewed = slow(host(viewedSync))
        viewed.fold(git, pr, listOf(a, b))
        val outcomes = mutableListOf<Map<String, Boolean>?>()
        // tick, untick, tick a.kt, and tick b.kt once, all before the host answers
        listOf(mapOf("a.kt" to true), mapOf("a.kt" to false), mapOf("a.kt" to true, "b.kt" to true)).forEach { viewed.send(git, it) { outcomes += it } }
        held.removeAt(0)()
        held.removeAt(0)()
        takes = false
        held.removeAt(0)()
        assertEquals("only the last call speaks for a.kt, and it was refused", listOf(emptyMap<String, Boolean>(), emptyMap(), null), outcomes)
        assertEquals(mapOf("a.kt" to true, "b.kt" to true), confirmed(mapOf("a.kt" to true, "b.kt" to true), outcomes.filterNotNull().fold(emptyMap()) { m, o -> m + o }))
    }

    @Test
    fun `a call the host took confirms the ticks no later call carries`() {
        val viewed = slow(host(viewedSync))
        viewed.fold(git, pr, listOf(a, b))
        val outcomes = mutableListOf<Map<String, Boolean>?>()
        viewed.send(git, mapOf("a.kt" to true, "b.kt" to true)) { outcomes += it }
        viewed.send(git, mapOf("b.kt" to false)) { outcomes += it }
        held.removeAt(0)()
        held.removeAt(0)()
        assertEquals(listOf(mapOf("a.kt" to true), mapOf("b.kt" to false)), outcomes)
    }

    @Test
    fun `a call for a review since left that fails never marks the review under way failed`() {
        val viewed = slow(host(viewedSync))
        viewed.fold(git, pr, listOf(a, b))
        viewed.send(git, mapOf("a.kt" to true)) {}
        answer = ViewedStates("PR_2", mapOf("a.kt" to ViewedState.UNVIEWED))
        viewed.fold(git, pr.copy(target = ReviewTarget.forPr(2, "t", "h", "origin/main")), listOf(a))
        takes = false
        held.removeAt(0)()
        assertEquals(HostViewed.Status.HOST, viewed.status)
        assertEquals(emptyList<String>(), warned)
    }
}

package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The review's Viewed ticks on the host: folded in at load, sent one by one, and a failure said once. The merge rules are spec/cases/viewed-*.json. */
class HostViewedTest {
    private val git = GitClient(Files.createTempDirectory("review-lens-viewed"))
    private val warned = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private var answer: ViewedStates? = ViewedStates("PR_1", mapOf("a.kt" to ViewedState.VIEWED, "b.kt" to ViewedState.UNVIEWED))
    private var takes = true

    private val viewedSync = object : ViewedSync {
        override fun viewedStates(number: Int) = answer ?: throw CliException("offline")
        override fun setViewed(reviewId: String, path: String, viewed: Boolean) { sent += "$reviewId $path $viewed"; if (!takes) throw CliException("offline") }
    }
    private fun host(viewed: ViewedSync?) = object : ReviewHost {
        override val name = "TestHost"
        override fun openPullRequests() = emptyList<PullRequest>()
        override fun headRef(number: Int) = ""
        override fun reviewThreads(number: Int) = emptyList<ReviewThread>()
        override fun reviewSlot(number: Int): ReviewSlot = error("unused")
        override fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>) = Unit
        override fun checks(number: Int) = Checks(ChecksState.NONE)
        override fun resolveThread(threadId: String, resolved: Boolean) = Unit
        override fun editComment(commentId: String, body: String) = Unit
        override fun deleteComment(commentId: String) = Unit
        override val viewed = viewed
    }

    private fun synced(host: ReviewHost?) = HostViewed({ host }, { it() }, { title, _ -> warned += title })
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
        val outcomes = mutableListOf<Boolean>()
        viewed.send(git, "a.kt", true) { outcomes += it }
        assertEquals("nothing goes before the host answered at load", emptyList<String>(), sent)
        viewed.fold(git, pr, listOf(a, b))
        viewed.send(git, "a.kt", true) { outcomes += it }
        takes = false
        viewed.send(git, "b.kt", true) { outcomes += it }
        viewed.send(git, "b.kt", false) { outcomes += it }
        assertEquals(listOf("PR_1 a.kt true", "PR_1 b.kt true", "PR_1 b.kt false"), sent)
        assertEquals(listOf(true, false, false), outcomes)
        assertEquals(HostViewed.Status.FAILED, viewed.status)
        assertEquals(1, warned.size)
    }
}

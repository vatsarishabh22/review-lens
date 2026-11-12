package io.github.vatsarishabh22.reviewlens

import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The review's Viewed ticks on the host (README, Viewed sync): the host's answer folded in at load, each tick sent as
 * it is made. A host that keeps no Viewed checkboxes (GitLab) leaves the ticks on this laptop; a failed sync keeps them
 * here, queued in [ReviewMeta.unsent], and says so once per session. The ticks themselves are [ReviewService]'s.
 */
@Service(Service.Level.PROJECT)
class HostViewed internal constructor(
    private val hostOf: (GitClient) -> ReviewHost?,
    private val pool: (() -> Unit) -> Unit,
    private val warn: (title: String, text: String) -> Unit,
    /** Milliseconds on a clock that never jumps, for how long a call has waited. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    constructor(project: Project) : this(
        hostOf = ReviewHosts::of,
        // one at a time, in click order: a tick and an untick sent close together must reach the host as made
        pool = AppExecutorUtil.createBoundedApplicationPoolExecutor("Review Lens viewed sync", 1).let { serial -> { serial.execute(it) } },
        warn = { title, text -> notify(project, title, text, NotificationType.WARNING) },
    )

    /** Where the checklist lives right now, for the panel's one-line status. */
    enum class Status(private val words: String) {
        HOST("Synced with %s"), LOCAL("Viewed kept on this laptop"), FAILED("%s sync failed · kept on this laptop");
        fun text(host: String) = words.format(host)
    }

    @Volatile var status = Status.LOCAL; private set
    /** The host's id for the review, known once it answered at load: ticks go to it. */
    @Volatile private var reviewId: String? = null
    /** False once the host is known to keep no Viewed checkboxes: a tick is then local only, never queued. */
    @Volatile private var hostKeepsViewed = true
    /** The review's files on the host, read at load; null when unknown (a branch, or the host didn't answer). */
    @Volatile private var hostFiles: Set<String>? = null
    private val failureShown = AtomicBoolean()

    /** What a load heard from the host: kept by [adopt] only once that load is the newest, so a late one changes nothing. */
    data class View(val reviewId: String?, val hostFiles: Set<String>?, val keepsViewed: Boolean, val status: Status, val failure: Failure? = null)

    /** Why [host] couldn't be reached: said once per session, by the load that is kept. */
    data class Failure(val host: String, val error: Throwable)
    /** A call made and not answered yet: the review it is for, the paths it carries, when it was made. */
    private data class Call(val reviewId: String, val paths: Set<String>, val made: Long)
    /** This review's unanswered calls, oldest first; [shown] once one waited [SHOW_SENDING_MS], until none is left. */
    private val inFlight = ArrayDeque<Call>()
    private var shown = false

    /**
     * At load: [meta] with the host's ticks folded in (the host wins for its files), and what the host said, for [adopt];
     * a branch review stays local. Keeps nothing itself. Runs the host's CLI: off EDT.
     */
    fun answer(git: GitClient, meta: ReviewMeta, changes: List<FileChange>): Pair<ReviewMeta, View> {
        val local = View(null, null, keepsViewed = true, Status.LOCAL)
        val number = meta.target.prNumber ?: return meta to local
        val host = hostOf(git)
        val keeps = host == null || host.viewed != null // unknown while no host answers: ticks queue for when one does
        val viewed = host?.viewed
        if (host != null && viewed == null) return meta to local.copy(keepsViewed = false) // a host with no Viewed checkboxes
        return runCatching { (viewed ?: throw ReviewHosts.notSignedIn()).viewedStates(number) }.fold(
            onSuccess = { states ->
                val synced = syncViewed(meta, states.files, changes) { ticks -> runCatching { viewed!!.setViewed(states.pullRequestId, ticks) }.isSuccess }
                synced to View(states.pullRequestId, states.files.keys, keeps, if (synced.unsent == null) Status.HOST else Status.FAILED)
            },
            onFailure = { meta to View(null, null, keeps, Status.FAILED, Failure(ReviewHosts.nameOf(git), it)) },
        )
    }

    /** Keeps what the newest load heard ([answer]). */
    fun adopt(view: View) {
        reviewId = view.reviewId
        hostFiles = view.hostFiles
        hostKeepsViewed = view.keepsViewed
        status = view.status
        view.failure?.let(::warnOnce)
    }

    /**
     * Whether [change]'s tick goes to the host: a file the host lists, or any file while the list is unknown (the next
     * load sorts it out). A scope file the review's net change doesn't include has no Viewed box there.
     */
    fun sends(meta: ReviewMeta?, change: FileChange): Boolean = meta?.target?.reviewable == true && hostKeepsViewed && hostFiles?.contains(change.path) != false

    /**
     * Sends [ticks] off EDT in one call. Nothing goes before the host answered at load. [done] hears the ticks the host
     * took that may leave the queue, or null when it refused them: calls go one at a time in click order, so a path in a
     * call still out was flipped again since, and only that later call can confirm what is queued for it.
     */
    fun send(git: GitClient, ticks: Map<String, Boolean>, done: (Map<String, Boolean>?) -> Unit) {
        val id = reviewId ?: return
        val call = Call(id, ticks.keys, clock())
        synchronized(inFlight) { inFlight.addLast(call) }
        pool {
            val sent = runCatching { ((hostOf(git) ?: throw ReviewHosts.notSignedIn()).viewed ?: throw CliException("This host keeps no Viewed checkboxes")).setViewed(id, ticks) }
            val later = synchronized(inFlight) { inFlight.remove(call); current().flatMap { it.paths }.toSet() }
            // a call for a review since left only answers that review's next load: its ticks stay in its unsent queue,
            // and its failure is that review's, never the status of the one under way
            if (id == reviewId) {
                sent.onFailure { status = Status.FAILED; warnOnce(Failure(ReviewHosts.nameOf(git), it)) }
                done(if (sent.isSuccess) ticks - later else null)
            }
        }
    }

    /** Whether [path]'s tick is still on its way to the host, in this review. */
    fun sending(path: String) = synchronized(inFlight) { current().any { path in it.paths } }

    /**
     * The distinct files whose ticks are on their way, for the panel's "Sending n…": said once a call has waited
     * [SHOW_SENDING_MS], so a quick answer never flickers, and kept until none is left, so a young call behind an
     * answered one doesn't blink it off; 0 otherwise.
     */
    fun waiting(): Int = synchronized(inFlight) {
        val calls = current()
        shown = calls.isNotEmpty() && (shown || clock() - calls.first().made >= SHOW_SENDING_MS)
        if (shown) calls.flatMap { it.paths }.toSet().size else 0
    }

    private fun current() = inFlight.filter { it.reviewId == reviewId }

    /** Says once per session that the ticks stay on the laptop, so a failed sync is never silent. */
    private fun warnOnce(failure: Failure) {
        if (!failureShown.compareAndSet(false, true)) return // the pool's send and a load can fail at once
        thisLogger().info("Host viewed sync failed", failure.error)
        val name = failure.host
        warn("Can't reach $name right now", "Your viewed ticks are kept on this laptop and sent when $name is back.")
    }

    companion object {
        const val SHOW_SENDING_MS = 500L

        fun of(project: Project): HostViewed = project.service()
    }
}

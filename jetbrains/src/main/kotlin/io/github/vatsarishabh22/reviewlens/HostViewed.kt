package io.github.vatsarishabh22.reviewlens

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

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
) {
    constructor(project: Project) : this(
        hostOf = ReviewHosts::of,
        pool = { ApplicationManager.getApplication().executeOnPooledThread(it) },
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
    private var failureShown = false

    /** At load: [meta] with the host's ticks folded in (the host wins for its files); a branch review stays local. Runs the host's CLI: off EDT. */
    fun fold(git: GitClient, meta: ReviewMeta, changes: List<FileChange>): ReviewMeta {
        hostFiles = null
        val number = meta.target.prNumber ?: return local(meta)
        val host = hostOf(git)
        hostKeepsViewed = host == null || host.viewed != null // unknown while no host answers: ticks queue for when one does
        val viewed = host?.viewed
        if (host != null && viewed == null) return local(meta) // a host with no Viewed checkboxes
        return runCatching { (viewed ?: throw ReviewHosts.notSignedIn()).viewedStates(number) }.fold(
            onSuccess = { states ->
                reviewId = states.pullRequestId
                hostFiles = states.files.keys
                syncViewed(meta, states.files, changes) { path, ticked -> runCatching { viewed!!.setViewed(states.pullRequestId, path, ticked) }.isSuccess }
                    .also { status = if (it.unsent == null) Status.HOST else Status.FAILED }
            },
            onFailure = { failed(git, it); meta },
        )
    }

    /**
     * Whether [change]'s tick goes to the host: a file the host lists, or any file while the list is unknown (the next
     * load sorts it out). A scope file the review's net change doesn't include has no Viewed box there.
     */
    fun sends(meta: ReviewMeta?, change: FileChange): Boolean = meta?.target?.reviewable == true && hostKeepsViewed && hostFiles?.contains(change.path) != false

    /** Sends one tick off EDT; [done] hears whether the host took it. Nothing goes before the host answered at load. */
    fun send(git: GitClient, path: String, ticked: Boolean, done: (Boolean) -> Unit) {
        val id = reviewId ?: return
        pool {
            runCatching { ((hostOf(git) ?: throw ReviewHosts.notSignedIn()).viewed ?: throw CliException("This host keeps no Viewed checkboxes")).setViewed(id, path, ticked) }
                .onSuccess { done(true) }
                .onFailure { failed(git, it); done(false) }
        }
    }

    private fun local(meta: ReviewMeta) = meta.also { status = Status.LOCAL; reviewId = null }

    /** Says once per session that the ticks stay on the laptop, so a failed sync is never silent. */
    private fun failed(git: GitClient, error: Throwable) {
        status = Status.FAILED
        if (failureShown) return
        failureShown = true
        thisLogger().info("Host viewed sync failed", error)
        val name = ReviewHosts.nameOf(git)
        warn("Can't reach $name right now", "Your viewed ticks are kept on this laptop and sent when $name is back.")
    }

    companion object {
        fun of(project: Project): HostViewed = project.service()
    }
}

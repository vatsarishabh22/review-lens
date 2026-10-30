package io.github.vatsarishabh22.reviewlens

import io.github.vatsarishabh22.reviewlens.github.GhClient
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The code host a review talks to: its open reviews, threads and one-call Finish (spec/README.md; GitLab's is
 * spec/gitlab.md). [GhClient] is GitHub's. Everything else in a review is plain git and never asks the host.
 * Calls run a CLI and block: call off EDT.
 */
interface ReviewHost {
    /** How the UI names the host: "Submit to GitHub". */
    val name: String

    fun openPullRequests(): List<PullRequest>

    /** The refspec source that fetches review [number]'s head (`pull/7/head` on GitHub). */
    fun headRef(number: Int): String

    fun reviewThreads(number: Int): List<ReviewThread>

    /** What the host will take on review [number] now; read again each time Finish opens. */
    fun reviewSlot(number: Int): ReviewSlot

    /** Posts [comments], [replies], [summary] and [verdict] as one review at [commit]; a refusal undoes what it added and throws the host's reason. */
    fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>)

    fun checks(number: Int): Checks

    /** Resolves thread [threadId], or reopens it, on the host at once; a refusal throws the host's reason. */
    fun resolveThread(threadId: String, resolved: Boolean)

    /** Replaces posted comment [commentId]'s text, or deletes it, on the host at once; a refusal throws the host's reason. */
    fun editComment(commentId: String, body: String)
    fun deleteComment(commentId: String)

    /** The host's own "Viewed" checkboxes, or null where it keeps none to sync with (GitLab): ticks then stay on this laptop. */
    val viewed: ViewedSync?
}

/**
 * The host's answer to "what will you take on this review now": the rules, their data and their words are the host's
 * own (GitHub's: [GitHubSlot]). Pure, so the Finish popup can ask on every keystroke on EDT.
 */
interface ReviewSlot {
    /** Comments the viewer began on the host's website, which Finish posts with theirs. */
    val started: Int

    /** Why [verdict] can't end this review whatever is written (greys its choice), or null. */
    fun denied(verdict: Verdict): String?

    /** Why the [outbox] can't post from a scope ending at [scopeEnd] whatever the verdict (said in place of "will be posted"), or null. */
    fun held(outbox: Outbox, scopeEnd: String): String?

    /** Why the host won't take this review as it is now (greys Submit), or null. */
    fun refusal(verdict: Verdict, summary: String, outbox: Outbox, scopeEnd: String): String?
}

enum class ChecksState { NONE, RUNNING, FAILING, PASSED }

data class Checks(val state: ChecksState, val failing: Int = 0) {
    val text get() = when (state) {
        ChecksState.NONE -> ""
        ChecksState.RUNNING -> "Checks running"
        ChecksState.FAILING -> if (failing == 1) "1 check failing" else "$failing checks failing"
        ChecksState.PASSED -> "Checks passed"
    }
}

/** GitHub's own per-viewer state of a PR file; DISMISSED = "new changes since last viewed" (our 🔄). */
enum class ViewedState { VIEWED, UNVIEWED, DISMISSED }

data class ViewedStates(val pullRequestId: String, val files: Map<String, ViewedState>)

/** A host's per-viewer "Viewed" checkboxes on a review's files. */
interface ViewedSync {
    fun viewedStates(number: Int): ViewedStates
    fun setViewed(reviewId: String, path: String, viewed: Boolean)
}

/** Someone on the host as the UI shows them: [name] in text, [login] seeds their avatar. */
data class Person(val login: String, val name: String)

/**
 * An open review on the host in the host's neutral words; each host maps its own answer to it. [head] is the branch
 * under review and [headSha] its commit on the host now (a review whose head moved on shows "New commits since your
 * review"); [requested]: the host asks the viewer to review it ("Waiting on you").
 */
data class PullRequest(
    val number: Int, val title: String, val head: String, val headSha: String, val base: String, val author: Person,
    val body: String = "", val additions: Int = 0, val deletions: Int = 0, val changedFiles: Int = 0, val updatedAt: String = "",
    val requested: Boolean = false,
)

/**
 * Picks a checkout's host as spec/gitlab.md says: `origin`'s host, then the CLI signed in to it. GitHub is the only
 * one yet; none means the branch list. Remembered per checkout; the PR list picks again on every load, so signing in
 * or out shows there.
 */
object ReviewHosts {
    private val picked = ConcurrentHashMap<Path, Picked>()
    private class Picked(val host: ReviewHost?)

    fun of(git: GitClient): ReviewHost? = picked.getOrPut(git.root) { Picked(pick(git)) }.host

    /**
     * The picked host's [ReviewHost.name] for UI text, read without picking (picking runs `ssh`/`gh`, never on EDT). The PR
     * list and a restored review pick first, so the fallback shows only before either has run.
     */
    fun nameOf(git: GitClient?): String = git?.let { picked[it.root]?.host?.name } ?: "your code host"

    /** [of] for a call that can't go on without the host; the balloon's title already says which call. */
    fun required(git: GitClient): ReviewHost = of(git) ?: throw CliException("Sign in to this repository's host")

    fun refresh(git: GitClient): ReviewHost? = Picked(pick(git)).also { picked[git.root] = it }.host

    private fun pick(git: GitClient): ReviewHost? {
        val remote = remoteHost(git.originUrl() ?: return null) ?: return null
        val host = if (remote.ssh) sshHostname(git.root, remote.name) else remote.name
        return GhClient(git.root).takeIf { it.signedInTo(host) }
    }

    /** What ssh connects to for [alias]: an `~/.ssh/config` Host alias (`github-personal`) names its real HostName. */
    private fun sshHostname(root: Path, alias: String): String =
        runCatching { parseSshHostname(runCli(root, "ssh", "-G", alias)) }.getOrNull() ?: alias
}

/** A remote's host [name], and whether it is reached over SSH (so it may be an `~/.ssh/config` alias). */
data class RemoteHost(val name: String, val ssh: Boolean)

/** The host in a remote URL: `https://`, `ssh://` and scp-like `git@host:owner/repo`; null for a local path or `file://`. */
fun remoteHost(url: String): RemoteHost? {
    // file:///path has no host to match; a scp-like URL is `host:path` with no slash after the colon (C:/ is a path)
    Regex("^([a-z+]+)://(?:[^@/]*@)?([^/:]+)").find(url)?.let { return RemoteHost(it.groupValues[2], it.groupValues[1].contains("ssh")) }
    return Regex("^(?:[^@/]+@)?([^/:]+):(?!/)").find(url)?.let { RemoteHost(it.groupValues[1], true) }
}

/** The `hostname` line of `ssh -G` output. */
fun parseSshHostname(output: String): String? =
    output.lineSequence().firstOrNull { it.startsWith("hostname ") }?.removePrefix("hostname ")?.trim()?.ifEmpty { null }

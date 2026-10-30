package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

data class GhUser(val login: String, val name: String?) {
    val displayName get() = name?.ifBlank { null } ?: login
}

data class ReviewRequest(val login: String?)

data class PullRequest(
    val number: Int,
    val title: String,
    val headRefName: String,
    /** The PR's head commit on GitHub now: a finished review whose head moved on shows "New commits since your review". */
    val headRefOid: String = "",
    val baseRefName: String,
    val author: GhUser,
    val body: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val changedFiles: Int = 0,
    val updatedAt: String = "",
    val reviewRequests: List<ReviewRequest> = emptyList(),
)

enum class ChecksState { NONE, RUNNING, FAILING, PASSED }

data class Checks(val state: ChecksState, val failing: Int = 0) {
    val text get() = when (state) {
        ChecksState.NONE -> ""
        ChecksState.RUNNING -> "Checks running"
        ChecksState.FAILING -> if (failing == 1) "1 check failing" else "$failing checks failing"
        ChecksState.PASSED -> "Checks passed"
    }
}

/** GitHub through the `gh` CLI, the host of a checkout whose `origin` is on a host `gh` is signed in to ([ReviewHosts]). */
class GhClient(private val root: Path) : ReviewHost, ViewedSync {
    override val name = "GitHub"

    /** `gh` is signed in to [host] (github.com, or a GitHub Enterprise server). */
    fun signedInTo(host: String): Boolean = runCatching { runCli(root, "gh", "auth", "status", "--hostname", host) }.isSuccess

    override val viewed: ViewedSync get() = this

    override fun headRef(number: Int) = "pull/$number/head"

    override fun viewerLogin(): String = runCli(root, "gh", "api", "user", "--jq", ".login")

    /** Open PRs without their checks: checks double the call's time (2.65 s vs 5.43 s for 70 PRs) and only the detail shows them. */
    override fun openPullRequests(): List<PullRequest> {
        val json = runCli(root, "gh", "pr", "list", "--state", "open", "--limit", "100", "--json",
            "number,title,headRefName,headRefOid,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests")
        return Gson().fromJson(json, Array<PullRequest>::class.java).toList()
    }

    /** This viewer's GitHub "Viewed" ticks for PR [number], every page; owner and repo come from the checkout's remote. */
    override fun viewedStates(number: Int): ViewedStates = parseViewedStates(runCli(root, "gh", "api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$VIEWED_QUERY", "--jq", VIEWED_JQ))

    /** Everyone's review threads on PR [number], every page. */
    override fun reviewThreads(number: Int): List<ReviewThread> = parseReviewThreads(runCli(root, "gh", "api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$REVIEW_THREADS_QUERY", "--jq", REVIEW_THREADS_JQ))

    /** Ticks or unticks one file on GitHub (private to the viewer; nobody is notified). */
    override fun setViewed(reviewId: String, path: String, viewed: Boolean) {
        val mutation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
        runCli(root, "gh", "api", "graphql", "-f", "id=$reviewId", "-f", "path=$path",
            "-f", "query=mutation(\$id:ID!,\$path:String!){$mutation(input:{pullRequestId:\$id,path:\$path}){clientMutationId}}")
    }

    /** The PR's id and latest push, and your own pending review on it (started on github.com or anywhere else), if any. */
    override fun reviewSlot(number: Int): GitHubSlot = parseReviewSlot(runCli(root, "gh", "api", "graphql", "-F", "owner={owner}", "-F", "name={repo}",
        "-F", "number=$number", "-f", "query=$REVIEW_SLOT_QUERY", "--jq", REVIEW_SLOT_JQ))

    /**
     * Posts one review: the new [comments] and the [replies] go into your pending review, which is then submitted with
     * [verdict] and [summary]. GitHub keeps one pending review per person per PR, so one started on github.com is
     * adopted (its comments post too) rather than refused. A refusal at any step undoes only what this call added, so
     * nothing posts by halves and nothing written on github.com is lost, and throws GitHub's reason.
     */
    override fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>) {
        val slot = reviewSlot(number)
        slot.refusal(verdict, summary, Outbox(comments, replies), commit)?.let { throw CliException(it) }
        val adopted = slot.pending?.id
        val review = adopted ?: graphql(startReviewBody(slot.id, commit, comments), ".data.addPullRequestReview.pullRequestReview.id")
        val added = mutableListOf<String>()
        try {
            if (adopted != null) comments.forEach { added += graphql(addThreadBody(review, it), ".data.addPullRequestReviewThread.thread.comments.nodes[0].id") }
            replies.forEach { added += graphql(replyBody(review, it), ".data.addPullRequestReviewThreadReply.comment.id") }
            graphql(submitBody(review, verdict, summary), ".data.submitPullRequestReview.pullRequestReview.id")
        } catch (e: CliException) {
            if (adopted == null) runCatching { graphql(deleteReviewBody(review), ".data.deletePullRequestReview.pullRequestReview.id") }
            else added.forEach { runCatching { graphql(deleteCommentBody(it), ".data.deletePullRequestReviewComment.pullRequestReview.id") } }
            throw e
        }
    }

    override fun resolveThread(threadId: String, resolved: Boolean) {
        val field = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        graphql(resolveBody(threadId, resolved), ".data.$field.thread.id")
    }

    override fun editComment(commentId: String, body: String) {
        graphql(editCommentBody(commentId, body), ".data.updatePullRequestReviewComment.pullRequestReviewComment.id")
    }

    override fun deleteComment(commentId: String) {
        graphql(deleteCommentBody(commentId), ".data.deletePullRequestReviewComment.pullRequestReview.id")
    }

    /** Sends one GraphQL [body] and returns what [jq] picks from the answer; a refusal throws with GitHub's reason. */
    private fun graphql(body: String, jq: String): String {
        val file = Files.createTempFile("review-lens-graphql", ".json")
        try {
            Files.writeString(file, body)
            return runCli(root, "gh", "api", "graphql", "--input", file.toString(), "--jq", jq).ifBlank { null }?.takeUnless { it == "null" }
                ?: throw CliException("GitHub didn't answer")
        } catch (e: CliException) {
            throw CliException(githubRefusal(e.stdout) ?: e.stderr.ifBlank { "GitHub didn't answer" })
        } finally {
            Files.deleteIfExists(file)
        }
    }

    override fun checks(number: Int): Checks =
        summarizeChecks(runCli(root, "gh", "pr", "view", number.toString(), "--json", "statusCheckRollup"))
}

/** Your pending review on a PR: GitHub keeps one per person, so Finish review adds to it rather than starting another. */
data class PendingReview(val id: String, val comments: Int)

/** GitHub's [ReviewSlot]: the PR's id, its latest push ([head]), your pending review if one exists, and whether you opened the PR. */
data class GitHubSlot(val id: String, val head: String, val pending: PendingReview?, val ownPullRequest: Boolean) : ReviewSlot {
    override val started get() = pending?.comments ?: 0

    /** GitHub refuses Approve and Request changes on your own pull request. */
    override fun denied(verdict: Verdict) =
        if (ownPullRequest && verdict != Verdict.COMMENT) "GitHub doesn't let you approve or request changes on your own pull request." else null

    /**
     * New comments can't join [pending] from a scope ending before [head]: what is added to a pending review lands on the
     * PR's latest push whatever line it was written for (measured on rl-thread-test#1). Replies sit on their threads and always can.
     */
    override fun held(outbox: Outbox, scopeEnd: String) = if (pending == null || outbox.comments.isEmpty() || scopeEnd == head) null
        else "You have a review in progress on GitHub, and comments added to it land on the latest push. Review up to the latest push to add yours, or submit that one on GitHub first."

    /** Then [denied], then GitHub's 422 on an empty Comment or Request changes (a blank reason, measured on PR #1). */
    override fun refusal(verdict: Verdict, summary: String, outbox: Outbox, scopeEnd: String) = held(outbox, scopeEnd) ?: denied(verdict)
        ?: if (verdict != Verdict.APPROVE && summary.isBlank() && outbox.size + started == 0) "Add a summary or an inline comment to post a ${verdict.label.lowercase()} review." else null
}

/** Reads `PR_id<TAB>head<TAB>pendingId<TAB>comments<TAB>own`; pendingId is empty and comments 0 when there is no pending review. */
fun parseReviewSlot(text: String): GitHubSlot {
    val (pr, head, pending, comments, own) = text.trim().split('\t').let { it + List(5 - it.size) { "" } }
    require(pr.isNotEmpty() && head.isNotEmpty()) { "GitHub did not return this pull request" }
    return GitHubSlot(pr, head, pending.ifEmpty { null }?.let { PendingReview(it, comments.toIntOrNull() ?: 0) }, own == "true")
}

// reviews(states: PENDING) lists only the viewer's own: nobody else's pending review is visible
private const val REVIEW_SLOT_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number)" +
    "{id headRefOid viewerDidAuthor reviews(states:PENDING,first:1){nodes{id comments{totalCount}}}}}}"
private const val REVIEW_SLOT_JQ = ".data.repository.pullRequest | [.id, .headRefOid, (.reviews.nodes[0].id // \"\"), (.reviews.nodes[0].comments.totalCount // 0 | tostring), (.viewerDidAuthor | tostring)] | @tsv"

/** GitHub's own per-viewer state of a PR file; DISMISSED = "new changes since last viewed" (our 🔄). */
enum class ViewedState { VIEWED, UNVIEWED, DISMISSED }

data class ViewedStates(val pullRequestId: String, val files: Map<String, ViewedState>)

private const val VIEWED_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){id files(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{path viewerViewedState}}}}}"
private const val VIEWED_JQ = ".data as \$d | \$d.repository.pullRequest as \$p | (\"#id\\t\" + \$p.id), " +
    "(\$p.files.nodes[] | \"\\(.path)\\t\\(.viewerViewedState)\")"

/** Reads `#id<TAB>PR_…`, then `path<TAB>STATE` lines (the `#` lines repeat per page). */
fun parseViewedStates(text: String): ViewedStates {
    var id = ""
    val files = LinkedHashMap<String, ViewedState>()
    text.lines().filter { '\t' in it }.forEach { line ->
        val (key, value) = line.split('\t', limit = 2)
        when (key) {
            "#id" -> id = value
            else -> runCatching { ViewedState.valueOf(value) }.getOrNull()?.let { files[key] = it }
        }
    }
    require(id.isNotEmpty()) { "GitHub did not return this pull request" }
    return ViewedStates(id, files)
}

/** GitHub's reason from an error body (`{"message":"Unprocessable Entity","errors":["Path could not be resolved"]}`), or null. */
fun githubRefusal(body: String): String? {
    val json = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
    val errors = (json.get("errors") as? JsonArray)?.map { if (it.isJsonObject) it.asJsonObject.get("message")?.asString.orEmpty() else it.asString }
        ?.filter { it.isNotBlank() }.orEmpty()
    return errors.joinToString("; ").ifEmpty { json.get("message")?.asString }
}

private val FAILED = setOf("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")
private val RUNNING = setOf("PENDING", "EXPECTED", "QUEUED", "IN_PROGRESS", "WAITING", "REQUESTED")

/**
 * One line for a PR's checks from `gh pr view --json statusCheckRollup`. GitHub mixes two kinds: check runs carry
 * `status` + `conclusion`, commit statuses carry `state`. Any failure wins over running, running over passed.
 */
fun summarizeChecks(json: String): Checks {
    val rollup = Gson().fromJson(json, JsonObject::class.java).get("statusCheckRollup") as? JsonArray ?: return Checks(ChecksState.NONE)
    val outcomes = rollup.map { it.asJsonObject }.map { check ->
        fun field(name: String) = check.get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val status = field("status")
        when {
            status.isNotEmpty() && status != "COMPLETED" -> "PENDING"
            else -> field("conclusion").ifEmpty { field("state") }
        }
    }
    val failing = outcomes.count { it in FAILED }
    return when {
        outcomes.isEmpty() -> Checks(ChecksState.NONE)
        failing > 0 -> Checks(ChecksState.FAILING, failing)
        outcomes.any { it in RUNNING } -> Checks(ChecksState.RUNNING)
        else -> Checks(ChecksState.PASSED)
    }
}

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

/** The `gh` CLI, used only when it is installed and logged in. */
class GhClient(private val root: Path) {

    fun isReady(): Boolean = runCatching { runCli(root, "gh", "auth", "status") }.isSuccess

    fun viewerLogin(): String = runCli(root, "gh", "api", "user", "--jq", ".login")

    /** Open PRs without their checks: checks double the call's time (2.65 s vs 5.43 s for 70 PRs) and only the detail shows them. */
    fun openPullRequests(): List<PullRequest> {
        val json = runCli(root, "gh", "pr", "list", "--state", "open", "--limit", "100", "--json",
            "number,title,headRefName,headRefOid,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests")
        return Gson().fromJson(json, Array<PullRequest>::class.java).toList()
    }

    /** This viewer's GitHub "Viewed" ticks for PR [number], every page; owner and repo come from the checkout's remote. */
    fun viewedStates(number: Int): ViewedStates = parseViewedStates(runCli(root, "gh", "api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$VIEWED_QUERY", "--jq", VIEWED_JQ))

    /** Everyone's review threads on PR [number], every page. */
    fun reviewThreads(number: Int): List<ReviewThread> = parseReviewThreads(runCli(root, "gh", "api", "graphql", "--paginate",
        "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number", "-f", "query=$REVIEW_THREADS_QUERY", "--jq", REVIEW_THREADS_JQ))

    /** Ticks or unticks one file on GitHub (private to the viewer; nobody is notified). */
    fun setViewed(pullRequestId: String, path: String, viewed: Boolean) {
        val mutation = if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"
        runCli(root, "gh", "api", "graphql", "-f", "id=$pullRequestId", "-f", "path=$path",
            "-f", "query=mutation(\$id:ID!,\$path:String!){$mutation(input:{pullRequestId:\$id,path:\$path}){clientMutationId}}")
    }

    /**
     * Posts one review: a pending review with the new [comments], each of the [replies] added to it, then submitted with
     * [verdict] and [summary]. A refusal at any step deletes the pending review, so nothing posts by halves, and throws GitHub's reason.
     */
    fun submitReview(number: Int, commit: String, summary: String, verdict: Verdict, comments: List<Draft>, replies: List<Draft>) {
        val review = graphql(startReviewBody(pullRequestId(number), commit, comments), ".data.addPullRequestReview.pullRequestReview.id")
        try {
            replies.forEach { graphql(replyBody(review, it), ".data.addPullRequestReviewThreadReply.comment.id") }
            graphql(submitBody(review, verdict, summary), ".data.submitPullRequestReview.pullRequestReview.id")
        } catch (e: CliException) {
            runCatching { graphql(deleteReviewBody(review), ".data.deletePullRequestReview.pullRequestReview.id") }
            throw e
        }
    }

    private fun pullRequestId(number: Int): String = runCli(root, "gh", "api", "graphql", "-F", "owner={owner}", "-F", "name={repo}", "-F", "number=$number",
        "-f", "query=query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number){id}}}",
        "--jq", ".data.repository.pullRequest.id")

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

    fun checks(number: Int): Checks =
        summarizeChecks(runCli(root, "gh", "pr", "view", number.toString(), "--json", "statusCheckRollup"))
}

/** GitHub's own per-viewer state of a PR file; DISMISSED = "new changes since last viewed" (our 🔄). */
enum class ViewedState { VIEWED, UNVIEWED, DISMISSED }

/** [ownPullRequest]: the viewer opened it, so GitHub allows only Comment reviews. */
data class ViewedStates(val pullRequestId: String, val files: Map<String, ViewedState>, val ownPullRequest: Boolean = false)

private const val VIEWED_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){viewer{login} repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){id author{login} files(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{path viewerViewedState}}}}}"
private const val VIEWED_JQ = ".data as \$d | \$d.repository.pullRequest as \$p | (\"#id\\t\" + \$p.id), " +
    "(\"#own\\t\" + ((\$p.author.login // \"\") == \$d.viewer.login | tostring)), (\$p.files.nodes[] | \"\\(.path)\\t\\(.viewerViewedState)\")"

/** Reads `#id<TAB>PR_…`, `#own<TAB>true|false`, then `path<TAB>STATE` lines (the `#` lines repeat per page). */
fun parseViewedStates(text: String): ViewedStates {
    var id = ""
    var own = false
    val files = LinkedHashMap<String, ViewedState>()
    text.lines().filter { '\t' in it }.forEach { line ->
        val (key, value) = line.split('\t', limit = 2)
        when (key) {
            "#id" -> id = value
            "#own" -> own = value == "true"
            else -> runCatching { ViewedState.valueOf(value) }.getOrNull()?.let { files[key] = it }
        }
    }
    require(id.isNotEmpty()) { "GitHub did not return this pull request" }
    return ViewedStates(id, files, own)
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

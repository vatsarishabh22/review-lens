package io.github.vatsarishabh22.reviewlens.github

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.vatsarishabh22.reviewlens.*

// GitHub's wire: what GhClient sends (GraphQL text, payloads, jq filters) and how it reads the answers into the
// host-neutral types. Nothing outside this package calls it.

/** `gh pr list --json` as GitHub spells it; only [parsePullRequests] reads it. A team review request has no login. */
private data class GhUser(val login: String, val name: String?)
private data class GhRequest(val login: String?)
private data class GhPullRequest(
    val number: Int, val title: String, val headRefName: String, val headRefOid: String, val baseRefName: String, val author: GhUser,
    val body: String?, val additions: Int, val deletions: Int, val changedFiles: Int, val updatedAt: String, val reviewRequests: List<GhRequest>?,
    val isCrossRepository: Boolean = false,
)

/**
 * `gh pr list --json` as [PullRequest]s. A PR waits on [viewer] when a review request names them.
 * ponytail: user requests only; a request to one of the viewer's teams lists under "Other open".
 */
fun parsePullRequests(json: String, viewer: String?): List<PullRequest> = Gson().fromJson(json, Array<GhPullRequest>::class.java).map {
    PullRequest(it.number, it.title, it.headRefName, it.headRefOid, it.baseRefName, Person(it.author.login, it.author.name?.ifBlank { null } ?: it.author.login),
        it.body.orEmpty(), it.additions, it.deletions, it.changedFiles, it.updatedAt, viewer != null && it.reviewRequests.orEmpty().any { r -> r.login == viewer },
        fromFork = it.isCrossRepository)
}

/**
 * Each open PR's commits (newest last), the stack rule's input (spec/README.md, Stacks). Its own call: `gh pr list --json
 * commits` asks for every commit's authors too, which GitHub refuses past 500,000 nodes (100 PRs did, measured).
 * ponytail: the last 100 commits of the newest 100 PRs; a parent further back is missed and the PR compares with its base.
 */
internal const val COMMIT_IDS_QUERY = "query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){pullRequests(states:OPEN,first:100,orderBy:{field:CREATED_AT,direction:DESC})" +
    "{nodes{number commits(last:100){nodes{commit{oid}}}}}}}"
internal const val COMMIT_IDS_JQ = ".data.repository.pullRequests.nodes[] | \"\\(.number)\\t\\([.commits.nodes[].commit.oid] | join(\",\"))\""

/** Reads `number<TAB>oid,oid,…` lines into each PR's commits. */
fun parseCommitIds(text: String): Map<Int, List<String>> = text.lines().filter { '\t' in it }.associate { line ->
    val (number, oids) = line.split('\t', limit = 2)
    number.toInt() to oids.split(',').filter { it.isNotEmpty() }
}

/** Reads `PR_id<TAB>head<TAB>pendingId<TAB>comments<TAB>own`; pendingId is empty and comments 0 when there is no pending review. */
fun parseReviewSlot(text: String): GitHubSlot {
    val (pr, head, pending, comments, own) = text.trim().split('\t').let { it + List(5 - it.size) { "" } }
    require(pr.isNotEmpty() && head.isNotEmpty()) { "GitHub did not return this pull request" }
    return GitHubSlot(pr, head, pending.ifEmpty { null }?.let { PendingReview(it, comments.toIntOrNull() ?: 0) }, own == "true")
}

// reviews(states: PENDING) lists only the viewer's own: nobody else's pending review is visible
internal const val REVIEW_SLOT_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number)" +
    "{id headRefOid viewerDidAuthor reviews(states:PENDING,first:1){nodes{id comments{totalCount}}}}}}"
internal const val REVIEW_SLOT_JQ = ".data.repository.pullRequest | [.id, .headRefOid, (.reviews.nodes[0].id // \"\"), (.reviews.nodes[0].comments.totalCount // 0 | tostring), (.viewerDidAuthor | tostring)] | @tsv"

internal const val VIEWED_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){id files(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{path viewerViewedState}}}}}"
internal const val VIEWED_JQ = ".data as \$d | \$d.repository.pullRequest as \$p | (\"#id\\t\" + \$p.id), " +
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

/** One GraphQL call as `gh api graphql --input` takes it. */
fun graphqlBody(query: String, variables: Map<String, Any?>): String = Gson().toJson(mapOf("query" to query, "variables" to variables))

/** Starts the reviewer's pending review at [commit] with the new [comments]; GitHub answers with the review's id. */
fun startReviewBody(pullRequestId: String, commit: String, comments: List<Draft>) = graphqlBody(START_REVIEW, mapOf("pr" to pullRequestId, "commit" to commit,
    "threads" to comments.map { mapOf("path" to it.path, "line" to it.line, "side" to "RIGHT", "body" to it.body) + range(it) }))
/** A comment on several lines also names its first line, on the same side. */
private fun range(comment: Draft) = comment.startLine?.let { mapOf("startLine" to it, "startSide" to "RIGHT") }.orEmpty()
fun replyBody(reviewId: String, reply: Draft) = graphqlBody(ADD_REPLY, mapOf("review" to reviewId, "thread" to reply.replyTo, "body" to reply.body))
fun submitBody(reviewId: String, verdict: Verdict, summary: String) = graphqlBody(SUBMIT_REVIEW, mapOf("review" to reviewId, "event" to githubEvent(verdict), "body" to summary.ifBlank { null }))
/** GitHub's own `PullRequestReviewEvent` for a verdict, spelled here so renaming a [Verdict] can't change what GitHub is sent. */
private fun githubEvent(verdict: Verdict) = when (verdict) { Verdict.COMMENT -> "COMMENT"; Verdict.APPROVE -> "APPROVE"; Verdict.REQUEST_CHANGES -> "REQUEST_CHANGES" }
fun deleteReviewBody(reviewId: String) = graphqlBody(DELETE_REVIEW, mapOf("review" to reviewId))
/** Adds a new comment to a pending review someone else started; GitHub anchors it at the PR's latest push (it takes no commit). */
fun addThreadBody(reviewId: String, comment: Draft) = graphqlBody(ADD_THREAD, mapOf("review" to reviewId, "path" to comment.path, "line" to comment.line, "body" to comment.body) + range(comment))
fun deleteCommentBody(commentId: String) = graphqlBody(DELETE_COMMENT, mapOf("comment" to commentId))
/** Replaces a posted comment's text on GitHub at once. */
fun editCommentBody(commentId: String, body: String) = graphqlBody(UPDATE_COMMENT, mapOf("comment" to commentId, "body" to body))
/**
 * Ticks (true) and unticks (false) files in one call, one aliased mutation each, the paths passed as variables. GitHub
 * runs them all and answers an error for any it refused, which fails the call.
 * ponytail: one call however many files; measured to 300 (2.5 s), split into chunks if a PR past that is ever refused.
 */
fun viewedBody(pullRequestId: String, ticks: Map<String, Boolean>): String {
    val calls = ticks.entries.mapIndexed { i, (_, viewed) -> "m$i:${if (viewed) "markFileAsViewed" else "unmarkFileAsViewed"}(input:{pullRequestId:\$id,path:\$p$i}){clientMutationId}" }
    return graphqlBody("mutation(\$id:ID!${ticks.keys.indices.joinToString("") { ",\$p$it:String!" }}){${calls.joinToString(" ")}}",
        mapOf("id" to pullRequestId) + ticks.keys.mapIndexed { i, path -> "p$i" to path })
}
/** Resolves a thread, or reopens it ([resolved] false), on GitHub at once: GitHub can't hold it in a pending review. */
fun resolveBody(threadId: String, resolved: Boolean) = graphqlBody(if (resolved) RESOLVE_THREAD else UNRESOLVE_THREAD, mapOf("thread" to threadId))

private const val START_REVIEW = "mutation(\$pr:ID!,\$commit:GitObjectID!,\$threads:[DraftPullRequestReviewThread]){addPullRequestReview(input:{pullRequestId:\$pr,commitOID:\$commit,threads:\$threads}){pullRequestReview{id}}}"
private const val ADD_REPLY = "mutation(\$review:ID!,\$thread:ID!,\$body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewId:\$review,pullRequestReviewThreadId:\$thread,body:\$body}){comment{id}}}"
private const val SUBMIT_REVIEW = "mutation(\$review:ID!,\$event:PullRequestReviewEvent!,\$body:String){submitPullRequestReview(input:{pullRequestReviewId:\$review,event:\$event,body:\$body}){pullRequestReview{id}}}"
private const val ADD_THREAD = "mutation(\$review:ID!,\$path:String!,\$line:Int!,\$startLine:Int,\$startSide:DiffSide,\$body:String!){addPullRequestReviewThread(input:{pullRequestReviewId:\$review,path:\$path,line:\$line,side:RIGHT,startLine:\$startLine,startSide:\$startSide,body:\$body}){thread{comments(first:1){nodes{id}}}}}"
private const val DELETE_COMMENT = "mutation(\$comment:ID!){deletePullRequestReviewComment(input:{id:\$comment}){pullRequestReview{id}}}"
private const val UPDATE_COMMENT = "mutation(\$comment:ID!,\$body:String!){updatePullRequestReviewComment(input:{pullRequestReviewCommentId:\$comment,body:\$body}){pullRequestReviewComment{id}}}"
private const val RESOLVE_THREAD = "mutation(\$thread:ID!){resolveReviewThread(input:{threadId:\$thread}){thread{id}}}"
private const val UNRESOLVE_THREAD = "mutation(\$thread:ID!){unresolveReviewThread(input:{threadId:\$thread}){thread{id}}}"
private const val DELETE_REVIEW = "mutation(\$review:ID!){deletePullRequestReview(input:{pullRequestReviewId:\$review}){pullRequestReview{id}}}"

private class RawThread(
    val id: String = "", val path: String = "", val line: Int? = null, val originalLine: Int? = null, val diffSide: String? = null, val isResolved: Boolean = false, val resolvedBy: String? = null,
    val url: String? = null, val commit: String? = null, val mine: Boolean = false, val comments: List<ThreadComment>? = null,
    val viewerCanResolve: Boolean = false, val viewerCanUnresolve: Boolean = false, val resolvedByMe: Boolean = false, val startLine: Int? = null,
)

/** Reads the one-JSON-object-per-line output of [REVIEW_THREADS_JQ]; threads without comments are dropped. */
fun parseReviewThreads(text: String): List<ReviewThread> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
    val raw = Gson().fromJson(line, RawThread::class.java)
    raw.comments?.takeIf { it.isNotEmpty() }?.let {
        ReviewThread(raw.id, raw.path, raw.line, raw.originalLine, raw.diffSide == "LEFT", raw.commit, raw.isResolved, raw.resolvedBy, raw.url.orEmpty(), raw.mine, it,
            // GitHub answers startLine == line for a one-line thread (measured): only a real range keeps one
            raw.viewerCanResolve, raw.viewerCanUnresolve, raw.resolvedByMe, raw.startLine?.takeIf { raw.line != null && it < raw.line })
    }
}

const val REVIEW_THREADS_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){viewer{login} repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){reviewThreads(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{id path line startLine originalLine diffSide isResolved resolvedBy{login} viewerCanResolve viewerCanUnresolve " +
    "comments(first:50){nodes{id author{login} body url commit{oid} viewerCanUpdate viewerCanDelete}}}}}}}"
const val REVIEW_THREADS_JQ = ".data.viewer.login as \$me | .data.repository.pullRequest.reviewThreads.nodes[] | {id, path, line, startLine, originalLine, diffSide, isResolved, resolvedBy: .resolvedBy.login, resolvedByMe: (.resolvedBy.login == \$me), viewerCanResolve, viewerCanUnresolve, " +
    "url: .comments.nodes[0].url, commit: .comments.nodes[0].commit.oid, mine: (.comments.nodes[0].author.login == \$me), " +
    "comments: [.comments.nodes[] | {id, author: (.author.login // \"ghost\"), body, canEdit: .viewerCanUpdate, canDelete: .viewerCanDelete, mine: (.author.login == \$me)}]} | tojson"

package io.github.vatsarishabh22.reviewlens.host.github

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.github.vatsarishabh22.reviewlens.core.*

// GitHub's wire: what GitHubClient sends (GraphQL text and payloads) and how it reads GitHub's answers into the
// host-neutral types. Nothing outside this package calls it.

/** A PR node of [PULL_REQUESTS_QUERY] as GitHub spells it; only [parsePullRequests] reads it. A team review request has no login. */
private data class GhUser(val login: String, val name: String?)
private data class GhLogin(val login: String?)
private data class GhRequest(val requestedReviewer: GhLogin?)
private data class GhRequests(val nodes: List<GhRequest>?)
private data class GhPullRequest(
    val number: Int, val title: String, val headRefName: String, val headRefOid: String, val baseRefName: String, val author: GhUser?,
    val body: String?, val additions: Int, val deletions: Int, val changedFiles: Int, val updatedAt: String, val reviewRequests: GhRequests?,
    val isCrossRepository: Boolean = false,
)

/** The open PRs, as `gh pr list` asked for them (its query, captured with `GH_DEBUG=api`, gh 2.103.3), and who is asking. */
internal const val PULL_REQUESTS_QUERY = "query(\$owner:String!,\$name:String!){viewer{login} repository(owner:\$owner,name:\$name)" +
    "{pullRequests(states:OPEN,first:100,orderBy:{field:CREATED_AT,direction:DESC}){nodes{number title headRefName headRefOid baseRefName " +
    "author{login ...on User{name}} body additions deletions changedFiles updatedAt reviewRequests(first:100){nodes{requestedReviewer{...on User{login} ...on Bot{login}}}} isCrossRepository}}}}"

/**
 * [PULL_REQUESTS_QUERY]'s answer as [PullRequest]s. A PR waits on the viewer when a review request names them.
 * ponytail: user requests only; a request to one of the viewer's teams lists under "Other open".
 */
fun parsePullRequests(data: JsonObject): List<PullRequest> {
    val viewer = data.at("viewer")?.get("login")?.asString
    val nodes = data.at("repository.pullRequests")?.getAsJsonArray("nodes") ?: throw IllegalArgumentException("GitHub did not return this repository")
    return Gson().fromJson(nodes, Array<GhPullRequest>::class.java).map {
        val author = it.author?.login ?: "ghost" // a deleted account
        PullRequest(it.number, it.title, it.headRefName, it.headRefOid, it.baseRefName, Person(author, it.author?.name?.ifBlank { null } ?: author),
            it.body.orEmpty(), it.additions, it.deletions, it.changedFiles, it.updatedAt,
            viewer != null && it.reviewRequests?.nodes.orEmpty().any { r -> r.requestedReviewer?.login == viewer }, fromFork = it.isCrossRepository)
    }
}

/**
 * Each open PR's commits (newest last), the stack rule's input (spec/README.md, Stacks). Its own call: asking for the
 * commits in the PR list asks for every commit's authors too, which GitHub refuses past 500,000 nodes (100 PRs did, measured).
 * ponytail: the last 100 commits of the newest 100 PRs; a parent further back is missed and the PR compares with its base.
 */
internal const val COMMIT_IDS_QUERY = "query(\$owner:String!,\$name:String!){repository(owner:\$owner,name:\$name){pullRequests(states:OPEN,first:100,orderBy:{field:CREATED_AT,direction:DESC})" +
    "{nodes{number commits(last:100){nodes{commit{oid}}}}}}}"

/** [COMMIT_IDS_QUERY]'s answer: each PR's commit ids. */
fun parseCommitIds(data: JsonObject): Map<Int, List<String>> = data.at("repository.pullRequests")?.getAsJsonArray("nodes").orEmpty().associate { pr ->
    pr.asJsonObject["number"].asInt to pr.asJsonObject.at("commits")?.getAsJsonArray("nodes").orEmpty().mapNotNull { it.asJsonObject.at("commit")?.get("oid")?.asString }
}

/** [REVIEW_SLOT_QUERY]'s answer: the PR's id, its latest push, your pending review if any, and whether you opened it. */
fun parseReviewSlot(data: JsonObject): GitHubSlot {
    val pr = data.at("repository.pullRequest")
    val id = pr?.string("id"); val head = pr?.string("headRefOid")
    require(!id.isNullOrEmpty() && !head.isNullOrEmpty()) { "GitHub did not return this pull request" }
    val pending = pr.at("reviews")?.getAsJsonArray("nodes")?.firstOrNull()?.asJsonObject
    val pendingId = pending?.string("id")
    require(pending == null || !pendingId.isNullOrEmpty()) { "GitHub did not return your pending review's id" }
    return GitHubSlot(id, head, pendingId?.let { PendingReview(it, pending.at("comments")?.get("totalCount")?.asInt ?: 0) }, pr["viewerDidAuthor"]?.asBoolean == true)
}

/** [REVIEW_READ_QUERY]'s answer: one review's state and its comment count. */
fun parseReviewRead(data: JsonObject): ReviewRead {
    val review = data.at("node")
    val state = review?.string("state")
    require(!state.isNullOrEmpty()) { "GitHub did not return this review" }
    return ReviewRead(state == "PENDING", review.at("comments")?.get("totalCount")?.asInt ?: 0)
}

internal const val REVIEW_READ_QUERY = "query(\$id:ID!){node(id:\$id){... on PullRequestReview{state comments{totalCount}}}}"

// reviews(states: PENDING) lists only the viewer's own: nobody else's pending review is visible
internal const val REVIEW_SLOT_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number)" +
    "{id headRefOid viewerDidAuthor reviews(states:PENDING,first:1){nodes{id comments{totalCount}}}}}}"

internal const val VIEWED_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){id files(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{path viewerViewedState}}}}}"

/** Every page of [VIEWED_QUERY]'s answer joined; states it does not know are skipped. */
fun parseViewedStates(pages: List<JsonObject>): ViewedStates {
    val prs = pages.mapNotNull { it.at("repository.pullRequest") }
    val id = prs.firstNotNullOfOrNull { it.string("id") }
    require(!id.isNullOrEmpty()) { "GitHub did not return this pull request" }
    val files = LinkedHashMap<String, ViewedState>()
    prs.flatMap { it.at("files")?.getAsJsonArray("nodes").orEmpty() }.map { it.asJsonObject }.forEach { file ->
        val path = file.string("path") ?: return@forEach // no path, nothing to tick
        runCatching { ViewedState.valueOf(file.string("viewerViewedState")!!) }.getOrNull()?.let { files[path] = it }
    }
    return ViewedStates(id, files)
}

/** A string field, null when GitHub answered null or left it out. */
internal fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList().orEmpty()

/** GitHub's reason from an error body (`{"message":"Unprocessable Entity","errors":["Path could not be resolved"]}`), or null. */
fun githubRefusal(body: String): String? {
    val json = runCatching { Gson().fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
    val errors = (json.get("errors") as? JsonArray)?.map { if (it.isJsonObject) it.asJsonObject.get("message")?.asString.orEmpty() else it.asString }
        ?.filter { it.isNotBlank() }.orEmpty()
    return errors.joinToString("; ").ifEmpty { json.get("message")?.asString }
}

private val FAILED = setOf("FAILURE", "ERROR", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE")
private val RUNNING = setOf("PENDING", "EXPECTED", "QUEUED", "IN_PROGRESS", "WAITING", "REQUESTED")

/** The latest push's checks, as `gh pr view --json statusCheckRollup` asked for them (captured with `GH_DEBUG=api`). ponytail: the first 100. */
internal const val CHECKS_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!){repository(owner:\$owner,name:\$name){pullRequest(number:\$number)" +
    "{commits(last:1){nodes{commit{statusCheckRollup{contexts(first:100){nodes{__typename ...on StatusContext{state} ...on CheckRun{status conclusion}}}}}}}}}}"

/**
 * One line for a PR's checks, the rollup's [contexts] in [CHECKS_QUERY]'s answer (none when GitHub has no rollup). GitHub
 * mixes two kinds: check runs carry `status` + `conclusion`, commit statuses carry `state`. Any failure wins over
 * running, running over passed.
 */
fun summarizeChecks(contexts: JsonArray?): Checks {
    val outcomes = contexts.orEmpty().map { it.asJsonObject }.map { check ->
        val status = check.string("status").orEmpty()
        when {
            status.isNotEmpty() && status != "COMPLETED" -> "PENDING"
            else -> check.string("conclusion").orEmpty().ifEmpty { check.string("state").orEmpty() }
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

/** One GraphQL call's body: its query and variables. */
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

/**
 * Every page of [REVIEW_THREADS_QUERY]'s answer as [ReviewThread]s; threads without comments are dropped. [ReviewThread.mine]
 * and a comment's `mine`: the viewer wrote it; a deleted account reads "ghost".
 */
fun parseReviewThreads(pages: List<JsonObject>): List<ReviewThread> = pages.flatMap { page ->
    val me = page.at("viewer")?.string("login")
    page.at("repository.pullRequest.reviewThreads")?.getAsJsonArray("nodes").orEmpty().map { it.asJsonObject }.mapNotNull { t ->
        val nodes = t.at("comments")?.getAsJsonArray("nodes").orEmpty().map { it.asJsonObject }
        val first = nodes.firstOrNull() ?: return@mapNotNull null
        val comments = nodes.map { c ->
            val author = c.at("author")?.string("login")
            ThreadComment(author ?: "ghost", c.string("body").orEmpty(), c.string("id").orEmpty(), c.flag("viewerCanUpdate"), c.flag("viewerCanDelete"), author != null && author == me)
        }
        val line = t.int("line")
        val resolver = t.at("resolvedBy")?.string("login")
        val firstAuthor = first.at("author")?.string("login")
        ReviewThread(t.string("id").orEmpty(), t.string("path").orEmpty(), line, t.int("originalLine"), t.string("diffSide") == "LEFT", first.at("commit")?.string("oid"),
            t.flag("isResolved"), resolver, first.string("url").orEmpty(), firstAuthor != null && firstAuthor == me, comments,
            // GitHub answers startLine == line for a one-line thread (measured): only a real range keeps one
            t.flag("viewerCanResolve"), t.flag("viewerCanUnresolve"), resolver != null && resolver == me, t.int("startLine")?.takeIf { line != null && it < line })
    }
}

private fun JsonObject.int(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt
private fun JsonObject.flag(name: String): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean == true

const val REVIEW_THREADS_QUERY = "query(\$owner:String!,\$name:String!,\$number:Int!,\$endCursor:String){viewer{login} repository(owner:\$owner,name:\$name)" +
    "{pullRequest(number:\$number){reviewThreads(first:100,after:\$endCursor){pageInfo{hasNextPage endCursor} nodes{id path line startLine originalLine diffSide isResolved resolvedBy{login} viewerCanResolve viewerCanUnresolve " +
    "comments(first:50){nodes{id author{login} body url commit{oid} viewerCanUpdate viewerCanDelete}}}}}}}"

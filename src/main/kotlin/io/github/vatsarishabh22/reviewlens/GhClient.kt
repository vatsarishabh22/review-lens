package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

data class GhUser(val login: String, val name: String?) {
    val displayName get() = name?.ifBlank { null } ?: login
}

data class ReviewRequest(val login: String?)

data class PullRequest(
    val number: Int,
    val title: String,
    val headRefName: String,
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
            "number,title,headRefName,baseRefName,author,body,additions,deletions,changedFiles,updatedAt,reviewRequests")
        return Gson().fromJson(json, Array<PullRequest>::class.java).toList()
    }

    fun checks(number: Int): Checks =
        summarizeChecks(runCli(root, "gh", "pr", "view", number.toString(), "--json", "statusCheckRollup"))
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

package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import java.nio.file.Path

data class PullRequest(val number: Int, val title: String, val headRefName: String, val baseRefName: String)

/** The `gh` CLI, used only when it is installed and logged in. */
class GhClient(private val root: Path) {

    fun isReady(): Boolean = runCatching { runCli(root, "gh", "auth", "status") }.isSuccess

    fun openPullRequests(): List<PullRequest> {
        val json = runCli(root, "gh", "pr", "list", "--state", "open", "--limit", "100",
            "--json", "number,title,headRefName,baseRefName")
        return Gson().fromJson(json, Array<PullRequest>::class.java).toList()
    }
}

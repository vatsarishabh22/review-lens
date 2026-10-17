package io.github.vatsarishabh22.reviewlens

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * One PR's review state, kept across sessions in `.git/review-lens/<key>.json` (outside the working tree).
 * [reviewed] maps a path to the blob it had when marked reviewed, so a later change to that file un-reviews it.
 * [seenHead] is the PR head the reviewer last started or re-scoped on: "Since your last review" starts there.
 * [unsent] holds ticks GitHub has not confirmed yet (path → viewed); they are re-sent first and win until confirmed.
 */
data class ReviewMeta(
    val target: ReviewTarget,
    val range: ScopeRange,
    val scopeLabel: String,
    val reviewed: Map<String, String> = emptyMap(),
    val seenHead: String? = null,
    val unsent: Map<String, Boolean>? = null,
) {
    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun fileFor(git: GitClient, key: String): Path = git.gitDir.resolve("review-lens/$key.json")

        fun load(git: GitClient, key: String): ReviewMeta? =
            fileFor(git, key).takeIf(Files::exists)?.let { gson.fromJson(Files.readString(it), ReviewMeta::class.java) }

        fun save(git: GitClient, meta: ReviewMeta) {
            val file = fileFor(git, meta.target.key)
            Files.createDirectories(file.parent)
            Files.writeString(file, gson.toJson(meta))
        }
    }
}

/** Stands in for a blob in [ReviewMeta.reviewed] when GitHub says "changed since viewed" and this laptop never saw the file. */
const val DISMISSED_BLOB = "dismissed"

/**
 * One checklist from two memories: for files GitHub knows about it wins (VIEWED = viewed at today's version,
 * DISMISSED = 🔄, UNVIEWED = not viewed), except ticks it has not confirmed yet ([unsent]), which keep this laptop's
 * answer so an offline tick is never erased; every other path keeps what this laptop remembers.
 */
fun mergeViewed(local: Map<String, String>, github: Map<String, ViewedState>, changes: List<FileChange>, unsent: Map<String, Boolean> = emptyMap()): Map<String, String> {
    val merged = local.toMutableMap()
    for (change in changes) {
        if (change.path in unsent) continue
        when (github[change.path]) {
            ViewedState.VIEWED -> merged[change.path] = change.newBlob
            ViewedState.DISMISSED -> if (merged[change.path].let { it == null || it == change.newBlob }) merged[change.path] = DISMISSED_BLOB
            ViewedState.UNVIEWED -> merged.remove(change.path)
            null -> Unit
        }
    }
    return merged
}

/** Re-sends the queued ticks ([send] says whether GitHub took one), then folds in GitHub's answer. */
fun syncViewed(meta: ReviewMeta, github: Map<String, ViewedState>, changes: List<FileChange>, send: (String, Boolean) -> Boolean): ReviewMeta {
    val queued = meta.unsent.orEmpty()
    val unsent = queued.filterNot { (path, viewed) -> send(path, viewed) }
    // every queued tick keeps the laptop's value: [github] was read before the re-send, so it is stale for them
    return meta.copy(reviewed = mergeViewed(meta.reviewed, github, changes, queued), unsent = unsent.ifEmpty { null })
}

/** How far a started review got: files viewed (at their current version) out of the files in its scope. */
data class ReviewProgress(val viewed: Int, val total: Int)

enum class Badge(val symbol: String) { TODO("⭐"), REVIEWED("✅"), CHANGED_SINCE("🔄") }

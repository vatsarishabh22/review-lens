package io.github.vatsarishabh22.reviewlens

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/**
 * One PR's review state, kept across sessions in `.git/review-lens/<key>.json` (outside the working tree).
 * [reviewed] maps a path to the blob it had when marked reviewed, so a later change to that file un-reviews it.
 * [seenHead] is the PR head the reviewer last started or re-scoped on: "Since your last review" starts there.
 * [unsent] holds ticks GitHub has not confirmed yet (path → viewed); they are re-sent first and win until confirmed.
 * [drafts] are the reviewer's inline comments, kept here (across exit) until Finish review posts them.
 * [verdict] is the last verdict GitHub accepted from this reviewer (Mark as reviewed leaves it), for the PR list.
 * [checked] holds the impact map's ticks: per changed function (`path#Class.method`), the places outside the PR the
 * reviewer checked, kept with a hash of the function's text so a later change to it clears them (spec/impact.md).
 */
data class ReviewMeta(
    val target: ReviewTarget,
    val range: ScopeRange,
    val scopeLabel: String,
    val reviewed: Map<String, String> = emptyMap(),
    val seenHead: String? = null,
    val unsent: Map<String, Boolean>? = null,
    val drafts: List<Draft>? = null,
    val verdict: Verdict? = null,
    val checked: Map<String, CheckedFunction>? = null,
) {
    /** The places ticked for [function], only while its text still hashes to [hash]: a changed function starts over. */
    fun checkedPlaces(function: String, hash: String): Set<String> = checked?.get(function)?.takeIf { it.hash == hash }?.places.orEmpty().toSet()

    /** Ticks or unticks [place] for [function] as it is now ([hash]); ticks from an earlier version of it are dropped. */
    fun tick(function: String, hash: String, place: String, on: Boolean): ReviewMeta {
        val places = checkedPlaces(function, hash).let { if (on) it + place else it - place }
        return copy(checked = (checked.orEmpty() + (function to CheckedFunction(hash, places.sorted()))).ifEmpty { null })
    }

    /** At Exit: keeps the ticks of [functions] (the scope's changed functions) and drops the rest. */
    fun keepChecked(functions: Set<String>): ReviewMeta = copy(checked = checked?.filterKeys { it in functions }?.ifEmpty { null })

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

/**
 * Re-sends the queued ticks ([send] says whether GitHub took one), then folds in GitHub's answer. [github] lists every
 * file of the PR on GitHub; a queued tick for any other path (a scope file whose change the PR undoes, or one that came
 * in with a merge from the base) has no Viewed box there and is refused, so it is dropped from the queue, kept here.
 */
fun syncViewed(meta: ReviewMeta, github: Map<String, ViewedState>, changes: List<FileChange>, send: (String, Boolean) -> Boolean): ReviewMeta {
    val queued = meta.unsent.orEmpty().filterKeys { it in github }
    val unsent = queued.filterNot { (path, viewed) -> send(path, viewed) }
    // every queued tick keeps the laptop's value: [github] was read before the re-send, so it is stale for them
    return meta.copy(reviewed = mergeViewed(meta.reviewed, github, changes, queued), unsent = unsent.ifEmpty { null })
}

/**
 * How far a started review got: files viewed (at their current version) out of the files in its scope, the last posted
 * [verdict], and whether the PR has [newCommits] since the head you last reviewed.
 */
data class ReviewProgress(val viewed: Int, val total: Int, val verdict: Verdict? = null, val newCommits: Boolean = false) {
    /** Every file viewed at its current version, and nothing pushed since. */
    val done get() = total > 0 && viewed == total && !newCommits

    /** The detail view's button; "new commits" only when they can be offered on their own (a force-push can rewrite the commit you reviewed). */
    fun startLabel(sinceOffered: Boolean) = when {
        newCommits && sinceOffered -> "Review new commits"
        done || newCommits -> "Review again"
        else -> "Resume review"
    }

    /** The PR list's pill. */
    val label get() = when {
        newCommits -> "🔄 New commits since your review"
        done -> "✓ Reviewed · ${verdict?.done ?: "$viewed of $total"}"
        else -> "Resume · $viewed of $total viewed"
    }
}

/** True when the PR's [head] on GitHub is not the one this review last saw; unknown heads (no gh, a branch) never claim it. */
fun ReviewMeta.headMoved(head: String?): Boolean = !head.isNullOrEmpty() && head != (seenHead ?: range.to)

enum class Badge(val symbol: String) { TODO("⭐"), REVIEWED("✅"), CHANGED_SINCE("🔄") }

/** The impact map's ticks on one function: the [places] checked (each `path#name`), while its text hashed to [hash]. */
data class CheckedFunction(val hash: String, val places: List<String>)


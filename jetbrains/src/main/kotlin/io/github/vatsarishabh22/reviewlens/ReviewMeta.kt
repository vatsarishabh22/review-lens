package io.github.vatsarishabh22.reviewlens

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * One PR's review state, kept across sessions in `.git/review-lens/<key>.json` (outside the working tree).
 * [reviewed] maps a path to the blob it had when marked reviewed, so a later change to that file un-reviews it.
 * [seenHead] is the PR head the reviewer last started or re-scoped on: "Since your last review" starts there.
 * [parentHead] is its stack parent's head at its last load, so the review bar's scope menu works out where its own
 * changes start without asking the host; every load replaces it.
 * [unsent] holds ticks GitHub has not confirmed yet (path → viewed); each load sends them again and they win until confirmed.
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
    val parentHead: String? = null,
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

    /** Of [changes] (the scope's files now), the ones ticked at the version they have: "x of N viewed". */
    fun viewedNow(changes: List<FileChange>): List<FileChange> = changes.filter { reviewed[it.path] == it.newBlob }

    /** At Exit: keeps the ticks of [functions] (the scope's changed functions) and drops the rest. */
    fun keepChecked(functions: Set<String>): ReviewMeta = copy(checked = checked?.filterKeys { it in functions }?.ifEmpty { null })

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().create()

        private fun fileFor(git: GitClient, key: String): Path = git.gitDir.resolve("review-lens/$key.json")

        fun load(git: GitClient, key: String): ReviewMeta? =
            fileFor(git, key).takeIf(Files::exists)?.let { gson.fromJson(Files.readString(it), ReviewMeta::class.java) }

        /**
         * The only way the review [key]'s file is written: [change] gets what it holds now (null: none yet) and returns
         * what it becomes, one change at a time, so a tick, a draft, a re-scope and a load each build on the others and
         * none writes over one made while it worked.
         */
        fun change(git: GitClient, key: String, change: (ReviewMeta?) -> ReviewMeta): ReviewMeta = synchronized(LOCK) {
            change(load(git, key)).also { meta -> writeWhole(fileFor(git, key), gson.toJson(meta)) }
        }

        /**
         * Writes [folded], an answer worked out from [read] while the host was asked, only if the file still holds [read]:
         * a tick or a draft saved meanwhile is newer and stays, and the next load folds the host in again.
         */
        fun foldIn(git: GitClient, read: ReviewMeta, folded: ReviewMeta): ReviewMeta =
            change(git, read.target.key) { now -> if (now == null || now == read) folded else now }

        private val LOCK = Any()
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
 * Folds in GitHub's answer at load; the queued ticks keep the laptop's value ([github] was read before they go again),
 * and what is left queued goes through the tick line, never from here. [github] lists every file of the PR on GitHub; a
 * queued tick for any other path (a scope file whose change the PR undoes, or one that came in with a merge from the
 * base) has no Viewed box there, so it leaves the queue, kept here.
 */
fun syncViewed(meta: ReviewMeta, github: Map<String, ViewedState>, changes: List<FileChange>): ReviewMeta {
    val queued = meta.unsent.orEmpty().filterKeys { it in github }
    return meta.copy(reviewed = mergeViewed(meta.reviewed, github, changes, queued), unsent = queued.ifEmpty { null })
}

/** Replaces [file] whole (written beside it, then moved over it), so a reader never sees it half written. */
fun writeWhole(file: Path, text: String) {
    Files.createDirectories(file.parent)
    val written = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
    try {
        Files.writeString(written, text)
        Files.move(written, file, REPLACE_EXISTING, ATOMIC_MOVE)
    } finally { Files.deleteIfExists(written) } // gone once moved; a failed write leaves no stray copy
}

/**
 * [meta] with [sent], ticks the host took for review [key], confirmed ([confirmed]); unchanged when [meta] is another
 * review's, the one under way since, whose queue they must never clear (its own next send or load sorts them out).
 */
fun confirmedIn(meta: ReviewMeta, key: String, sent: Map<String, Boolean>): ReviewMeta =
    if (meta.target.key == key) meta.copy(unsent = confirmed(meta.unsent, sent)) else meta

/** Every file of a scope viewed; an empty scope never is. */
fun allViewed(viewed: Int, total: Int) = total > 0 && viewed == total

/**
 * A started review's line in the PR list (spec/README.md, the list's table; spec/cases/list-status.json). [ticked] is
 * any tick, of any version; [headMoved] is the PR's head past the one you last reviewed; [changedSince] is how many of
 * the files you viewed the host marks changed since (null until it answers, or with no host to ask).
 */
data class ReviewProgress(
    val viewed: Int, val total: Int, val verdict: Verdict? = null, val headMoved: Boolean = false, val drafts: Int = 0,
    val ticked: Boolean = viewed > 0, val changedSince: Int? = null,
) {
    private val allViewed get() = allViewed(viewed, total)

    /** Whether the host is asked for [changedSince]: a moved head on a review with files viewed at the version last reviewed. */
    val asksHost get() = headMoved && viewed > 0

    /** A push is news only to a review that read or posted something, and not once the host says no file you viewed changed (a rebase). */
    val newCommits get() = headMoved && (ticked || verdict != null) && !(ticked && changedSince == 0)

    val done get() = !newCommits && (allViewed || verdict?.final == true)

    /** The detail view's button; "new commits" only when they can be offered on their own (a force-push can rewrite the commit you reviewed). */
    fun startLabel(sinceOffered: Boolean) = when {
        newCommits && sinceOffered -> "Review new commits"
        done || newCommits -> "Review again"
        else -> "Resume review"
    }

    /** The PR list's pill. */
    val label get() = when {
        newCommits -> changedSince?.takeIf { it > 0 }?.let { "🔄 ${files(it)} changed since your review" } ?: "🔄 New commits since your review"
        allViewed -> "✓ Reviewed · ${verdict?.done ?: "$viewed of $total"}"
        verdict != null -> "${if (done) "✓ " else ""}${verdict.done.replaceFirstChar(Char::uppercase)} · $viewed of $total viewed"
        !ticked && drafts > 0 -> "Resume · $drafts ${if (drafts == 1) "draft" else "drafts"}"
        else -> "Resume · $viewed of $total viewed"
    }
}

/**
 * How many of [viewedThen] (the files you viewed at the version you last reviewed) the host marks changed since
 * (DISMISSED: it un-marks a file once its content changes, never for a rebase). Null when the host can't say: ticks it
 * hasn't got yet ([unsent]), or none of [viewedThen] ticked on it at all (another login, ticks from before the sync);
 * and null rather than 0 when the PR now has files outside your review's [scope] (a new file, a narrowed scope, a
 * stacked PR's parent), so a push that only touched those is never taken for a rebase.
 */
fun changedSince(viewedThen: Collection<String>, scope: Collection<String>, host: Map<String, ViewedState>, unsent: Map<String, Boolean>?): Int? {
    if (!unsent.isNullOrEmpty() || viewedThen.none { host[it] == ViewedState.VIEWED || host[it] == ViewedState.DISMISSED }) return null
    return viewedThen.count { host[it] == ViewedState.DISMISSED }.takeIf { it > 0 || scope.containsAll(host.keys) }
}

/** True when the PR's [head] on GitHub is not the one this review last saw; unknown heads (no gh, a branch) never claim it. */
fun ReviewMeta.headMoved(head: String?): Boolean = !head.isNullOrEmpty() && head != (seenHead ?: range.to)

enum class Badge(val symbol: String) { TODO("⭐"), REVIEWED("✅"), CHANGED_SINCE("🔄") }

/** A file's badge from the ticks: never ticked ⭐, ticked at its current blob ✅, else (an older blob, or "dismissed") 🔄. */
fun badgeOf(reviewed: Map<String, String>, change: FileChange): Badge = when (reviewed[change.path]) {
    null -> Badge.TODO
    change.newBlob -> Badge.REVIEWED
    else -> Badge.CHANGED_SINCE
}

/** What a click on a file's box does, as the prototype words it. */
fun badgeWords(badge: Badge) = when (badge) {
    Badge.TODO -> "Not viewed yet — click to mark viewed"
    Badge.REVIEWED -> "Viewed — click to unmark"
    Badge.CHANGED_SINCE -> "Changed since you viewed it"
}

/**
 * A folder's tick (spec/README.md, Viewed) on the [files] directly in it: which to change and whether they become
 * viewed. All viewed: all unticked; else the ones not viewed (🔄 included) ticked.
 */
fun folderTick(files: List<FileChange>, reviewed: Map<String, String>): Pair<List<FileChange>, Boolean> {
    val open = files.filter { badgeOf(reviewed, it) != Badge.REVIEWED }
    return if (allViewed(files.size - open.size, files.size)) files to false else open to true
}

/** The queue once the host took [sent]: each path leaves it unless the reviewer flipped it again while the call was out. */
fun confirmed(unsent: Map<String, Boolean>?, sent: Map<String, Boolean>): Map<String, Boolean>? =
    unsent.orEmpty().filterNot { (path, viewed) -> sent[path] == viewed }.ifEmpty { null }

/** The impact map's ticks on one function: the [places] checked (each `path#name`), while its text hashed to [hash]. */
data class CheckedFunction(val hash: String, val places: List<String>)


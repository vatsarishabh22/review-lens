package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

/** The review this checkout is in: what to go back to, and the stash holding the reviewer's own work (if any). */
data class ActiveReview(
    val target: ReviewTarget,
    val head: String,
    val reviewBranch: String,
    val originalRef: String,
    val stashMessage: String?,
)

/** The visible stages of starting a review, reported as each begins. */
enum class StartStep { PARK, CHECKOUT }

/** What exit did with the reviewer's own work. */
enum class Restored { NOTHING_TO_RESTORE, RESTORED, KEPT_IN_STASH, STASH_MISSING }

/**
 * Same-window review: park the reviewer's work, check the PR out on a local `review/…` branch, and on exit go back to
 * the original branch and restore the work. State lives in `.git/review-lens/active.json`, outside the working tree,
 * so a crash mid-review can be resumed or exited cleanly. Runs git: call off EDT.
 */
class ReviewLifecycle(private val git: GitClient) {

    private val stateFile: Path by lazy { git.gitDir.resolve("review-lens/active.json") }

    fun active(): ActiveReview? = stateFile.takeIf(Files::exists)?.let { Gson().fromJson(Files.readString(it), ActiveReview::class.java) }

    fun start(target: ReviewTarget, head: String, progress: (StartStep) -> Unit = {}): ActiveReview {
        active()?.let { error("Already reviewing ${it.target.label}. Exit that review first.") }
        val original = git.currentRef()
        val stash = if (git.isDirty()) "review-lens: parked from $original for ${target.label}" else null
        val review = ActiveReview(target, head, "review/${target.key}", original, stash)
        save(review) // before touching git, so a crash at any later point still knows how to get back
        try {
            stash?.let { progress(StartStep.PARK); git.stash(it) }
            progress(StartStep.CHECKOUT)
            git.checkoutBranchAt(review.reviewBranch, head)
        } catch (e: Exception) {
            runCatching { if (git.currentRef() != original) git.checkout(original) }
            stash?.let(git::restoreStash)
            Files.deleteIfExists(stateFile)
            throw e
        }
        return review
    }

    /** Moves an active review to a new head (new commits pushed, or a fresh fetch) without touching the parked work. */
    fun moveTo(head: String): ActiveReview {
        val review = active() ?: error("No review in progress")
        git.checkoutBranchAt(review.reviewBranch, head)
        return review.copy(head = head).also(::save)
    }

    fun exit(): Restored {
        val review = active() ?: return Restored.NOTHING_TO_RESTORE
        git.checkout(review.originalRef)
        val restored = when (review.stashMessage?.let(git::restoreStash)) {
            null -> if (review.stashMessage == null) Restored.NOTHING_TO_RESTORE else Restored.STASH_MISSING
            true -> Restored.RESTORED
            false -> Restored.KEPT_IN_STASH
        }
        Files.deleteIfExists(stateFile)
        return restored
    }

    private fun save(review: ActiveReview) {
        Files.createDirectories(stateFile.parent)
        Files.writeString(stateFile, Gson().toJson(review))
    }
}

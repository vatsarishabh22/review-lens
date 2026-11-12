package io.github.vatsarishabh22.reviewlens

import com.google.gson.GsonBuilder
import java.nio.file.Files

/**
 * `.git/review-lens/stacks.json` (spec/README.md, Stacks): the parents the last list saw, and each stacked PR's own file
 * count by `<parent head>..<head>`, which two commit ids fix, so a count never goes stale: a push makes a new key. Runs
 * CLIs: call off EDT.
 */
class StackStore(private val git: GitClient) {
    /** The file's shape. Gson leaves a missing map null whatever its default, hence the nullable fields. */
    private class Saved(val parents: Map<Int, SeenParent>? = null, val counts: Map<String, Int>? = null)

    private val file get() = git.gitDir.resolve("review-lens/stacks.json")

    private fun saved(): Saved = runCatching { GSON.fromJson(Files.readString(file), Saved::class.java) }.getOrNull() ?: Saved()

    fun parents(): Map<Int, SeenParent> = saved().parents.orEmpty()

    fun counts(): Map<String, Int> = saved().counts.orEmpty()

    /** Each open PR's parent by the exact fact and the notes ([stackView]), writing down what it saw for the next list. */
    fun observe(prs: List<PullRequest>): Map<Int, StackParent> = update { saved ->
        stackView(prs, saved.parents.orEmpty()).let { Saved(it.seen, saved.counts) to it.parents }
    }

    /**
     * The counts for [keys]: kept ones as they are, the rest asked of [compare] all at once; a key it can't answer is
     * left out. Saves only [keys]'s counts, so the keys of PRs that moved on drop out.
     */
    fun fill(keys: Collection<String>, compare: (from: String, to: String) -> Int): Map<String, Int> {
        val kept = counts().filterKeys { it in keys }
        val counts = kept + askAll(keys.filter { it !in kept }) { key -> compare(key.substringBefore(".."), key.substringAfter("..")) }
        update { saved -> Saved(saved.parents, counts) to Unit }
        return counts
    }

    /**
     * One read-modify-write at a time (a list's notes and a count fill run on different threads), the file replaced
     * whole by a move, so a reader never sees it half written and neither write drops the other's half.
     */
    private fun <T> update(change: (Saved) -> Pair<Saved, T>): T = synchronized(LOCK) {
        val (next, result) = change(saved())
        writeWhole(file, GSON.toJson(Saved(next.parents.orEmpty(), next.counts.orEmpty())))
        result
    }

    companion object {
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        // ponytail: one lock for every repository's file; writes are rare and quick, so a lock per file isn't worth it
        private val LOCK = Any()

        /**
         * Each stacked PR's key, for a PR still built on its parent's head: the bottom PR, a moved one and one stacked the
         * host's way compare with their base, whose count the host gives.
         */
        fun keys(prs: List<PullRequest>, parents: Map<Int, StackParent>): Map<Int, String> {
            val heads = prs.associate { it.number to it.headSha }
            return prs.mapNotNull { pr ->
                parents[pr.number]?.takeUnless { it.moved || it.onBranch }?.let { heads[it.number] }?.let { pr.number to "$it..${pr.headSha}" }
            }.toMap()
        }
    }
}

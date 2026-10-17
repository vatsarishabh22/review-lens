package io.github.vatsarishabh22.reviewlens

import java.nio.file.Path

/** Git operations on the repository checked out at [root]. */
class GitClient(val root: Path) {

    /** Fetches PR [number] into a private ref plus its [base] branch; returns the PR's head ref. */
    fun fetchPr(number: Int, base: String): String {
        val ref = "refs/review-lens/pr-$number"
        git("fetch", "--no-tags", REMOTE, "+pull/$number/head:$ref", "+refs/heads/$base:refs/remotes/$REMOTE/$base")
        return ref
    }

    fun fetchAll() = git("fetch", "--no-tags", REMOTE)

    fun remoteBranches(): List<String> =
        git("branch", "-r", "--format=%(refname:short)").lines().filter { it.isNotBlank() && !it.endsWith("/HEAD") }

    fun defaultBase(): String =
        runCatching { git("symbolic-ref", "--short", "refs/remotes/$REMOTE/HEAD") }.getOrDefault("$REMOTE/main")

    fun mergeBase(a: String, b: String): String = git("merge-base", a, b)

    /** Commits in `from..to`, newest first, with author and commit time (epoch seconds). */
    fun commits(from: String, to: String): List<Commit> =
        git("log", "--format=%H%x09%an%x09%ct%x09%s", "$from..$to").lines().filter { it.isNotBlank() }.map {
            val parts = it.split('\t', limit = 4)
            Commit(parts[0], parts.getOrElse(3) { "" }, parts[1], parts[2].toLong())
        }

    fun changes(range: ScopeRange): List<FileChange> = parseChanges(
        git("diff", "--raw", "-z", "--no-abbrev", "-M", range.from, range.to),
        git("diff", "--numstat", "-z", "-M", range.from, range.to),
    )

    fun commitShas(range: ScopeRange): Set<String> = git("rev-list", "${range.from}..${range.to}").lines().toSet()

    fun blame(rev: String, path: String): Blame = parseBlame(git("blame", "--porcelain", rev, "--", path))

    /** File content at [rev], or "" when the file does not exist there (added files have no base). */
    fun show(rev: String, path: String): String = runCatching { runCli(root, "git", "show", "$rev:$path", trim = false) }.getOrDefault("")

    /** The repository's private dir (`.git`, or the worktree's own git dir). */
    fun gitDir(): Path = root.resolve(git("rev-parse", "--git-dir"))

    /** The branch HEAD is on, or the commit SHA when HEAD is detached. */
    fun currentRef(): String = runCatching { git("symbolic-ref", "--short", "-q", "HEAD") }.getOrNull()?.ifBlank { null } ?: git("rev-parse", "HEAD")

    /** Tracked changes and untracked files as `git status --short` lines (`M  app.py`), ignoring the IDE's own `.idea/`. */
    fun dirtyFiles(): List<String> =
        runCli(root, "git", "status", "--porcelain", "--", ".", NOT_IDE, trim = false).lines().filter { it.isNotBlank() }

    fun isDirty(): Boolean = dirtyFiles().isNotEmpty()

    /** Every tracked path at HEAD, for the panel's whole-project view. */
    fun trackedFiles(): List<String> = git("ls-files").lines().filter { it.isNotBlank() }

    /**
     * Stashes the reviewer's work, untracked files included, under [message] so it can be found again by name.
     * `.idea/` stays put: the open IDE keeps writing to it, which would stop the stash from re-applying.
     */
    fun stash(message: String) = git("stash", "push", "--include-untracked", "-m", message, "--", ".", NOT_IDE)

    /**
     * Pops the stash saved under [message]. Returns false and keeps the stash when it does not apply cleanly,
     * so nothing is ever lost; returns null when no such stash exists.
     */
    fun restoreStash(message: String): Boolean? {
        val ref = git("stash", "list", "--format=%gd%x09%gs").lines()
            .firstOrNull { it.substringAfter('\t').endsWith(": $message") }?.substringBefore('\t') ?: return null
        // --index brings staged changes back as staged; it refuses up front (changing nothing) when that cannot apply.
        // A plain pop that conflicts leaves the stash in place, so a failure never loses work.
        return runCatching { git("stash", "pop", "--index", ref) }.recoverCatching { git("stash", "pop", ref) }.isSuccess
    }

    /** Creates or resets local [branch] to [sha] and checks it out; fails without touching anything if the tree is dirty. */
    fun checkoutBranchAt(branch: String, sha: String) = git("checkout", "-B", branch, sha)

    fun checkout(ref: String) = git("checkout", ref)

    fun revParse(ref: String): String = git("rev-parse", ref)

    private fun git(vararg args: String) = runCli(root, "git", *args)

    companion object {
        // ponytail: assumes the GitHub remote is "origin"; make it a setting when a fork-based repo needs another.
        const val REMOTE = "origin"
        // A glob, not ":(exclude).idea": stash re-runs `git add` with the pathspec, and git add refuses a pathspec that
        // names an ignored path exactly, which a repo ignoring `.idea` while tracking files in it hits (stash saved, tree not cleaned).
        private const val NOT_IDE = ":(exclude,glob).ide[a]/**"

        /**
         * The repo root in the IDE's own spelling of the path. `--show-toplevel` resolves symlinks (macOS /tmp is
         * /private/tmp), and a file opened through that spelling is outside the project: no guard, no bar, a dialog.
         */
        fun forProject(basePath: String): GitClient =
            GitClient(Path.of(basePath).resolve(runCli(Path.of(basePath), "git", "rev-parse", "--show-cdup")).normalize())
    }
}

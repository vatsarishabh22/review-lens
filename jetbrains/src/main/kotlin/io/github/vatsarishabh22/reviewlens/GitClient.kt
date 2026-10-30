package io.github.vatsarishabh22.reviewlens

import java.nio.file.Path

/** Git operations on the repository checked out at [root]. */
class GitClient(val root: Path) {

    /** Fetches a review's [head] (the host's refspec source) into `refs/review-lens/<key>`, plus its [base] branch; returns that ref. */
    fun fetchReview(head: String, key: String, base: String): String {
        val ref = "refs/review-lens/$key"
        git("fetch", "--no-tags", REMOTE, "+$head:$ref", "+refs/heads/$base:refs/remotes/$REMOTE/$base")
        return ref
    }

    /** `origin`'s URL, or null when there is no `origin`. */
    fun originUrl(): String? = runCatching { git("remote", "get-url", REMOTE) }.getOrNull()?.ifBlank { null }

    fun fetchAll() = git("fetch", "--no-tags", REMOTE)

    fun remoteBranches(): List<String> =
        // lstrip, not short: git shortens refs/remotes/origin/HEAD to a bare "origin", which slipped past the HEAD filter
        git("branch", "-r", "--format=%(refname:lstrip=2)").lines().filter { it.isNotBlank() && !it.endsWith("/HEAD") }

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

    /** Every file's changed lines in [range], for the impact map; paths unquoted, so a non-ASCII name reads as itself. */
    fun changedLines(range: ScopeRange): Map<String, LineChanges> = parseChangedLines(
        runCli(root, "git", "-c", "core.quotePath=false", "diff", "-U0", "--no-color", "--no-ext-diff", "--no-prefix", "-M", range.from, range.to, trim = false))

    /** File content at [rev], or "" when the file does not exist there (added files have no base). */
    fun show(rev: String, path: String): String = runCatching { runCli(root, "git", "show", "$rev:$path", trim = false) }.getOrDefault("")

    /**
     * The repository's private dir (`.git`, or the worktree's own git dir), asked once: saving a tick runs on the UI
     * thread, where running git is forbidden (it showed as "IDE error occurred").
     */
    val gitDir: Path by lazy { root.resolve(git("rev-parse", "--git-dir")) }

    /** The branch HEAD is on, or the commit SHA when HEAD is detached. */
    fun currentRef(): String = runCatching { git("symbolic-ref", "--short", "-q", "HEAD") }.getOrNull()?.ifBlank { null } ?: git("rev-parse", "HEAD")

    /** Tracked changes and untracked files as `git status --short` lines (`M  app.py`), ignoring the IDE's own `.idea/`. */
    /** The reviewer's work: tracked changes (`.idea/` included) and untracked files, except the IDE's untracked scratch in `.idea/`. */
    fun dirtyFiles(): List<String> =
        runCli(root, "git", "status", "--porcelain", trim = false).lines().filter { it.isNotBlank() && !it.startsWith("?? $IDE_DIR/") && it != "?? $IDE_DIR" }

    fun isDirty(): Boolean = dirtyFiles().isNotEmpty()

    /**
     * Tracked files changed here (staged or not) that also differ between HEAD and [target]: exactly what makes
     * `git checkout <target>` refuse. Other changes, a file the IDE just staged for one, are carried across untouched.
     */
    fun blockingCheckout(target: String): List<String> {
        val changed = git("diff", "--name-only", "HEAD").lines().filter { it.isNotBlank() }
        if (changed.isEmpty()) return changed
        val moving = git("diff", "--name-only", "HEAD", target).lines().toSet()
        return changed.filter { it in moving }
    }

    /** Every tracked path at HEAD, for the panel's whole-project view. */
    fun trackedFiles(): List<String> = git("ls-files").lines().filter { it.isNotBlank() }

    /**
     * Stashes the reviewer's work, untracked files included, under [message] so it can be found again by name. Tracked
     * files in `.idea/` go too: teams commit them and a PR can change them. The IDE's untracked scratch there stays put:
     * the open IDE keeps rewriting it, which would stop the stash from re-applying.
     */
    fun stash(message: String) = git("stash", "push", "--include-untracked", "-m", message, "--", ".",
        *git("ls-files", "--others", "--exclude-standard", "-z", "--", IDE_DIR).split('\u0000').filter { it.isNotEmpty() }.map { ":(exclude,literal)$it" }.toTypedArray())

    /** Stashes the changes to [paths] only, under [message]; for what was written during a review, which isn't the reviewer's work. */
    fun stashPaths(message: String, paths: List<String>) = git("stash", "push", "-m", message, "--", *paths.map { ":(literal)$it" }.toTypedArray())

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
        // The IDE's settings folder. Only its untracked files are left out of a park; an ignored `.idea` (the plugin
        // template's shape) lists none, so no exclude ever names an ignored path, which git add would refuse.
        private const val IDE_DIR = ".idea"

        /**
         * The repo root in the IDE's own spelling of the path. `--show-toplevel` resolves symlinks (macOS /tmp is
         * /private/tmp), and a file opened through that spelling is outside the project: no guard, no bar, a dialog.
         */
        fun forProject(basePath: String): GitClient =
            GitClient(Path.of(basePath).resolve(runCli(Path.of(basePath), "git", "rev-parse", "--show-cdup")).normalize())
    }
}

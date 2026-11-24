package io.github.vatsarishabh22.reviewlens.host

import io.github.vatsarishabh22.reviewlens.core.*
import io.github.vatsarishabh22.reviewlens.host.github.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks a checkout's host as spec/gitlab.md says: `origin`'s host, then the CLI signed in to it. GitHub is the only
 * one yet; none means the branch list. Remembered per checkout; the PR list picks again on every load, so signing in
 * or out shows there.
 */
object ReviewHosts {
    private val picked = ConcurrentHashMap<Path, Picked>()
    private class Picked(val host: ReviewHost?)

    fun of(git: GitClient): ReviewHost? = picked.getOrPut(git.root) { Picked(pick(git)) }.host

    /**
     * The picked host's [ReviewHost.name] for UI text, read without picking (picking runs `ssh`/`gh`, never on EDT). The PR
     * list and a restored review pick first, so the fallback shows only before either has run.
     */
    fun nameOf(git: GitClient?): String = git?.let { picked[it.root]?.host?.name } ?: "your code host"

    /** [of] for a call that can't go on without the host; the balloon's title already says which call. */
    fun required(git: GitClient): ReviewHost = of(git) ?: throw notSignedIn()

    fun notSignedIn() = CliException("Sign in to this repository's host")

    fun refresh(git: GitClient): ReviewHost? = Picked(pick(git)).also { picked[git.root] = it }.host

    private fun pick(git: GitClient): ReviewHost? {
        val remote = remoteHost(git.originUrl() ?: return null) ?: return null
        val host = if (remote.ssh) sshHostname(git.root, remote.name) else remote.name
        return GhClient(git.root).takeIf { it.signedInTo(host) }
    }

    /** What ssh connects to for [alias]: an `~/.ssh/config` Host alias (`github-personal`) names its real HostName. */
    private fun sshHostname(root: Path, alias: String): String =
        runCatching { parseSshHostname(runCli(root, "ssh", "-G", alias)) }.getOrNull() ?: alias
}

/** A remote's host [name], and whether it is reached over SSH (so it may be an `~/.ssh/config` alias). */
data class RemoteHost(val name: String, val ssh: Boolean)

/** The host in a remote URL: `https://`, `ssh://` and scp-like `git@host:owner/repo`; null for a local path or `file://`. */
fun remoteHost(url: String): RemoteHost? {
    // file:///path has no host to match; a scp-like URL is `host:path` with no slash after the colon (C:/ is a path)
    Regex("^([a-z+]+)://(?:[^@/]*@)?([^/:]+)").find(url)?.let { return RemoteHost(it.groupValues[2], it.groupValues[1].contains("ssh")) }
    return Regex("^(?:[^@/]+@)?([^/:]+):(?!/)").find(url)?.let { RemoteHost(it.groupValues[1], true) }
}

/** The `hostname` line of `ssh -G` output. */
fun parseSshHostname(output: String): String? =
    output.lineSequence().firstOrNull { it.startsWith("hostname ") }?.removePrefix("hostname ")?.trim()?.ifEmpty { null }

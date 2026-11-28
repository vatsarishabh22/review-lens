package io.github.vatsarishabh22.reviewlens.host

import io.github.vatsarishabh22.reviewlens.core.*
import io.github.vatsarishabh22.reviewlens.host.github.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks a checkout's host as spec/gitlab.md says: `origin`'s host, then the account signed in to it (`gh`'s, read once
 * per pick and kept in memory only). GitHub is the only one yet; none means the branch list. Remembered per checkout;
 * the PR list picks again on every load, so signing in or out shows there.
 */
object ReviewHosts {
    private val picked = ConcurrentHashMap<Path, Picked>()
    private class Picked(val host: ReviewHost?, val why: String? = null)

    fun of(git: GitClient): ReviewHost? = picked.getOrPut(git.root) { pick(git) }.host

    /**
     * The picked host's [ReviewHost.name] for UI text, read without picking (picking runs `ssh`/`gh`, never on EDT). The PR
     * list and a restored review pick first, so the fallback shows only before either has run.
     */
    fun nameOf(git: GitClient?): String = git?.let { picked[it.root]?.host?.name } ?: "your code host"

    /** [of] for a call that can't go on without the host; the balloon's title already says which call. */
    fun required(git: GitClient): ReviewHost = of(git) ?: throw notSignedIn(git)

    /** No host for [git]: the reason the last pick found (an old `gh`), else sign in. */
    fun notSignedIn(git: GitClient) = noHost(whyNot(git))

    fun refresh(git: GitClient): ReviewHost? = pick(git).also { picked[git.root] = it }.host

    /** Why the last pick found no host, when the reason is one the user can act on (an old `gh`); null otherwise. */
    fun whyNot(git: GitClient): String? = picked[git.root]?.why

    private fun pick(git: GitClient): Picked {
        val none = Picked(null)
        val aliases = HashMap<String, String>()
        fun hostOf(remote: RemoteHost) = if (remote.ssh) aliases.getOrPut(remote.name) { sshHostname(git.root, remote.name) } else remote.name
        val host = hostOf(remoteHost(git.originUrl() ?: return none) ?: return none)
        // the token is gh's account for this host; never logged (runCli logs only a failure's stderr)
        val token = try {
            runCli(git.root, "gh", "auth", "token", "--hostname", host).ifEmpty { null }
        } catch (e: Exception) { // no account for the host, or gh not installed ("Cannot run program"), or too old
            if (ghTooOld(e.message.orEmpty())) return Picked(null, "Update gh to 2.17 or later to reach GitHub")
            null
        } ?: return none
        val repo = ghDefaultRepo(parseRemotes(git.remoteUrls(), git.remoteMarkers()).filter { r -> remoteHost(r.url)?.let(::hostOf) == host }) ?: return none
        return Picked(GitHubClient(GitHubApi(host, token), repo))
    }

    /** What ssh connects to for [alias]: an `~/.ssh/config` Host alias (`github-personal`) names its real HostName. */
    private fun sshHostname(root: Path, alias: String): String =
        runCatching { parseSshHostname(runCli(root, "ssh", "-G", alias)) }.getOrNull()?.let(::sshHost) ?: alias
}

/** The failure of a call that needs a host none was picked for: [why] when the pick said one (an old `gh`), else sign in. */
fun noHost(why: String?) = CliException(why ?: "Sign in to this repository's host")

/** A remote's host [name], and whether it is reached over SSH (so it may be an `~/.ssh/config` alias). */
data class RemoteHost(val name: String, val ssh: Boolean)

/** The host in a remote URL: `https://`, `ssh://` and scp-like `git@host:owner/repo`; null for a local path or `file://`. */
fun remoteHost(url: String): RemoteHost? {
    // file:///path has no host to match; a scp-like URL is `host:path` with no slash after the colon (C:/ is a path)
    Regex("^([a-z+]+)://(?:[^@/]*@)?([^/:]+)").find(url)?.let { return RemoteHost(it.groupValues[2], it.groupValues[1].contains("ssh")) }
    return Regex("^(?:[^@/]+@)?([^/:]+):(?!/)").find(url)?.let { RemoteHost(it.groupValues[1], true) }
}

/** A remote: its [name], [url], and the repository `gh repo set-default` chose there ([resolved]: `base` or `owner/repo`). */
data class Remote(val name: String, val url: String, val resolved: String? = null)

/** [GitClient.remoteUrls]' fetch lines (`name<TAB>url (fetch)`) and [GitClient.remoteMarkers]' lines as [Remote]s, in name order. */
fun parseRemotes(urls: String, markers: String): List<Remote> {
    val resolved = markers.lines().mapNotNull { line ->
        val (key, value) = line.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        Regex("^remote\\.(.+)\\.gh-resolved$").find(key)?.let { it.groupValues[1] to value }
    }.toMap()
    return urls.lines().mapNotNull { Regex("^(\\S+)\t(.+) \\(fetch\\)$").find(it) }
        .map { Remote(it.groupValues[1], it.groupValues[2], resolved[it.groupValues[1]]) }.sortedBy { it.name }
}

/** The repository path in a remote URL (`acme/shop` from `git@github.com:acme/shop.git`), or null for a local path. */
fun remotePath(url: String): String? {
    val path = Regex("^[a-z+]+://[^/]+/(.+)$").find(url)?.groupValues?.get(1)
        ?: Regex("^(?:[^@/]+@)?[^/:]+:(?!/)(.+)$").find(url)?.groupValues?.get(1) ?: return null
    return path.trim('/').removeSuffix(".git").ifEmpty { null }
}

/**
 * The repository `gh` works on in a checkout with these [remotes] (all on one host), the remotes ranked `upstream`,
 * `github`, `origin` in any case, then the rest by name: the first one `gh repo set-default` marked (`gh-resolved`: `base`
 * names its remote, `owner/repo` or `host/owner/repo` the repository), else the first that names a repository (gh 2.103.3,
 * measured). Null when none does.
 */
fun ghDefaultRepo(remotes: List<Remote>): GitHubRepo? {
    val rank = listOf("upstream", "github", "origin")
    val ranked = remotes.sortedWith(compareBy({ rank.indexOf(it.name.lowercase()).let { i -> if (i < 0) rank.size else i } }, { it.name }))
    ranked.firstNotNullOfOrNull { r ->
        if (r.resolved == "base") remotePath(r.url)?.let(::repoOf) else r.resolved?.split('/')?.takeLast(2)?.joinToString("/")?.let(::repoOf)
    }?.let { return it }
    return ranked.firstNotNullOfOrNull { remotePath(it.url)?.let(::repoOf) }
}

/** `acme/shop` as a [GitHubRepo]; anything but two parts is no GitHub repository. */
private fun repoOf(path: String) = path.split('/').takeIf { it.size == 2 && it.none(String::isEmpty) }?.let { (owner, name) -> GitHubRepo(owner, name) }

/** gh before 2.17 has no `auth token`: its command parser answers "unknown flag: --hostname" (the shape measured on gh 2.103). */
fun ghTooOld(reason: String) = reason.startsWith("unknown ")

/** The host an SSH HostName serves: GitHub's SSH over port 443 (`ssh.github.com`, GitHub's documented setup) is github.com. */
fun sshHost(hostname: String): String = if (hostname == "ssh.github.com") "github.com" else hostname

/** The `hostname` line of `ssh -G` output. */
fun parseSshHostname(output: String): String? =
    output.lineSequence().firstOrNull { it.startsWith("hostname ") }?.removePrefix("hostname ")?.trim()?.ifEmpty { null }

package io.github.vatsarishabh22.reviewlens

import io.github.vatsarishabh22.reviewlens.core.*
import io.github.vatsarishabh22.reviewlens.host.*
import io.github.vatsarishabh22.reviewlens.host.github.*
import io.github.vatsarishabh22.reviewlens.services.*
import io.github.vatsarishabh22.reviewlens.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Which host a checkout talks to, from `origin`'s URL (spec/gitlab.md, "Picking the host"), and fetching a review's head. */
class ReviewHostTest {
    // its own client: the IDE's needs a running IDE, which this plain test doesn't start
    private val github = GitHubClient(GitHubApi("github.com", "", http = java.net.http.HttpClient.newHttpClient()), GitHubRepo("acme", "shop"))

    // Every "GitHub" the UI shows comes from the host's name (another host's name must show through); before any host is picked the text stays neutral.
    @Test
    fun `the UI says the picked host's name, and a neutral word before one is picked`() {
        assertEquals("GitHub", github.name)
        assertEquals("your code host", ReviewHosts.nameOf(null))
        assertEquals("on GitLab", CommentLook.POSTED.label("GitLab"))
        assertEquals("GitLab sync failed · kept on this laptop", HostViewed.Status.FAILED.text("GitLab"))
    }

    @Test
    fun `the host in every remote URL shape, SSH ones marked so an alias can be resolved`() {
        assertEquals(RemoteHost("github.com", false), remoteHost("https://github.com/acme/shop.git"))
        assertEquals(RemoteHost("github.example.com", false), remoteHost("https://token@github.example.com:8443/acme/shop"))
        assertEquals(RemoteHost("github.com", true), remoteHost("ssh://git@github.com:22/acme/shop.git"))
        assertEquals(RemoteHost("github.com", true), remoteHost("git@github.com:acme/shop.git"))
        assertEquals(RemoteHost("github-personal", true), remoteHost("git@github-personal:acme/shop.git"))
        listOf("/srv/git/shop.git", "../origin.git", "file:///srv/git/shop.git", "C:/repos/shop").forEach { assertNull(it, remoteHost(it)) }
    }

    @Test
    fun `the repository path in every remote URL shape`() {
        listOf("https://github.com/acme/shop.git", "https://token@github.example.com:8443/acme/shop", "ssh://git@github.com:22/acme/shop.git/",
            "git@github.com:acme/shop.git", "git@github-personal:acme/shop").forEach { assertEquals(it, "acme/shop", remotePath(it)) }
        assertEquals("group/sub/app", remotePath("https://gitlab.com/group/sub/app.git"))
        listOf("/srv/git/shop.git", "file:///srv/git/shop.git", "C:/repos/shop").forEach { assertNull(it, remotePath(it)) }
    }

    @Test
    fun `remotes are git remote -v's fetch URLs with their markers, a dotted name kept whole, a marker without a remote dropped`() {
        val urls = "origin\tgit@github.com:me/shop.git (fetch)\norigin\tgit@github.com:me/push.git (push)\nmy.fork\thttps://github.com/x/shop (fetch)\nmy.fork\thttps://github.com/x/shop (push)\n"
        val markers = "remote.my.fork.gh-resolved base\nremote.ghost.gh-resolved base\n"
        assertEquals(listOf(Remote("my.fork", "https://github.com/x/shop", "base"), Remote("origin", "git@github.com:me/shop.git")), parseRemotes(urls, markers))
        assertEquals(emptyList<Remote>(), parseRemotes("", ""))
    }

    /** gh reads a remote's URL as git uses it, measured: `gh:cli/cli` under `url."https://github.com/".insteadOf gh:` is cli/cli. */
    @Test
    fun `a remote's URL is read with insteadOf applied, as gh reads it`() {
        val dir = Files.createTempDirectory("review-lens-insteadof")
        fun git(vararg args: String) = check(ProcessBuilder("git", *args).directory(dir.toFile()).start().waitFor() == 0)
        git("init", "-q"); git("config", "url.https://github.com/.insteadOf", "gh:"); git("remote", "add", "origin", "gh:acme/shop")
        val client = GitClient(dir)
        assertEquals(listOf(Remote("origin", "https://github.com/acme/shop")), parseRemotes(client.remoteUrls(), client.remoteMarkers()))
    }

    /** gh 2.103.3's pick, measured in a scratch checkout (release log 2026-11-26). */
    @Test
    fun `the repository is the one gh would pick`() {
        fun r(name: String, repo: String, resolved: String? = null) = Remote(name, "https://github.com/$repo.git", resolved)
        assertEquals(GitHubRepo("b", "b"), ghDefaultRepo(listOf(r("github", "b/b"), r("origin", "a/a"))))
        assertEquals(GitHubRepo("c", "c"), ghDefaultRepo(listOf(r("github", "b/b"), r("origin", "a/a"), r("upstream", "c/c"))))
        assertEquals(GitHubRepo("a", "a"), ghDefaultRepo(listOf(r("origin", "a/a"), r("zeta", "b/b"))))
        assertEquals(GitHubRepo("c", "c"), ghDefaultRepo(listOf(r("alpha", "c/c"), r("zeta", "b/b"))))
        assertEquals("set-default wins: base names its remote", GitHubRepo("b", "b"), ghDefaultRepo(listOf(r("fork", "b/b", "base"), r("upstream", "c/c"))))
        assertEquals("or the repository itself", GitHubRepo("d", "d"), ghDefaultRepo(listOf(r("origin", "a/a", "d/d"), r("upstream", "c/c"))))
        assertEquals("with its host, as gh reads one (measured)", GitHubRepo("d", "d"), ghDefaultRepo(listOf(r("origin", "a/a", "github.com/d/d"))))
        assertEquals("a name in any case, measured", GitHubRepo("c", "c"), ghDefaultRepo(listOf(r("origin", "a/a"), r("Upstream", "c/c"))))
        assertEquals("two markers: the higher-ranked remote's, measured", GitHubRepo("c", "c"), ghDefaultRepo(listOf(r("origin", "a/a", "a/a"), r("upstream", "b/b", "c/c"))))
        assertNull(ghDefaultRepo(listOf(Remote("origin", "/srv/git/shop.git"))))
        assertNull("a GitLab subgroup path is no GitHub repository", ghDefaultRepo(listOf(Remote("origin", "https://gitlab.com/group/sub/app.git"))))
    }

    @Test
    fun `GitHub's SSH over port 443 is github_com, other hosts stay as they are`() {
        assertEquals("github.com", sshHost("ssh.github.com"))
        assertEquals("github.example.com", sshHost("github.example.com"))
    }

    @Test
    fun `a gh too old for auth token is told apart from one with no account`() {
        assertTrue(ghTooOld("unknown flag: --hostname")) // an unknown subcommand given --hostname, measured on gh 2.103
        assertTrue(ghTooOld("unknown command \"token\" for \"gh auth\""))
        assertFalse(ghTooOld("no oauth token found for gitlab.com"))
    }

    @Test
    fun `a call that needs the host says why there is none, sign in only when nothing else is known`() {
        assertEquals("Update gh to 2.17 or later to reach GitHub", noHost("Update gh to 2.17 or later to reach GitHub").message)
        assertEquals("Sign in to this repository's host", noHost(null).message)
    }

    @Test
    fun `an ssh config alias names its real host`() {
        assertEquals("github.com", parseSshHostname("user git\nhostname github.com\nport 22\n"))
        assertNull(parseSshHostname("user git\nport 22\n"))
    }

    @Test
    fun `a checkout whose origin is a local path has no host, so the list shows branches`() {
        val dir = Files.createTempDirectory("review-lens-host")
        fun git(vararg args: String) = check(ProcessBuilder("git", *args).directory(dir.toFile()).start().waitFor() == 0)
        git("init", "-q"); git("remote", "add", "origin", "/srv/git/shop.git")
        assertNull(ReviewHosts.refresh(GitClient(dir)))
    }

    @Test
    fun `a review's head is fetched from the host's ref into its own private ref`() {
        val origin = Files.createTempDirectory("review-lens-origin")
        val work = Files.createTempDirectory("review-lens-work")
        fun git(dir: java.nio.file.Path, vararg args: String) =
            ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@t", *args).directory(dir.toFile()).start().let { p ->
                p.inputStream.bufferedReader().readText().trim().also { check(p.waitFor() == 0) { args.joinToString(" ") } }
            }
        git(origin, "init", "-q", "-b", "main"); git(origin, "commit", "-q", "--allow-empty", "-m", "base")
        git(origin, "commit", "-q", "--allow-empty", "-m", "pr")
        val head = git(origin, "rev-parse", "HEAD")
        git(origin, "update-ref", "refs/pull/7/head", head); git(origin, "reset", "-q", "--hard", "HEAD~1")
        git(work, "init", "-q"); git(work, "remote", "add", "origin", origin.toString())
        val ref = GitClient(work).fetchReview(github.headRef(7), "pr-7", "main")
        assertEquals("refs/review-lens/pr-7", ref)
        assertEquals(head, git(work, "rev-parse", ref))
    }
}

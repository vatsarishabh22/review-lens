package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.vatsarishabh22.reviewlens.github.GhClient
import java.nio.file.Files

/** Which host a checkout talks to, from `origin`'s URL (spec/gitlab.md, "Picking the host"), and fetching a review's head. */
class ReviewHostTest {

    // Every "GitHub" the UI shows comes from the host's name (another host's name must show through); before any host is picked the text stays neutral.
    @Test
    fun `the UI says the picked host's name, and a neutral word before one is picked`() {
        assertEquals("GitHub", GhClient(java.nio.file.Path.of(".")).name)
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
        val ref = GitClient(work).fetchReview(GhClient(work).headRef(7), "pr-7", "main")
        assertEquals("refs/review-lens/pr-7", ref)
        assertEquals(head, git(work, "rev-parse", ref))
    }
}

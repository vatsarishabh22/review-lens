package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/** Which host a checkout talks to, from `origin`'s URL (spec/gitlab.md, "Picking the host"), and fetching a review's head. */
class ReviewHostTest {

    // gh pr list's real shape (JetBrains/intellij-platform-plugin-template #598); the second asks a user and a team (no login)
    private val ghList = """[
        {"additions":1,"author":{"id":"U1","is_bot":false,"login":"SimY4","name":"Alex"},"baseRefName":"main","body":"x","changedFiles":1,"deletions":0,
         "headRefName":"fix/zip","headRefOid":"2f9d9ea","number":598,"reviewRequests":[],"title":"chore: zip","updatedAt":"2026-07-23T01:09:36Z"},
        {"additions":186,"author":{"id":"U2","is_bot":false,"login":"nikitavilkov","name":""},"baseRefName":"main","body":"","changedFiles":12,"deletions":166,
         "headRefName":"feature/chat","headRefOid":"e9c9c9d","number":597,"title":"AI chat demo","updatedAt":"2026-07-20T10:00:00Z",
         "reviewRequests":[{"__typename":"Team","name":"core","slug":"core"},{"__typename":"User","login":"me"}]}
    ]"""

    @Test
    fun `GitHub's PR list becomes host-neutral rows, waiting on you only when a request names you`() {
        val (a, b) = parsePullRequests(ghList, "me")
        assertEquals(PullRequest(598, "chore: zip", "fix/zip", "2f9d9ea", "main", Person("SimY4", "Alex"), "x", 1, 0, 1, "2026-07-23T01:09:36Z", false), a)
        assertEquals(Person("nikitavilkov", "nikitavilkov"), b.author) // a blank name shows the login
        assertEquals(true, b.requested)
        assertEquals("a team request names nobody", false, parsePullRequests(ghList, "core")[1].requested)
        assertEquals("viewer unknown: nothing waits", false, parsePullRequests(ghList, null)[1].requested)
    }

    // Every "GitHub" the UI shows comes from the host's name (another host's name must show through); before any host is picked the text stays neutral.
    @Test
    fun `the UI says the picked host's name, and a neutral word before one is picked`() {
        assertEquals("GitHub", GhClient(java.nio.file.Path.of(".")).name)
        assertEquals("your code host", ReviewHosts.nameOf(null))
        assertEquals("on GitLab", CommentLook.POSTED.label("GitLab"))
        assertEquals("GitLab sync failed · kept on this laptop", ReviewService.Sync.FAILED.text("GitLab"))
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

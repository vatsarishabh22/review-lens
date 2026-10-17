package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** spec/fixtures is the state every editor shares: the real readers must load each file and lose no field. */
class SpecFixturesTest {

    private val fixtures: Path = Path.of("../spec/fixtures")
    private val repo: Path = Files.createTempDirectory("review-lens-spec").also {
        check(ProcessBuilder("git", "init", "-q").directory(it.toFile()).start().waitFor() == 0)
    }
    private val git = GitClient(repo)

    private fun install(name: String): String {
        val text = Files.readString(fixtures.resolve(name))
        Files.createDirectories(git.gitDir.resolve("review-lens"))
        Files.writeString(git.gitDir.resolve("review-lens/$name"), text)
        return text
    }

    private fun assertSameJson(expected: String, actual: Any) =
        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(Gson().toJson(actual)))

    @Test
    fun `active review loads whole`() {
        val text = install("active.json")
        assertSameJson(text, ReviewLifecycle(git).active()!!)
    }

    @Test
    fun `every PR and branch state file loads whole`() {
        val files = Files.list(fixtures).use { s -> s.map { it.fileName.toString() }.filter { it != "active.json" }.toList() }
        assertTrue("no fixtures found in $fixtures", files.isNotEmpty())
        for (name in files) assertSameJson(install(name), ReviewMeta.load(git, name.removeSuffix(".json"))!!)
    }
}

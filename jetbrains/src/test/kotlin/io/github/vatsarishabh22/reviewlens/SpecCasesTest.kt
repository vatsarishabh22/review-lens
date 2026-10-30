package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.vatsarishabh22.reviewlens.github.githubRefusal
import io.github.vatsarishabh22.reviewlens.github.parseViewedStates
import io.github.vatsarishabh22.reviewlens.github.summarizeChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.nio.file.Path

/**
 * spec/cases: the rules every editor implements, as shared cases (spec/README.md, Cases). Each file's rule runs through
 * its adapter below, which calls the real function; a file with no adapter, or an adapter with no file, fails the run.
 */
@RunWith(Parameterized::class)
class SpecCasesTest(private val rule: String) {

    @Test
    fun `every case gives what the spec expects`() {
        val file = JsonParser.parseString(Files.readString(CASES.resolve("$rule.json"))).asJsonObject
        assertEquals("the file names its rule", rule, file["rule"].asString)
        val run = ADAPTERS[rule] ?: return fail("no adapter for spec/cases/$rule.json")
        val wrong = file["cases"].asJsonArray.map { it.asJsonObject }.mapNotNull { case ->
            val got = runCatching { run(case["given"].asJsonObject) }.getOrElse { JsonObject().apply { addProperty("error", true) } }
            val expect = case["expect"] ?: JsonNull.INSTANCE
            if (got == expect) null else "${case["name"].asString}\n  expected ${PRETTY.toJson(expect)}\n  got      ${PRETTY.toJson(got)}"
        }
        if (wrong.isNotEmpty()) fail("$rule:\n" + wrong.joinToString("\n"))
    }

    companion object {
        private val CASES: Path = Path.of("../spec/cases")
        private val GSON = Gson()
        private val PRETTY = GsonBuilder().serializeNulls().create()

        private fun tree(value: Any?): JsonElement = if (value == null) JsonNull.INSTANCE else GSON.toJsonTree(value)
        private fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
        private fun JsonObject.strings(key: String): List<String> = getAsJsonArray(key).map { it.asString }
        private fun JsonObject.map(key: String): Map<String, String> = getAsJsonObject(key).entrySet().associate { it.key to it.value.asString }
        private fun JsonObject.flags(key: String): Map<String, Boolean> = get(key)?.takeUnless { it.isJsonNull }?.asJsonObject?.entrySet()?.associate { it.key to it.value.asBoolean }.orEmpty()
        private fun JsonObject.states(key: String): Map<String, ViewedState> = getAsJsonObject(key).entrySet().associate { it.key to ViewedState.valueOf(it.value.asString) }
        private fun JsonObject.changes(key: String): List<FileChange> = getAsJsonArray(key).map { it.asJsonObject.let { c -> FileChange(c.str("path")!!, null, 'M', c.str("newBlob")!!, null, null) } }
        private fun JsonObject.change(key: String) = getAsJsonObject(key).let { FileChange(it.str("path")!!, null, 'M', it.str("newBlob")!!, null, null) }
        private fun commits(shas: List<String>) = shas.map { Commit(it, "") }
        private fun range(range: ScopeRange, label: String) = JsonObject().apply { addProperty("from", range.from); addProperty("to", range.to); addProperty("label", label) }
        private fun meta(reviewed: Map<String, String>, unsent: Map<String, Boolean>) =
            ReviewMeta(ReviewTarget.forPr(1, "t", "h", "origin/main"), ScopeRange("f", "t"), "All", reviewed, unsent = unsent.ifEmpty { null })

        /** Rule name (the file's path under spec/cases, without .json) -> the real function, JSON in and out. */
        internal val ADAPTERS: Map<String, (JsonObject) -> JsonElement> = mapOf(
            "scope" to { g ->
                val all = commits(g.strings("commits")); val picked = all.filter { it.sha in g.strings("picked") }
                range(resolveScope(g.str("mergeBase")!!, all, picked), scopeLabel(all, picked))
            },
            "since-last-review" to { g -> sinceLastReview(g.str("head")!!, commits(g.strings("commits")), g.str("seenHead"))?.let { (r, label) -> range(r, label) } ?: JsonNull.INSTANCE },
            "viewed-merge" to { g -> tree(mergeViewed(g.map("local"), g.states("host"), g.changes("changes"), g.flags("unsent"))) },
            "viewed-sync" to { g ->
                val tried = mutableListOf<String>()
                val synced = syncViewed(meta(g.map("reviewed"), g.flags("unsent")), g.states("host"), g.changes("changes")) { path, _ -> tried += path; path in g.strings("accepts") }
                JsonObject().apply { add("reviewed", tree(synced.reviewed)); add("unsent", tree(synced.unsent)); add("tried", tree(tried)) }
            },
            "finish-selection" to { g ->
                val drafts = GSON.fromJson(g["drafts"], Array<Draft>::class.java).toList()
                val threads = g["threads"].takeUnless { it.isJsonNull }?.asJsonArray?.map { thread(it.asString) }
                val scope = g.changes("changes")
                JsonObject().apply { add("comments", tree(toPost(drafts, scope).map { it.body })); add("replies", tree(repliesToPost(drafts, threads).map { it.body })) }
            },
            "state-key" to { g -> tree(g["pr"]?.let { ReviewTarget.forPr(it.asInt, "t", "h", "origin/main").key } ?: ReviewTarget.forBranch(g.str("branch")!!, "origin/main").key) },
            "badge" to { g -> tree(badgeOf(g.map("reviewed"), g.change("change")).symbol) },
            "git-changes" to { g ->
                tree(parseChanges(g.str("raw")!!, g.str("numstat")!!).map { c ->
                    linkedMapOf("path" to c.path, "oldPath" to c.oldPath, "status" to c.status.toString(), "newBlob" to c.newBlob, "added" to c.added, "deleted" to c.deleted)
                }).also { nulls(it) }
            },
            "git-blame" to { g ->
                val blame = parseBlame(g.str("porcelain")!!)
                JsonObject().apply {
                    add("lines", tree(blame.lines.mapKeys { it.key.toString() }))
                    add("commits", tree(blame.commits.mapValues { (_, c) -> linkedMapOf("author" to c.author, "time" to c.time, "summary" to c.summary) }))
                }
            },
            "suggestion" to { g ->
                tree(bodyParts(g.str("body")!!).map { part -> when (part) { is BodyPart.Text -> mapOf("text" to part.markdown); is BodyPart.Suggestion -> mapOf("suggestion" to part.lines) } })
            },
            "github/viewed-answer" to { g ->
                parseViewedStates(g.str("text")!!).let { s -> JsonObject().apply { addProperty("pullRequestId", s.pullRequestId); add("files", tree(s.files.mapValues { it.value.name })) } }
            },
            "github/refusal" to { g -> tree(githubRefusal(g.str("body")!!)) },
            "github/checks" to { g -> tree(summarizeChecks(GSON.toJson(g["json"])).text) },
        )

        private fun thread(id: String) = ReviewThread(id, "a.py", 7, 7, false, "h", false, null, "u", false, listOf(ThreadComment("lee", "why?")))

        // Gson drops null fields from a map it serialises; the cases spell them out
        private fun nulls(changes: JsonElement) = changes.asJsonArray.forEach { c ->
            listOf("oldPath", "added", "deleted").forEach { key -> if (!c.asJsonObject.has(key)) c.asJsonObject.add(key, JsonNull.INSTANCE) }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rules(): List<String> = Files.walk(CASES).use { files ->
            files.filter { it.toString().endsWith(".json") }.map { CASES.relativize(it).toString().removeSuffix(".json") }.sorted().toList()
        }
    }
}

class SpecCasesCoverageTest {
    /** Both ways: every case file has an adapter, and every adapter a case file. */
    @Test
    fun `the adapters and the case files match`() = assertEquals(SpecCasesTest.ADAPTERS.keys.sorted(), SpecCasesTest.rules())
}

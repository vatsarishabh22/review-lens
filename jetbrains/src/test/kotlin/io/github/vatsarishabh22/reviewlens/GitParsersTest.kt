package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixtures are real git 2.x output captured from a throwaway repo (`|` stands for NUL). */
class GitParsersTest {
    private fun z(s: String) = s.replace('|', '\u0000')

    private val raw = z(
        ":100644 100644 789819226 e3990a77 M|app.py|" +
            ":000000 100644 0000000 b3c5a95f A|helpers.py|" +
            ":000000 100644 0000000 bdc955b7 A|img.bin|" +
            ":100644 100644 3e757656 3e757656 R100|new.py|fresh.py|" +
            ":100644 000000 286c5f57 0000000 D|old.py|"
    )
    private val numstat = z("2\t0\tapp.py|5\t0\thelpers.py|-\t-\timg.bin|0\t0\t|new.py|fresh.py|0\t1\told.py|")

    @Test
    fun `joins status, blob and line counts per file, including renames and binaries`() {
        assertEquals(
            listOf(
                FileChange("app.py", null, 'M', "e3990a77", 2, 0),
                FileChange("fresh.py", "new.py", 'R', "3e757656", 0, 0),
                FileChange("helpers.py", null, 'A', "b3c5a95f", 5, 0),
                FileChange("img.bin", null, 'A', "bdc955b7", null, null),
                FileChange("old.py", null, 'D', "0000000", 0, 1),
            ),
            parseChanges(raw, numstat),
        )
    }

    @Test
    fun `blame maps each line to its commit and keeps commit details from the first occurrence`() {
        val porcelain = """
            ca9f384e109382e731a3c180be2c661fe2471040 1 1 1
            author t
            author-time 1792142140
            summary base
            boundary
            filename app.py
            ${"\t"}a
            75d1157b6605b905252e9cb255f54ee927c75e0d 2 2 2
            author Alice Chen
            author-time 1792142141
            summary c1
            filename app.py
            ${"\t"}a2
            75d1157b6605b905252e9cb255f54ee927c75e0d 3 3
            ${"\t"}a3
        """.trimIndent()
        val blame = parseBlame(porcelain)
        val c1 = "75d1157b6605b905252e9cb255f54ee927c75e0d"
        assertEquals(mapOf(1 to "ca9f384e109382e731a3c180be2c661fe2471040", 2 to c1, 3 to c1), blame.lines)
        assertEquals(BlameCommit(c1, "Alice Chen", 1792142141, "c1"), blame.commits[c1])
    }
}

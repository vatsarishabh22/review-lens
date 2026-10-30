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

    @Test
    fun `changed lines read -U0 hunks per file, both sides, a deleted and a renamed file included`() {
        val diff = """
            diff --git app.py app.py
            index 1111111..2222222 100644
            --- app.py
            +++ app.py
            @@ -4 +4 @@ def apply(order):
            -    return 1
            +    return 2
            @@ -9,2 +8,0 @@ def run():
            -    a = 1
            -    b = 2
            @@ -20,0 +20,3 @@
            +x = 1
            +y = 2
            +z = 3
            diff --git old.py old.py
            deleted file mode 100644
            --- old.py
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -def legacy():
            -    pass
            diff --git a.py b.py
            similarity index 90%
            rename from a.py
            rename to b.py
            --- a.py
            +++ b.py
            @@ -3 +3 @@
            -    v = 1
            +    v = 2
        """.trimIndent()
        val changes = parseChangedLines(diff)
        assertEquals(LineChanges("app.py", setOf(4, 20, 21, 22), setOf(4, 9, 10)), changes["app.py"])
        assertEquals(LineChanges("old.py", emptySet(), setOf(1, 2)), changes["old.py"])
        assertEquals(LineChanges("a.py", setOf(3), setOf(3)), changes["b.py"])
    }
}


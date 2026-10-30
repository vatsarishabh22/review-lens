package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** Real git 2.x output; `git diff --raw/--numstat` and `blame --porcelain` are spec/cases/git-*.json. */
class GitParsersTest {
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


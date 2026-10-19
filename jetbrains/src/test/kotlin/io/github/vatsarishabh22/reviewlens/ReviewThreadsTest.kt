package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewThreadsTest {

    // Shaped like REVIEW_THREADS_JQ's output for a public PR (nessalabs/skills#26): GitHub moved the open thread from 192 to 194 at the head
    private val head = "bfd851a0c0ffee"
    private val text = """
        {"path":"a.md","line":194,"originalLine":192,"diffSide":"RIGHT","isResolved":false,"url":"u1","commit":"$head","mine":false,"comments":[{"author":"bot","body":"Fix this"},{"author":"me","body":"Done"}]}
        {"path":"a.md","line":null,"originalLine":44,"diffSide":"RIGHT","isResolved":false,"url":"u2","commit":"e52631d","mine":true,"comments":[{"author":"me","body":"Old"}]}
        {"path":"a.md","line":12,"originalLine":12,"diffSide":"RIGHT","isResolved":true,"url":"u3","commit":"$head","mine":false,"comments":[{"author":"bot","body":"Nit"}]}
        {"path":"b.py","line":3,"originalLine":3,"diffSide":"LEFT","isResolved":false,"url":"u4","commit":"$head","mine":false,"comments":[{"author":"bot","body":"Removed line"}]}
        {"path":"b.py","line":7,"originalLine":5,"diffSide":"RIGHT","isResolved":false,"url":"u5","commit":"newer01","mine":false,"comments":[{"author":"bot","body":"On a newer push"}]}
        {"path":"c.py","line":1,"originalLine":1,"diffSide":"RIGHT","isResolved":false,"url":"u6","commit":"$head","mine":false,"comments":[]}
    """.trimIndent()

    private val threads = parseReviewThreads(text)

    @Test fun `reads every thread that has a comment, with its replies`() {
        assertEquals(listOf("u1", "u2", "u3", "u4", "u5"), threads.map { it.url })
        assertEquals(listOf("bot", "me"), threads[0].comments.map { it.author })
        assertTrue(threads[1].mine)
    }

    @Test fun `state is resolved first, then outdated when GitHub has no line, else open`() {
        assertEquals(listOf(ThreadState.OPEN, ThreadState.OUTDATED, ThreadState.RESOLVED, ThreadState.OPEN, ThreadState.OPEN), threads.map { it.state })
    }

    @Test fun `a thread sits under GitHub's line only in the version GitHub anchored it to, on the new side`() {
        assertEquals(194, threads[0].lineAt(head))
        assertNull("outdated", threads[1].lineAt(head))
        assertEquals("resolved still placed, folded by the editor", 12, threads[2].lineAt(head))
        assertNull("old side of the diff", threads[3].lineAt(head))
        assertNull("anchored to a push this checkout doesn't show", threads[4].lineAt(head))
        assertNull("a narrower scope ends before the head", threads[0].lineAt("a1f3c2e"))
    }

    @Test fun `the list says where a thread is, or was`() {
        assertEquals("line 194", threads[0].where)
        assertEquals("line 44 on an older push", threads[1].where)
    }

    @Test fun `a preview is the first line of text with markdown dropped and identifiers kept`() {
        // a real bot comment's opening (nessalabs/skills#26)
        assertEquals("P1 Badge  Preserve valid module-local regression coverage",
            previewLine("**<sub><sub>![P1 Badge](https://img.shields.io/badge/P1-orange?style=flat)</sub></sub>  Preserve valid module-local regression coverage**\n\nFor defects…"))
        assertEquals("Is with_retries worth its own module?", previewLine("\n\nIs `with_retries` worth its own module?"))
        assertEquals("Heading", previewLine("## Heading"))
    }
}

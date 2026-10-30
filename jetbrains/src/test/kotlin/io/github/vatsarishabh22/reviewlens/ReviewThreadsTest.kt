package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewThreadsTest {
    @Test fun `a preview is the first line of text with markdown dropped and identifiers kept`() {
        // a real bot comment's opening (nessalabs/skills#26)
        assertEquals("P1 Badge  Preserve valid module-local regression coverage",
            previewLine("**<sub><sub>![P1 Badge](https://img.shields.io/badge/P1-orange?style=flat)</sub></sub>  Preserve valid module-local regression coverage**\n\nFor defects…"))
        assertEquals("Is with_retries worth its own module?", previewLine("\n\nIs `with_retries` worth its own module?"))
        assertEquals("Heading", previewLine("## Heading"))
    }
}

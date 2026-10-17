package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PR body as the detail view shows it. The body below is PR #2's own shape. */
class PrDescriptionTest {

    @Test
    fun `markdown renders as HTML, whole`() {
        val html = markdownHtml("## What\n\n- **Inline comments:** hover a line\n- `gh` can't list\n\n" + "long line. ".repeat(100) + "THE END")!!
        assertTrue(html, "<h2>What</h2>" in html && "<strong>Inline comments:</strong>" in html && "<code>gh</code>" in html && "<li>" in html)
        assertTrue("never cut off", "THE END" in html)
    }

    @Test
    fun `template comments and images drop out, the alt text stays`() {
        val html = markdownHtml("<!-- describe your change -->\nSee ![the new bar](https://github.com/user-attachments/assets/x.png) and ![](https://x/y.png)")!!
        assertFalse(html, "describe your change" in html || "<img" in html)
        assertTrue(html, "the new bar" in html)
    }

    @Test
    fun `an empty or template-only body shows nothing`() {
        assertNull(markdownHtml(""))
        assertNull(markdownHtml("<!-- fill me -->\r\n  "))
    }
}

package io.github.vatsarishabh22.reviewlens

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** The shared list of code files, as the plugin ships it and as spec/ keeps it for every editor. */
class CodeFilesTest {

    @Test
    fun `code files name their language, data and prose files have none`() {
        assertEquals("Go", CodeFiles.languageOf("main.go"))
        assertEquals("Python", CodeFiles.languageOf("refunds.PY"))
        assertEquals("TypeScript", CodeFiles.languageOf("api.ts"))
        listOf("config.yaml", "package.json", "schema.sql", "README.md", "Makefile").forEach { assertNull(it, CodeFiles.languageOf(it)) }
    }

    @Test
    fun `every extension sits in one row, lower case and without its dot`() {
        val rows = JsonParser.parseString(Files.readString(Path.of("../spec/code-files.json"))).asJsonObject["languages"].asJsonArray
        val extensions = rows.flatMap { row -> row.asJsonObject["extensions"].asJsonArray.map { it.asString } }
        assertEquals(extensions.size, extensions.toSet().size)
        assertTrue(extensions.all { it == it.lowercase() && !it.contains('.') })
    }
}

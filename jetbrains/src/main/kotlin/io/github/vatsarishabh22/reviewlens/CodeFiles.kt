package io.github.vatsarishabh22.reviewlens

import com.google.gson.Gson

/**
 * Which files are code, read from the editors' shared list (spec/code-files.json, GitHub Linguist's programming
 * languages): a changed file in a language the IDE can't analyse says so, where a data or prose file stays silent.
 */
object CodeFiles {
    private class Row(val name: String, val extensions: List<String>)
    private class Table(val languages: List<Row>)

    private val byExtension: Map<String, String> by lazy {
        val table = CodeFiles::class.java.getResourceAsStream("/spec/code-files.json")!!.reader().use { Gson().fromJson(it, Table::class.java) }
        table.languages.flatMap { row -> row.extensions.map { it to row.name } }.toMap()
    }

    /** The language [fileName] is written in when it is code (`main.go` → Go), else null (`config.yaml`, `README.md`). */
    fun languageOf(fileName: String): String? = byExtension[fileName.substringAfterLast('.', "").lowercase()]
}

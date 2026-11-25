package io.github.vatsarishabh22.reviewlens

import io.github.vatsarishabh22.reviewlens.services.FINISH_REVIEW
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** Each layer imports only the layers below it (CONTRIBUTING.md, Code): ui → services → host → core, impact → core. */
class LayersTest {
    private val root = Path.of("src/main/kotlin/io/github/vatsarishabh22/reviewlens")

    /** What each package may import of ours; only `host` reaches into a host's own package. */
    private val allowed = mapOf(
        "core" to setOf("core"),
        "impact" to setOf("core", "impact"),
        "host" to setOf("core", "host", "host.github"),
        "host.github" to setOf("core", "host.github"),
        "services" to setOf("core", "host", "impact", "services"),
        "ui" to setOf("core", "host", "impact", "services", "ui"),
    )

    private fun packageOf(file: Path) = root.relativize(file.parent).joinToString(".")

    /** The packages of ours [file]'s code uses, by import or fully qualified name; comments and its own `package` line don't count. */
    private fun uses(file: Path): List<String> = Files.readAllLines(file).map { it.trim() }
        .filterNot { it.startsWith("package ") || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .flatMap { line -> Regex("""io\.github\.vatsarishabh22\.reviewlens\.([\w.]+)\.""").findAll(line).mapNotNull { m -> allowed.keys.sortedByDescending { it.length }.firstOrNull { (m.groupValues[1] + ".").startsWith("$it.") } }.toList() }

    /** Every use in [file] its layer may not make, as "File.kt → pkg". */
    private fun breaks(file: Path) = uses(file).filter { it !in allowed.getValue(packageOf(file)) }.map { "${file.fileName} → $it" }

    private fun sources() = Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }

    @Test
    fun `every package is a known layer`() = assertEquals(emptyList<String>(), sources().map(::packageOf).distinct().filter { it !in allowed })

    @Test
    fun `no layer imports one above it`() = assertEquals(emptyList<String>(), sources().flatMap(::breaks))

    /** A host's own package (`host.github`, later `host.gitlab`) is reached only through the picker. */
    @Test
    fun `only ReviewHosts imports a host's package`() = assertEquals(listOf("ReviewHosts.kt"),
        sources().filter { file -> uses(file).any { it.startsWith("host.") && it != packageOf(file) } }.map { it.fileName.toString() })

    /** A service offers UI by an action id (CONTRIBUTING.md); a typo there makes `getAction` null and the balloon throw. */
    @Test
    fun `the Finish action id a service names is declared in plugin_xml`() =
        assertEquals(true, Files.readString(Path.of("src/main/resources/META-INF/plugin.xml")).contains("id=\"$FINISH_REVIEW\""))

    /** IntelliJ's process runner and logger only (Cli runs git through them); nothing else of the IDE, no UI. */
    private val coreMayUse = listOf("import com.intellij.execution.", "import com.intellij.openapi.diagnostic.")

    @Test
    fun `core uses no IntelliJ beyond its process runner and logger`() = assertEquals(emptyList<String>(),
        sources().filter { packageOf(it) == "core" }.flatMap { file ->
            Files.readAllLines(file).filter { l -> (l.startsWith("import com.intellij.") && coreMayUse.none(l::startsWith)) || l.startsWith("import javax.swing.") || l.startsWith("import java.awt.") }
                .map { "${file.fileName}: $it" }
        })
}

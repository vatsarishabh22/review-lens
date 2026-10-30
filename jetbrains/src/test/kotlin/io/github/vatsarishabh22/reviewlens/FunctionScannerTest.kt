package io.github.vatsarishabh22.reviewlens

import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

/** The changed lines between two versions of a file, as real `git diff -U0` reports them. */
fun lineChanges(before: String, after: String): LineChanges {
    val dir = Files.createTempDirectory("review-lens-impact")
    Files.writeString(dir.resolve("a"), before); Files.writeString(dir.resolve("b"), after)
    val diff = ProcessBuilder("git", "diff", "--no-index", "-U0", "--no-prefix", "a", "b").directory(dir.toFile()).start()
        .inputStream.bufferedReader().readText()
    return parseChangedLines(diff).values.single()
}

/** Real file versions, real `git diff -U0`, real PSI: which functions the impact map would map, and how. */
class FunctionScannerTest : BasePlatformTestCase() {

    private fun changed(name: String, before: String, after: String, language: ImpactLanguage): Map<String, ChangeKind> {
        val lines = lineChanges(before, after)
        fun version(text: String) = FunctionScanner.scan(PsiFileFactory.getInstance(project).createFileFromText(name, language.language, text), language)
        return changedFunctions(version(before), version(after), lines).associate { it.name to it.kind }
    }

    fun `test Python - body, decorator, comment-only, docstring, new and removed`() {
        val before = """
            @retry(times=3)
            def apply(order, amount):
                # refunds never exceed the order
                return min(amount, order.total)


            def describe(order):
                return order.ref


            def notify(order):
                send(order)


            def legacy(order):
                return None
        """.trimIndent() + "\n"
        val after = """
            @retry(times=5)
            def apply(order, amount):
                # refunds never exceed the order total
                return min(amount, order.total)


            def describe(order):
                return order.ref


            def notify(order):
                send(order, urgent=True)


            def fresh(order):
                return order
        """.trimIndent() + "\n"
        // apply: the decorator is header (the comment edit alone wouldn't count); notify: body; legacy removed; fresh is new
        assertEquals(mapOf("apply" to ChangeKind.INPUTS, "notify" to ChangeKind.LOGIC, "legacy" to ChangeKind.REMOVED),
            changed("refunds.py", before, after, lang<PythonImpact>()))
    }

    fun `test Python - a comment-only change maps nothing`() {
        val before = "def apply(order):\n    # old note\n    return order\n"
        val after = "def apply(order):\n    # new note\n\n    return order\n"
        assertEquals(emptyMap<String, ChangeKind>(), changed("refunds.py", before, after, lang<PythonImpact>()))
    }

    fun `test Java - Javadoc-only is nothing, a parameter is inputs, overloads stay apart`() {
        val before = """
            class Refunds {
                /** Applies a refund. */
                int apply(int amount) { return amount; }
                int apply(int amount, int fee) { return amount - fee; }
                int total(int a) { return a; }
            }
        """.trimIndent() + "\n"
        val after = """
            class Refunds {
                /** Applies a refund, in cents. */
                int apply(int amount) { return amount; }
                int apply(int amount, int fee) { return amount - fee * 2; }
                int total(long a) { return (int) a; }
            }
        """.trimIndent() + "\n"
        assertEquals(mapOf("Refunds.apply#2" to ChangeKind.INPUTS, "Refunds.total" to ChangeKind.INPUTS),
            changed("Refunds.java", before, after, lang<JavaImpact>()))
    }

    fun `test Kotlin - a block body line is logic, an expression body after the equals is logic`() {
        val before = """
            class Refunds {
                fun apply(amount: Int): Int {
                    val capped = amount
                    return capped
                }
                fun twice(amount: Int) =
                    amount * 2
            }
        """.trimIndent() + "\n"
        val after = """
            class Refunds {
                fun apply(amount: Int): Int {
                    val capped = minOf(amount, 100)
                    return capped
                }
                fun twice(amount: Int) =
                    amount * 3
            }
        """.trimIndent() + "\n"
        assertEquals(mapOf("Refunds.apply" to ChangeKind.LOGIC, "Refunds.twice" to ChangeKind.LOGIC),
            changed("Refunds.kt", before, after, lang<KotlinImpact>()))
    }
}

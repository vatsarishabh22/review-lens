package io.github.vatsarishabh22.reviewlens

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Each language's answers on a real file: which declarations are functions, their headers, names and test files. */
class ImpactLanguageTest : BasePlatformTestCase() {

    private fun functions(name: String, text: String, language: ImpactLanguage): Map<String, String> {
        val file = myFixture.configureByText(name, text)
        return PsiTreeUtil.findChildrenOfType(file, PsiElement::class.java).filter(language::isFunction)
            .associate { language.qualifiedName(it) to language.header(it).substring(file.text).trim() }
    }

    fun `test Python - decorators are header, classes and lambdas are not functions, nested names are dotted`() {
        val python = ImpactLanguage.EP.extensionList.single { it is PythonImpact }
        val found = functions("refunds.py", """
            @retry(times=3)
            def apply(order, amount):
                return amount

            class Refunds:
                def run(self, x):
                    def inner():
                        pass
                    return inner

            handler = lambda x: x
        """.trimIndent(), python)
        assertEquals(listOf("apply", "Refunds.run", "Refunds.run.inner"), found.keys.toList())
        assertEquals("@retry(times=3)\ndef apply(order, amount):", found["apply"])
        assertTrue(python.testFileName.matches("test_refunds.py") && python.testFileName.matches("refunds_test.py"))
        assertFalse(python.testFileName.matches("refunds.py"))
    }

    fun `test Java - annotations are header, constructors count, a bodiless method is all header, fields are not functions`() {
        val java = ImpactLanguage.EP.extensionList.single { it is JavaImpact }
        val found = functions("Refunds.java", """
            class Refunds {
                int total;
                Refunds() { total = 0; }
                @Deprecated
                int apply(int amount) { return amount; }
                interface Gateway { void send(int amount); }
            }
        """.trimIndent(), java)
        assertEquals(setOf("Refunds.Refunds", "Refunds.apply", "Refunds.Gateway.send"), found.keys)
        assertEquals("@Deprecated\n    int apply(int amount)", found["Refunds.apply"])
        assertEquals("void send(int amount);", found["Refunds.Gateway.send"])
        assertTrue(java.testFileName.matches("RefundsTest.java"))
        assertFalse(java.testFileName.matches("Refunds.java"))
    }

    fun `test Kotlin - block and expression bodies, properties and lambdas are not functions`() {
        val kotlin = ImpactLanguage.EP.extensionList.single { it is KotlinImpact }
        val found = functions("Refunds.kt", """
            class Refunds {
                val total = 0
                @Deprecated("old")
                fun apply(amount: Int): Int {
                    return amount
                }
                fun twice(amount: Int) = amount * 2
            }
            val handler = { x: Int -> x }
            fun top() {}
        """.trimIndent(), kotlin)
        assertEquals(setOf("Refunds.apply", "Refunds.twice", "top"), found.keys)
        assertEquals("@Deprecated(\"old\")\n    fun apply(amount: Int): Int", found["Refunds.apply"])
        assertEquals("fun twice(amount: Int)", found["Refunds.twice"])
        assertTrue(kotlin.testFileName.matches("RefundsTest.kt"))
        assertFalse(kotlin.testFileName.matches("Refunds.kt"))
    }

    fun `test a language with no implementation has none`() {
        assertNull(ImpactLanguage.of(com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE))
    }
}

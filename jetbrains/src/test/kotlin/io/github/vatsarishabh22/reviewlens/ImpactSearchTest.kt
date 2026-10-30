package io.github.vatsarishabh22.reviewlens

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import kotlin.time.Duration

/** A real Python project, real references: which places the impact map finds for a changed function. */
class ImpactSearchTest : BasePlatformTestCase() {

    private fun impacts(language: ImpactLanguage, path: String, before: String, files: Map<String, String>, inPr: Set<String> = setOf(path),
                        budget: Duration = Duration.INFINITE) = searchImpacts(myFixture, language, path, before, files, inPr, budget)

    private val refundsBefore = """
        def calculate_refund(order):
            return order.total


        def legacy(order):
            return 0
    """.trimIndent() + "\n"
    private val refundsAfter = "def calculate_refund(order):\n    return order.total - order.fee\n"

    fun `test Python - places outside the PR, by declaration, split into code and tests`() {
        val found = impacts(lang<PythonImpact>(), "app/payments.py", refundsBefore, mapOf(
            "app/payments.py" to refundsAfter,
            "app/admin.py" to """
                from app.payments import calculate_refund


                def issue_refund(o):
                    amt = calculate_refund(o)
                    return calculate_refund(amt)
            """.trimIndent(),
            "app/invoice.py" to "from app import payments\n\ntotal = payments.calculate_refund(None)\n",
            "app/refund_view.py" to "from app.payments import calculate_refund\n\n\ndef post(o):\n    return calculate_refund(o)\n",
            "vendor/lib.py" to "from app.payments import calculate_refund\n\nx = calculate_refund(1)\n",
            "tests/test_refund.py" to "from app.payments import calculate_refund\n\n\ndef test_floor():\n    assert calculate_refund(1) == 1\n",
        ), inPr = setOf("app/payments.py", "app/refund_view.py"))
        val uses = found.getValue("app/payments.py#calculate_refund") as Uses.Found
        // one place per declaration, at its first use; import lines, the PR's own refund_view.py and vendor/ are dropped
        assertEquals(listOf(
            "app/admin.py#issue_refund :5 calculate_refund",
            "app/invoice.py#<top level> :3 calculate_refund",
        ), listed(uses.code))
        assertEquals(listOf("tests/test_refund.py#test_floor :5 calculate_refund"), listed(uses.tests))
    }

    fun `test Python - a removed function still named where it no longer resolves, untyped calls left out`() {
        val found = impacts(lang<PythonImpact>(), "app/payments.py", refundsBefore, mapOf(
            "app/payments.py" to refundsAfter,
            "app/old.py" to """
                from app.payments import legacy


                def old_path(o):
                    return legacy(o)


                def maybe(x):
                    return x.legacy()
            """.trimIndent(),
            "app/other.py" to "def legacy():\n    return 1\n\n\ndef fine():\n    return legacy()\n",
            "app/third.py" to "from missing_lib import legacy\n\nlegacy()\n",
            "app/fourth.py" to "from app.payments import gone\n\nlegacy()\n",
        ))
        val uses = found.getValue("app/payments.py#legacy") as Uses.Found
        // other.py's legacy() resolves to its own function; x.legacy() on an untyped x never resolves, so it can't be
        // told; third.py's library isn't installed, so its broken import is no sign of this removal; fourth.py's broken
        // import is another name's
        assertEquals(listOf("app/old.py#old_path :5 legacy"), listed(uses.code))
    }

    fun `test Python - a deleted file's function still imported from it`() {
        val found = impacts(lang<PythonImpact>(), "app/fmt.py", "def money(x):\n    return x\n", mapOf(
            "app/report.py" to "from app.fmt import money\n\n\ndef total():\n    return money(1)\n",
            "app/elsewhere.py" to "from billing.fmt import money\n\n\ndef cost():\n    return money(2)\n",
        ))
        // elsewhere.py's module is another one, missing too: no sign of this deletion
        assertEquals(listOf("app/report.py#total :5 money"), listed((found.getValue("app/fmt.py#money") as Uses.Found).code))
    }

    fun `test Python - a removed method still called on self`() {
        val before = "class Refunds:\n    def run(self):\n        return 1\n\n    def legacy(self):\n        return 0\n"
        val after = "class Refunds:\n    def run(self):\n        return 2\n"
        val found = impacts(lang<PythonImpact>(), "app/payments.py", before, mapOf(
            "app/payments.py" to after,
            "app/sub.py" to "from app.payments import Refunds\n\n\nclass Late(Refunds):\n    def go(self):\n        return self.legacy()\n",
        ))
        assertEquals(listOf("app/sub.py#Late.go :6 legacy"), listed((found.getValue("app/payments.py#Refunds.legacy") as Uses.Found).code))
    }

    fun `test Python - more than five places in a group are counted, not listed`() {
        val files = mapOf("app/payments.py" to refundsAfter) + (1..6).associate {
            "tests/test_r$it.py" to "from app.payments import calculate_refund\n\n\ndef test_$it():\n    assert calculate_refund($it)\n"
        } + ("app/admin.py" to "from app.payments import calculate_refund\n\n\ndef issue(o):\n    return calculate_refund(o)\n")
        val uses = impacts(lang<PythonImpact>(), "app/payments.py", refundsBefore, files).getValue("app/payments.py#calculate_refund") as Uses.Found
        assertEquals(PlaceGroup.Wide(6), uses.tests)
        assertEquals(1, (uses.code as PlaceGroup.Listed).places.size)
    }

    fun `test Python - nothing outside the PR finds nothing`() {
        val alone = impacts(lang<PythonImpact>(), "app/payments.py", refundsBefore, mapOf("app/payments.py" to refundsAfter))
        assertEquals(Uses.Found(PlaceGroup.Listed(emptyList()), PlaceGroup.Listed(emptyList())), alone.getValue("app/payments.py#calculate_refund"))
    }

    fun `test Python - a search past its budget stops`() {
        val found = impacts(lang<PythonImpact>(), "app/payments.py", refundsBefore, mapOf(
            "app/payments.py" to refundsAfter,
            "app/admin.py" to "from app.payments import calculate_refund\n\nx = calculate_refund(1)\n",
        ), budget = Duration.ZERO)
        assertEquals(Uses.TooSlow, found.getValue("app/payments.py#calculate_refund"))
    }
}

inline fun <reified T : ImpactLanguage> lang() = ImpactLanguage.EP.extensionList.single { it is T }

/** Writes [files] into the fixture's project, then searches [path] changed from [before] to its text in [files]. */
fun searchImpacts(fixture: CodeInsightTestFixture, language: ImpactLanguage, path: String, before: String, files: Map<String, String>,
                  inPr: Set<String> = setOf(path), budget: Duration = Duration.INFINITE): Map<String, Uses> {
    files.forEach { (p, text) -> fixture.addFileToProject(p, text) }
    val after = fixture.findFileInTempDir(path)?.let { fixture.psiManager.findFile(it) }
    val old = PsiFileFactory.getInstance(fixture.project).createFileFromText(path.substringAfterLast('/'), language.language, before)
    val pending = ImpactSearch.detect(path, path, language, old, after, lineChanges(before, files[path].orEmpty()))
    return ImpactSearch(fixture.project, { it.path.substringAfter("/src/") }, inPr, budget).impacts(language, after, pending).associate { it.key to it.uses }
}

/** A group's places as `key :line use`, the use cut from its line by the place's columns. */
fun listed(group: PlaceGroup) = (group as PlaceGroup.Listed).places.map { "${it.key} :${it.line} ${it.lines.first().substring(it.columns)}" }

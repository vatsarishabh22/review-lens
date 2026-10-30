package io.github.vatsarishabh22.reviewlens

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

/** A real Java and Kotlin project on a mock JDK, real references: the places the impact map finds. */
class JvmImpactSearchTest : LightJavaCodeInsightFixtureTestCase() {

    private fun impacts(language: ImpactLanguage, path: String, before: String, files: Map<String, String>) =
        searchImpacts(myFixture, language, path, before, files)

    fun `test Java - callers in code and tests, a removed method still called`() {
        val before = "package app;\n\npublic class Refunds {\n    int apply(int a) { return a; }\n    int legacy() { return 0; }\n    public static int stat() { return 1; }\n}\n"
        val after = "package app;\n\npublic class Refunds {\n    int apply(int a) { return a - 1; }\n    public static int stat() { return 2; }\n}\n"
        val found = impacts(lang<JavaImpact>(), "app/Refunds.java", before, mapOf(
            "app/Refunds.java" to after,
            "other/Billing.java" to "package other;\n\nimport static app.Refunds.stat;\n\nclass Billing {\n    int bill() { return stat(); }\n}\n",
            "app/Admin.java" to "package app;\n\nclass Admin {\n    int issue(Refunds r) {\n        return r.apply(1) + r.legacy();\n    }\n}\n",
            "app/RefundsTest.java" to "package app;\n\nclass RefundsTest {\n    void testApply() {\n        new Refunds().apply(2);\n    }\n}\n",
        ))
        val apply = found.getValue("app/Refunds.java#Refunds.apply") as Uses.Found
        assertEquals(listOf("app/Admin.java#Admin.issue :5 apply"), listed(apply.code))
        assertEquals(listOf("app/RefundsTest.java#RefundsTest.testApply :5 apply"), listed(apply.tests))
        // a static import names stat, the call uses it: only the call is a place
        assertEquals(listOf("other/Billing.java#Billing.bill :6 stat"), listed((found.getValue("app/Refunds.java#Refunds.stat") as Uses.Found).code))
        assertEquals(listOf("app/Admin.java#Admin.issue :5 legacy"), listed((found.getValue("app/Refunds.java#Refunds.legacy") as Uses.Found).code))
    }

    fun `test Kotlin - callers in code and tests`() {
        val before = "package app\n\nfun apply(a: Int): Int = a\n"
        val after = "package app\n\nfun apply(a: Int): Int = a - 1\n"
        val found = impacts(lang<KotlinImpact>(), "app/Refunds.kt", before, mapOf(
            "app/Refunds.kt" to after,
            "app/Admin.kt" to "package app\n\nclass Admin {\n    fun issue() = apply(1)\n}\n",
            "other/Billing.kt" to "package other\n\nimport app.apply\n\nfun bill() = apply(3)\n",
            "app/RefundsTest.kt" to "package app\n\nfun testApply() {\n    apply(2)\n}\n",
        ))
        val apply = found.getValue("app/Refunds.kt#apply") as Uses.Found
        assertEquals(listOf("app/Admin.kt#Admin.issue :4 apply", "other/Billing.kt#bill :5 apply"), listed(apply.code))
        assertEquals(listOf("app/RefundsTest.kt#testApply :4 apply"), listed(apply.tests))
    }
}

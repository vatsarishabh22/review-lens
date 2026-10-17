package io.github.vatsarishabh22.reviewlens

import org.junit.Assert.assertEquals
import org.junit.Test

/** `gh pr view --json statusCheckRollup` mixes check runs (status + conclusion) and commit statuses (state). */
class GhClientTest {
    private fun rollup(vararg checks: String) = """{"statusCheckRollup":[${checks.joinToString(",")}]}"""
    private val passed = """{"__typename":"CheckRun","status":"COMPLETED","conclusion":"SUCCESS"}"""
    private val skipped = """{"__typename":"CheckRun","status":"COMPLETED","conclusion":"SKIPPED"}"""
    private val failed = """{"__typename":"CheckRun","status":"COMPLETED","conclusion":"FAILURE"}"""
    private val running = """{"__typename":"CheckRun","status":"IN_PROGRESS","conclusion":""}"""
    private val statusOk = """{"__typename":"StatusContext","state":"SUCCESS"}"""
    private val statusError = """{"__typename":"StatusContext","state":"ERROR"}"""
    private val statusPending = """{"__typename":"StatusContext","state":"PENDING"}"""

    @Test fun `no checks says nothing`() = assertEquals("", summarizeChecks(rollup()).text)
    @Test fun `all green passes, skipped included`() = assertEquals("Checks passed", summarizeChecks(rollup(passed, skipped, statusOk)).text)
    @Test fun `a running check run is running`() = assertEquals("Checks running", summarizeChecks(rollup(passed, running)).text)
    @Test fun `a pending status is running`() = assertEquals("Checks running", summarizeChecks(rollup(passed, statusPending)).text)
    @Test fun `failures win over running and are counted`() =
        assertEquals("2 checks failing", summarizeChecks(rollup(failed, running, statusError)).text)
    @Test fun `one failure is singular`() = assertEquals("1 check failing", summarizeChecks(rollup(passed, failed)).text)
}

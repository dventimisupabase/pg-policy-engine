package com.pgpe.drift

import com.pgpe.ast.*
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class DriftDetectorTest {

    private fun makeExpected(): CompiledState {
        return CompiledState(listOf(
            TableArtifacts(
                table = "users",
                schema = "public",
                enableRls = "ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;",
                forceRls = "ALTER TABLE public.users FORCE ROW LEVEL SECURITY;",
                policies = listOf(
                    CompiledPolicy(
                        "tenant_isolation_users",
                        "users",
                        "CREATE POLICY tenant_isolation_users\n  ON public.users\n  AS PERMISSIVE\n  FOR ALL\n  USING (tenant_id = current_setting('app.tenant_id'));"
                    )
                )
            )
        ))
    }

    @Test
    fun `no drift when expected matches observed`() {
        val expected = makeExpected()
        val observed = ObservedState(listOf(
            ObservedTableState(
                table = "users",
                rlsEnabled = true,
                rlsForced = true,
                policies = listOf(
                    ObservedPolicy(
                        name = "tenant_isolation_users",
                        table = "users",
                        type = "PERMISSIVE",
                        command = "ALL",
                        usingExpr = "tenant_id = current_setting('app.tenant_id')",
                        checkExpr = null
                    )
                )
            )
        ))

        val report = DriftDetector.detectDrift(expected, observed)
        report.items shouldHaveSize 0
    }

    @Test
    fun `detect missing policy`() {
        val expected = makeExpected()
        val observed = ObservedState(listOf(
            ObservedTableState(
                table = "users",
                rlsEnabled = true,
                rlsForced = true,
                policies = emptyList()
            )
        ))

        val report = DriftDetector.detectDrift(expected, observed)
        report.items shouldHaveSize 1
        val item = report.items[0]
        item.shouldBeInstanceOf<DriftItem.MissingPolicy>()
        (item as DriftItem.MissingPolicy).policyName shouldBe "tenant_isolation_users"
        item.severity shouldBe Severity.CRITICAL
    }

    @Test
    fun `detect extra policy`() {
        val expected = makeExpected()
        val observed = ObservedState(listOf(
            ObservedTableState(
                table = "users",
                rlsEnabled = true,
                rlsForced = true,
                policies = listOf(
                    ObservedPolicy(
                        name = "tenant_isolation_users",
                        table = "users",
                        type = "PERMISSIVE",
                        command = "ALL",
                        usingExpr = "tenant_id = current_setting('app.tenant_id')",
                        checkExpr = null
                    ),
                    ObservedPolicy(
                        name = "unmanaged_policy",
                        table = "users",
                        type = "PERMISSIVE",
                        command = "SELECT",
                        usingExpr = "true",
                        checkExpr = null
                    )
                )
            )
        ))

        val report = DriftDetector.detectDrift(expected, observed)
        report.items shouldHaveSize 1
        val item = report.items[0]
        item.shouldBeInstanceOf<DriftItem.ExtraPolicy>()
        (item as DriftItem.ExtraPolicy).policyName shouldBe "unmanaged_policy"
        item.severity shouldBe Severity.WARNING
    }

    @Test
    fun `detect RLS disabled`() {
        val expected = makeExpected()
        val observed = ObservedState(listOf(
            ObservedTableState(
                table = "users",
                rlsEnabled = false,
                rlsForced = true,
                policies = listOf(
                    ObservedPolicy(
                        name = "tenant_isolation_users",
                        table = "users",
                        type = "PERMISSIVE",
                        command = "ALL",
                        usingExpr = "tenant_id = current_setting('app.tenant_id')",
                        checkExpr = null
                    )
                )
            )
        ))

        val report = DriftDetector.detectDrift(expected, observed)
        report.items.any { it is DriftItem.RlsDisabled } shouldBe true
        report.items.first { it is DriftItem.RlsDisabled }.severity shouldBe Severity.CRITICAL
    }

    @Test
    fun `detect RLS not forced`() {
        val expected = makeExpected()
        val observed = ObservedState(listOf(
            ObservedTableState(
                table = "users",
                rlsEnabled = true,
                rlsForced = false,
                policies = listOf(
                    ObservedPolicy(
                        name = "tenant_isolation_users",
                        table = "users",
                        type = "PERMISSIVE",
                        command = "ALL",
                        usingExpr = "tenant_id = current_setting('app.tenant_id')",
                        checkExpr = null
                    )
                )
            )
        ))

        val report = DriftDetector.detectDrift(expected, observed)
        report.items.any { it is DriftItem.RlsNotForced } shouldBe true
        report.items.first { it is DriftItem.RlsNotForced }.severity shouldBe Severity.HIGH
    }

    @Test
    fun `detect modified policy`() {
        val expected = makeExpected()
        val observed = ObservedState(listOf(
            ObservedTableState(
                table = "users",
                rlsEnabled = true,
                rlsForced = true,
                policies = listOf(
                    ObservedPolicy(
                        name = "tenant_isolation_users",
                        table = "users",
                        type = "PERMISSIVE",
                        command = "ALL",
                        usingExpr = "email = 'admin@example.com'",  // Wrong expression!
                        checkExpr = null
                    )
                )
            )
        ))

        val report = DriftDetector.detectDrift(expected, observed)
        report.items.any { it is DriftItem.ModifiedPolicy } shouldBe true
        val item = report.items.first { it is DriftItem.ModifiedPolicy } as DriftItem.ModifiedPolicy
        item.severity shouldBe Severity.CRITICAL
        item.policyName shouldBe "tenant_isolation_users"
    }

    @Test
    fun `reconciler generates SQL for missing policy`() {
        val expected = makeExpected()
        val drift = DriftReport(listOf(
            DriftItem.MissingPolicy("users", "tenant_isolation_users")
        ))

        val sql = Reconciler.reconcile(drift.items, expected)
        sql shouldHaveSize 1
        sql[0].contains("CREATE POLICY") shouldBe true
    }

    @Test
    fun `reconciler generates SQL for RLS disabled`() {
        val expected = makeExpected()
        val drift = DriftReport(listOf(
            DriftItem.RlsDisabled("users")
        ))

        val sql = Reconciler.reconcile(drift.items, expected)
        sql shouldHaveSize 1
        sql[0] shouldBe "ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;"
    }
}

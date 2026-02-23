package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SubsumptionProofTest {

    private val proof = SubsumptionProof()

    private fun context(policies: List<Policy>, tables: List<TableMetadata>): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
    }

    @Test
    fun `broad policy subsumes narrow policy`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        // "all_access" has no tenant_id filter (matches all rows)
        // "tenant_only" filters by tenant_id
        // all_access subsumes tenant_only
        val policies = listOf(
            Policy(
                name = "all_access",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(emptySet()))  // empty clause = true (all rows)
            ),
            Policy(
                name = "tenant_only",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.SubsumptionResult
        r.subsumingPolicy shouldBe "all_access"
        r.subsumedPolicy shouldBe "tenant_only"
        r.table shouldBe "data"
    }

    @Test
    fun `two unrelated policies produce no subsumption`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid"), ColumnInfo("role", "text")
            ))
        )
        val policies = listOf(
            Policy(
                name = "by_tenant",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            ),
            Policy(
                name = "by_role",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Session("app.role"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.StringLit("admin")))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))
        results.shouldBeEmpty()
    }
}

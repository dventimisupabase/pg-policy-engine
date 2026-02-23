package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CoverageProofTest {

    private val proof = CoverageProof()

    private fun context(policies: List<Policy>, tables: List<TableMetadata>): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
    }

    @Test
    fun `table with no policies reports hasPolicies=false`() {
        val tables = listOf(
            TableMetadata("orphan_table", "public", listOf(ColumnInfo("id", "uuid")))
        )
        val results = proof.execute(context(emptyList(), tables))

        results.size shouldBe 1
        val r = results[0] as ProofResult.CoverageResult
        r.table shouldBe "orphan_table"
        r.hasPolicies shouldBe false
    }

    @Test
    fun `table with SELECT-only policy reports missing INSERT, UPDATE, DELETE`() {
        val tables = listOf(
            TableMetadata("partial_table", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val policies = listOf(
            Policy(
                name = "read_only",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("partial_table"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))

        results.size shouldBe 1
        val r = results[0] as ProofResult.CoverageResult
        r.table shouldBe "partial_table"
        r.hasPolicies shouldBe true
        r.missingCommands shouldBe setOf(Command.INSERT, Command.UPDATE, Command.DELETE)
    }

    @Test
    fun `fully covered table emits no result`() {
        val tables = listOf(
            TableMetadata("covered_table", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val policies = listOf(
            Policy(
                name = "full_coverage",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE),
                selector = Selector.Named("covered_table"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))
        results.shouldBeEmpty()
    }
}

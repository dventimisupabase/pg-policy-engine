package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SoftDeleteProofTest {

    private val proof = SoftDeleteProof()

    private fun context(policies: List<Policy>, tables: List<TableMetadata>): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
    }

    @Test
    fun `with restrictive soft_delete policy proves safe (UNSAT)`() {
        val tables = listOf(
            TableMetadata("projects", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("is_deleted", "boolean")
            ))
        )
        val policies = listOf(
            Policy(
                name = "tenant_isolation",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("projects"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            ),
            Policy(
                name = "soft_delete",
                type = PolicyType.RESTRICTIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.HasColumn("is_deleted"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("is_deleted"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.BoolLit(false)))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.SoftDeleteResult
        r.table shouldBe "projects"
        r.status shouldBe SatResult.UNSAT
    }

    @Test
    fun `without soft_delete policy deleted rows are visible (SAT)`() {
        val tables = listOf(
            TableMetadata("projects", "public", listOf(
                ColumnInfo("id", "uuid"),
                ColumnInfo("tenant_id", "uuid"),
                ColumnInfo("is_deleted", "boolean")
            ))
        )
        val policies = listOf(
            Policy(
                name = "tenant_isolation",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("projects"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.SoftDeleteResult
        r.table shouldBe "projects"
        r.status shouldBe SatResult.SAT
    }

    @Test
    fun `table without is_deleted column is skipped`() {
        val tables = listOf(
            TableMetadata("users", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val policies = listOf(
            Policy(
                name = "tenant_isolation",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("users"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))
        results.shouldBeEmpty()
    }
}

package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContradictionProofTest {

    private val proof = ContradictionProof()

    private fun context(policies: List<Policy>, tables: List<TableMetadata>): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
    }

    @Test
    fun `permissive role=admin with restrictive role=viewer detects contradiction`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("role", "text")
            ))
        )
        val policies = listOf(
            Policy(
                name = "admin_only",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Session("app.role"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.StringLit("admin")))
                )))
            ),
            Policy(
                name = "viewer_only",
                type = PolicyType.RESTRICTIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Session("app.role"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.StringLit("viewer")))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.ContradictionResult
        r.table shouldBe "data"
        r.command shouldBe Command.SELECT
    }

    @Test
    fun `normal tenant isolation policy produces no contradictions`() {
        val tables = listOf(
            TableMetadata("users", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val policies = listOf(
            Policy(
                name = "tenant_isolation",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE),
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

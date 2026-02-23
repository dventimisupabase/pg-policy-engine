package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WriteRestrictionProofTest {

    private val proof = WriteRestrictionProof()

    private fun context(policies: List<Policy>, tables: List<TableMetadata>): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
    }

    @Test
    fun `same policy for all commands means writes subset of reads (UNSAT)`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val policies = listOf(
            Policy(
                name = "tenant_isolation",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))
        results.shouldBeEmpty()
    }

    @Test
    fun `weaker SELECT policy means writes exceed reads (SAT)`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid"), ColumnInfo("role", "text")
            ))
        )
        // SELECT requires tenant_id + role check, but INSERT only requires tenant_id
        val policies = listOf(
            Policy(
                name = "read_restricted",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id")),
                    Atom.BinaryAtom(ValueSource.Session("app.role"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.StringLit("admin")))
                )))
            ),
            Policy(
                name = "write_broad",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.INSERT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.WriteRestrictionResult
        r.table shouldBe "data"
        r.command shouldBe Command.INSERT
        r.status shouldBe SatResult.SAT
    }
}

package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RoleSeparationProofTest {

    private val proof = RoleSeparationProof()

    private fun context(
        policies: List<Policy>,
        tables: List<TableMetadata>,
        rolePairs: List<Pair<String, String>>
    ): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(
            policySet, metadata, SelectorEvaluator(metadata),
            ProofConfig(extras = mapOf("rolePairs" to rolePairs))
        )
    }

    @Test
    fun `admin vs viewer with row-level role separation are disjoint (UNSAT)`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("access_level", "text")
            ))
        )
        // Rows are partitioned by access_level matching the session role
        // col(access_level) = session('app.role')
        // Admin sees access_level='admin', viewer sees access_level='viewer'
        val policies = listOf(
            Policy(
                name = "role_partition",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("access_level"), BinaryOp.EQ, ValueSource.Session("app.role"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables, listOf("admin" to "viewer")))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.RoleSeparationResult
        r.table shouldBe "data"
        r.status shouldBe SatResult.UNSAT
    }

    @Test
    fun `same policy for both roles shows overlap (SAT)`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        // Both roles get the same tenant_id check — they can see the same rows
        val policies = listOf(
            Policy(
                name = "tenant_isolation",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables, listOf("admin" to "viewer")))

        results.shouldHaveSize(1)
        val r = results[0] as ProofResult.RoleSeparationResult
        r.table shouldBe "data"
        r.status shouldBe SatResult.SAT
    }

    @Test
    fun `no rolePairs in config produces no results`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(ColumnInfo("id", "uuid")))
        )
        val policySet = PolicySet(emptyList())
        val metadata = SchemaMetadata(tables)
        val ctx = ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
        val results = proof.execute(ctx)
        results.shouldBeEmpty()
    }
}

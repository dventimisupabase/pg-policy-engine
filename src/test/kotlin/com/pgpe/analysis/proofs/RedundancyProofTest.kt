package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RedundancyProofTest {

    private val proof = RedundancyProof()

    private fun context(policies: List<Policy>, tables: List<TableMetadata>): ProofContext {
        val policySet = PolicySet(policies)
        val metadata = SchemaMetadata(tables)
        return ProofContext(policySet, metadata, SelectorEvaluator(metadata), ProofConfig())
    }

    @Test
    fun `duplicate policy is redundant`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val clause = Clause(setOf(
            Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
        ))
        val policies = listOf(
            Policy("policy_a", PolicyType.PERMISSIVE, setOf(Command.SELECT), Selector.Named("data"), listOf(clause)),
            Policy("policy_b", PolicyType.PERMISSIVE, setOf(Command.SELECT), Selector.Named("data"), listOf(clause))
        )
        val results = proof.execute(context(policies, tables))

        // Both duplicate policies are redundant (removing either doesn't change effective predicate)
        results.shouldHaveSize(2)
        results.all { (it as ProofResult.RedundancyResult).table == "data" } shouldBe true
        results.map { (it as ProofResult.RedundancyResult).redundantPolicy }.toSet() shouldBe setOf("policy_a", "policy_b")
    }

    @Test
    fun `only policy on a table is not redundant`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val policies = listOf(
            Policy(
                name = "sole_policy",
                type = PolicyType.PERMISSIVE,
                commands = setOf(Command.SELECT),
                selector = Selector.Named("data"),
                clauses = listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                )))
            )
        )
        val results = proof.execute(context(policies, tables))
        results.shouldBeEmpty()
    }
}

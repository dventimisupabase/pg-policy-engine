package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PolicyEquivalenceProofTest {

    private val proof = PolicyEquivalenceProof()

    private fun context(
        policiesA: List<Policy>,
        policiesB: List<Policy>,
        tables: List<TableMetadata>
    ): ProofContext {
        val policySet = PolicySet(policiesA)
        val metadata = SchemaMetadata(tables)
        return ProofContext(
            policySet, metadata, SelectorEvaluator(metadata),
            ProofConfig(extras = mapOf("comparePolicySet" to PolicySet(policiesB)))
        )
    }

    @Test
    fun `identical policy sets are equivalent (UNSAT)`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid")
            ))
        )
        val clause = Clause(setOf(
            Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
        ))
        val policies = listOf(
            Policy("tenant_isolation", PolicyType.PERMISSIVE,
                setOf(Command.SELECT, Command.INSERT, Command.UPDATE, Command.DELETE),
                Selector.Named("data"), listOf(clause))
        )
        val results = proof.execute(context(policies, policies, tables))

        // All table-command pairs should be equivalent
        results.all { (it as ProofResult.PolicyEquivalenceResult).equivalent } shouldBe true
    }

    @Test
    fun `different policy sets are not equivalent (SAT)`() {
        val tables = listOf(
            TableMetadata("data", "public", listOf(
                ColumnInfo("id", "uuid"), ColumnInfo("tenant_id", "uuid"), ColumnInfo("role", "text")
            ))
        )
        val policiesA = listOf(
            Policy("by_tenant", PolicyType.PERMISSIVE, setOf(Command.SELECT),
                Selector.Named("data"),
                listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Col("tenant_id"), BinaryOp.EQ, ValueSource.Session("app.tenant_id"))
                ))))
        )
        val policiesB = listOf(
            Policy("by_role", PolicyType.PERMISSIVE, setOf(Command.SELECT),
                Selector.Named("data"),
                listOf(Clause(setOf(
                    Atom.BinaryAtom(ValueSource.Session("app.role"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.StringLit("admin")))
                ))))
        )
        val results = proof.execute(context(policiesA, policiesB, tables))

        val selectResult = results.filterIsInstance<ProofResult.PolicyEquivalenceResult>()
            .first { it.command == Command.SELECT }
        selectResult.equivalent shouldBe false
    }
}

package com.pgpe.property

import com.pgpe.ast.*
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class NormalizationPropertyTest {

    @Test
    fun `normalization is idempotent over random policies`() = runBlocking<Unit> {
        checkAll(10_000, arbPolicySet) { policySet ->
            val once = normalize(policySet)
            val twice = normalize(once)
            twice shouldBe once
        }
    }

    @Test
    fun `normalization preserves policy count`() = runBlocking<Unit> {
        checkAll(10_000, arbPolicySet) { policySet ->
            val normalized = normalize(policySet)
            normalized.policies.size shouldBe policySet.policies.size
        }
    }

    @Test
    fun `normalization preserves policy metadata`() = runBlocking<Unit> {
        checkAll(10_000, arbPolicySet) { policySet ->
            val normalized = normalize(policySet)
            for ((original, norm) in policySet.policies.zip(normalized.policies)) {
                norm.name shouldBe original.name
                norm.type shouldBe original.type
                norm.commands shouldBe original.commands
                norm.selector shouldBe original.selector
            }
        }
    }

    @Test
    fun `normalized clauses have no duplicate atoms`() = runBlocking<Unit> {
        checkAll(10_000, arbPolicySet) { policySet ->
            val normalized = normalize(policySet)
            for (policy in normalized.policies) {
                for (clause in policy.clauses) {
                    clause.atoms.size shouldBe clause.atoms.toSet().size
                }
            }
        }
    }

    @Test
    fun `tautological atoms are removed`() = runBlocking<Unit> {
        val arbPolicyWithTautology = arbPolicy.map { policy ->
            val clausesWithTautology = policy.clauses.map { clause ->
                val tautology = Atom.BinaryAtom(
                    ValueSource.Col("id"),
                    BinaryOp.EQ,
                    ValueSource.Col("id")
                )
                Clause(clause.atoms + tautology)
            }
            policy.copy(clauses = clausesWithTautology)
        }

        checkAll(5_000, arbPolicyWithTautology) { policy ->
            val normalized = normalize(PolicySet(listOf(policy)))
            for (normalizedPolicy in normalized.policies) {
                for (clause in normalizedPolicy.clauses) {
                    for (atom in clause.atoms) {
                        if (atom is Atom.BinaryAtom && atom.op == BinaryOp.EQ) {
                            assert(atom.left != atom.right) {
                                "Tautology $atom should have been removed"
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `contradictory clauses are removed`() = runBlocking<Unit> {
        val contradictoryClause = Clause(setOf(
            Atom.BinaryAtom(ValueSource.Col("id"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.IntLit(1))),
            Atom.BinaryAtom(ValueSource.Col("id"), BinaryOp.EQ, ValueSource.Lit(LiteralValue.IntLit(2)))
        ))

        checkAll(5_000, arbPolicy) { policy ->
            val withContradiction = policy.copy(clauses = policy.clauses + contradictoryClause)
            val normalized = normalize(PolicySet(listOf(withContradiction)))
            for (np in normalized.policies) {
                for (clause in np.clauses) {
                    val eqByCol = clause.atoms
                        .filterIsInstance<Atom.BinaryAtom>()
                        .filter { it.op == BinaryOp.EQ && it.left is ValueSource.Col && it.right is ValueSource.Lit }
                        .groupBy { (it.left as ValueSource.Col).name }

                    for ((_, eqAtoms) in eqByCol) {
                        val litValues = eqAtoms.map { (it.right as ValueSource.Lit).value }.toSet()
                        assert(litValues.size <= 1) {
                            "Contradictory clause should have been removed: $clause"
                        }
                    }
                }
            }
        }
    }
}

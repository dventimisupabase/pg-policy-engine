package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*
import com.pgpe.ast.*

class PolicyEquivalenceProof : Proof {
    override val id = "policy-equivalence"
    override val displayName = "Policy Equivalence"
    override val description = "Compares two policy sets for semantic equivalence"
    override val enabledByDefault = false

    override fun execute(context: ProofContext): List<ProofResult> {
        val comparePolicySet = context.config.extras["comparePolicySet"] as? PolicySet ?: return emptyList()
        val normalizedB = com.pgpe.ast.normalize(comparePolicySet)
        val results = mutableListOf<ProofResult>()

        for (table in context.metadata.tables) {
            val applicableA = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }
            val applicableB = normalizedB.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }

            for (cmd in Command.entries) {
                val permA = applicableA.filter { it.type == PolicyType.PERMISSIVE && cmd in it.commands }
                val restA = applicableA.filter { it.type == PolicyType.RESTRICTIVE && cmd in it.commands }
                val permB = applicableB.filter { it.type == PolicyType.PERMISSIVE && cmd in it.commands }
                val restB = applicableB.filter { it.type == PolicyType.RESTRICTIVE && cmd in it.commands }

                // Skip if both have no permissive policies (both default-deny)
                if (permA.isEmpty() && permB.isEmpty()) continue

                val result = withZ3(context.config.timeoutMs) { ctx ->
                    val encoder = SmtEncoder(ctx)
                    val solver = ctx.mkSolver()

                    val sessionPrefix = "session"
                    val rowPrefix = "row_${table.name}"

                    val effA = if (permA.isEmpty()) ctx.mkFalse()
                              else buildEffectivePredicate(encoder, ctx, permA, restA, sessionPrefix, rowPrefix)
                    val effB = if (permB.isEmpty()) ctx.mkFalse()
                              else buildEffectivePredicate(encoder, ctx, permB, restB, sessionPrefix, rowPrefix)

                    // Symmetric difference: (A ∧ ¬B) ∨ (B ∧ ¬A)
                    val symDiff = ctx.mkOr(
                        ctx.mkAnd(effA, ctx.mkNot(effB)),
                        ctx.mkAnd(effB, ctx.mkNot(effA))
                    )
                    solver.add(symDiff)
                    encoder.assertDistinctLiterals(solver)

                    val params = ctx.mkParams()
                    params.add("timeout", context.config.timeoutMs)
                    solver.setParameters(params)

                    when (solver.check()) {
                        Status.UNSATISFIABLE -> ProofResult.PolicyEquivalenceResult(
                            table = table.name,
                            command = cmd,
                            equivalent = true
                        )
                        Status.SATISFIABLE -> ProofResult.PolicyEquivalenceResult(
                            table = table.name,
                            command = cmd,
                            equivalent = false,
                            counterexample = extractCounterexample(solver)
                        )
                        else -> ProofResult.PolicyEquivalenceResult(
                            table = table.name,
                            command = cmd,
                            equivalent = false
                        )
                    }
                }
                results.add(result)
            }
        }

        return results
    }
}

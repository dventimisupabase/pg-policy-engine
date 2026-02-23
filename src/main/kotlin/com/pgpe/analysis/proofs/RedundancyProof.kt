package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*
import com.pgpe.ast.*

class RedundancyProof : Proof {
    override val id = "redundancy"
    override val displayName = "Redundancy Detection"
    override val description = "Detects permissive policies whose removal does not change the effective predicate"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        for (table in context.metadata.tables) {
            val applicable = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }
            val permissive = applicable.filter { it.type == PolicyType.PERMISSIVE }

            if (permissive.size < 2) continue

            for (cmd in Command.entries) {
                val cmdPermissive = permissive.filter { cmd in it.commands }
                if (cmdPermissive.size < 2) continue

                for (p in cmdPermissive) {
                    val withoutP = cmdPermissive.filter { it !== p }

                    // Check if effective_with_p ∧ ¬effective_without_p is UNSAT
                    // If UNSAT, removing p doesn't change anything → p is redundant
                    val isRedundant = withZ3(context.config.timeoutMs) { ctx ->
                        val encoder = SmtEncoder(ctx)
                        val solver = ctx.mkSolver()

                        val sessionPrefix = "session"
                        val rowPrefix = "row_${table.name}"

                        // effective_with_p = OR of all permissive clauses (including p)
                        val withPClauses = cmdPermissive.flatMap { it.clauses }
                        val effWithP = encoder.encodeClauses(withPClauses, sessionPrefix, rowPrefix)

                        // effective_without_p = OR of permissive clauses excluding p
                        val withoutPClauses = withoutP.flatMap { it.clauses }
                        val effWithoutP = encoder.encodeClauses(withoutPClauses, sessionPrefix, rowPrefix)

                        // Assert: row matches with_p but NOT without_p
                        solver.add(effWithP)
                        solver.add(ctx.mkNot(effWithoutP))
                        encoder.assertDistinctLiterals(solver)

                        val params = ctx.mkParams()
                        params.add("timeout", context.config.timeoutMs)
                        solver.setParameters(params)

                        solver.check() == Status.UNSATISFIABLE
                    }

                    if (isRedundant) {
                        results.add(ProofResult.RedundancyResult(
                            redundantPolicy = p.name,
                            table = table.name,
                            command = cmd
                        ))
                    }
                }
            }
        }

        return results
    }
}

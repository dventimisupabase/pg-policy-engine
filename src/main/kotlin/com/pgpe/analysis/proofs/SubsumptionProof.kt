package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*
import com.pgpe.ast.*

class SubsumptionProof : Proof {
    override val id = "subsumption"
    override val displayName = "Subsumption Detection"
    override val description = "Detects when one policy's access is a strict superset of another's"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        for (table in context.metadata.tables) {
            val applicable = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }
            val permissive = applicable.filter { it.type == PolicyType.PERMISSIVE }

            // Check all pairs of permissive policies on this table
            for (i in permissive.indices) {
                for (j in permissive.indices) {
                    if (i == j) continue
                    val p1 = permissive[i]
                    val p2 = permissive[j]

                    // Skip if they don't share any commands
                    val sharedCommands = p1.commands.intersect(p2.commands)
                    if (sharedCommands.isEmpty()) continue

                    // Check if p1 subsumes p2: every row p2 allows, p1 also allows
                    // Encode: p2 AND NOT p1 — if UNSAT, p1 subsumes p2
                    val subsumes = withZ3(context.config.timeoutMs) { ctx ->
                        val encoder = SmtEncoder(ctx)
                        val solver = ctx.mkSolver()

                        val sessionPrefix = "session"
                        val rowPrefix = "row_${table.name}"

                        val p2Expr = encoder.encodeClauses(p2.clauses, sessionPrefix, rowPrefix)
                        val p1Expr = encoder.encodeClauses(p1.clauses, sessionPrefix, rowPrefix)

                        solver.add(p2Expr)
                        solver.add(ctx.mkNot(p1Expr))
                        encoder.assertDistinctLiterals(solver)

                        val params = ctx.mkParams()
                        params.add("timeout", context.config.timeoutMs)
                        solver.setParameters(params)

                        solver.check() == Status.UNSATISFIABLE
                    }

                    if (subsumes) {
                        results.add(ProofResult.SubsumptionResult(
                            subsumingPolicy = p1.name,
                            subsumedPolicy = p2.name,
                            table = table.name
                        ))
                    }
                }
            }
        }

        return results
    }
}

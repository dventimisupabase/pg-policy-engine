package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*
import com.pgpe.ast.*

class SoftDeleteProof : Proof {
    override val id = "soft-delete"
    override val displayName = "Soft Delete Enforcement"
    override val description = "Checks that tables with is_deleted column properly filter deleted rows"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        for (table in context.metadata.tables) {
            val hasIsDeleted = table.columns.any { it.name == "is_deleted" }
            if (!hasIsDeleted) continue

            val applicable = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }
            val permissive = applicable.filter { it.type == PolicyType.PERMISSIVE && Command.SELECT in it.commands }
            if (permissive.isEmpty()) continue

            val restrictive = applicable.filter { it.type == PolicyType.RESTRICTIVE && Command.SELECT in it.commands }

            val result = withZ3(context.config.timeoutMs) { ctx ->
                val encoder = SmtEncoder(ctx)
                val solver = ctx.mkSolver()

                val sessionPrefix = "session"
                val rowPrefix = "row_${table.name}"

                // Build effective SELECT predicate
                val eff = buildEffectivePredicate(
                    encoder, ctx, permissive, restrictive, sessionPrefix, rowPrefix
                )

                // Assert effective predicate holds (row is visible)
                solver.add(eff)

                // Assert is_deleted = true (row is deleted)
                val isDeletedCol = encoder.freshVar("${rowPrefix}_col_is_deleted")
                val trueVal = encoder.freshVar("lit_bool_true")
                solver.add(ctx.mkEq(isDeletedCol, trueVal))
                encoder.assertDistinctLiterals(solver)

                val params = ctx.mkParams()
                params.add("timeout", context.config.timeoutMs)
                solver.setParameters(params)

                when (solver.check()) {
                    Status.UNSATISFIABLE -> ProofResult.SoftDeleteResult(
                        table = table.name,
                        status = SatResult.UNSAT
                    )
                    Status.SATISFIABLE -> ProofResult.SoftDeleteResult(
                        table = table.name,
                        status = SatResult.SAT,
                        counterexample = extractCounterexample(solver)
                    )
                    else -> ProofResult.SoftDeleteResult(
                        table = table.name,
                        status = SatResult.UNKNOWN
                    )
                }
            }
            results.add(result)
        }

        return results
    }
}

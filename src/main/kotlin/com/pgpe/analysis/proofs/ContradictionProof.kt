package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*

class ContradictionProof : Proof {
    override val id = "contradiction"
    override val displayName = "Contradiction Detection"
    override val description = "Detects when the effective predicate is unsatisfiable (nobody can access anything)"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        forEachTableCommand(context) { tcp ->
            if (tcp.permissive.isEmpty()) return@forEachTableCommand

            val isContradiction = withZ3(context.config.timeoutMs) { ctx ->
                val encoder = SmtEncoder(ctx)
                val solver = ctx.mkSolver()

                val sessionPrefix = "session"
                val rowPrefix = "row_${tcp.table.name}"

                val eff = buildEffectivePredicate(
                    encoder, ctx, tcp.permissive, tcp.restrictive, sessionPrefix, rowPrefix
                )
                solver.add(eff)
                encoder.assertDistinctLiterals(solver)

                val params = ctx.mkParams()
                params.add("timeout", context.config.timeoutMs)
                solver.setParameters(params)

                solver.check() == Status.UNSATISFIABLE
            }

            if (isContradiction) {
                results.add(ProofResult.ContradictionResult(
                    table = tcp.table.name,
                    command = tcp.command,
                    message = "Effective predicate is unsatisfiable — no session can access any row"
                ))
            }
        }

        return results
    }
}

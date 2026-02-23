package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*
import com.pgpe.ast.*

class WriteRestrictionProof : Proof {
    override val id = "write-restriction"
    override val displayName = "Write Restriction"
    override val description = "Checks that write access does not exceed read access"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()
        val writeCommands = setOf(Command.INSERT, Command.UPDATE, Command.DELETE)

        for (table in context.metadata.tables) {
            val applicable = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }
            val permissive = applicable.filter { it.type == PolicyType.PERMISSIVE }
            val restrictive = applicable.filter { it.type == PolicyType.RESTRICTIVE }

            val selectPermissive = permissive.filter { Command.SELECT in it.commands }
            if (selectPermissive.isEmpty()) continue

            val selectRestrictive = restrictive.filter { Command.SELECT in it.commands }

            for (writeCmd in writeCommands) {
                val writePermissive = permissive.filter { writeCmd in it.commands }
                if (writePermissive.isEmpty()) continue

                val writeRestrictive = restrictive.filter { writeCmd in it.commands }

                // Check: effective(WRITE) ∧ ¬effective(SELECT)
                // SAT means you can write to rows you can't read
                val result = withZ3(context.config.timeoutMs) { ctx ->
                    val encoder = SmtEncoder(ctx)
                    val solver = ctx.mkSolver()

                    val sessionPrefix = "session"
                    val rowPrefix = "row_${table.name}"

                    val writeEff = buildEffectivePredicate(
                        encoder, ctx, writePermissive, writeRestrictive, sessionPrefix, rowPrefix
                    )
                    val selectEff = buildEffectivePredicate(
                        encoder, ctx, selectPermissive, selectRestrictive, sessionPrefix, rowPrefix
                    )

                    solver.add(writeEff)
                    solver.add(ctx.mkNot(selectEff))
                    encoder.assertDistinctLiterals(solver)

                    val params = ctx.mkParams()
                    params.add("timeout", context.config.timeoutMs)
                    solver.setParameters(params)

                    when (solver.check()) {
                        Status.SATISFIABLE -> ProofResult.WriteRestrictionResult(
                            table = table.name,
                            command = writeCmd,
                            status = SatResult.SAT,
                            counterexample = extractCounterexample(solver)
                        )
                        Status.UNSATISFIABLE -> null  // Writes are subset of reads — no issue
                        else -> ProofResult.WriteRestrictionResult(
                            table = table.name,
                            command = writeCmd,
                            status = SatResult.UNKNOWN
                        )
                    }
                }

                if (result != null) {
                    results.add(result)
                }
            }
        }

        return results
    }
}

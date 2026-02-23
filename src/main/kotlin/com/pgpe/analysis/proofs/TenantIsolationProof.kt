package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*

class TenantIsolationProof : Proof {
    override val id = "tenant-isolation"
    override val displayName = "Tenant Isolation"
    override val description = "Proves that different tenants cannot access each other's rows"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        forEachTableCommand(context) { tcp ->
            if (tcp.permissive.isEmpty()) return@forEachTableCommand

            val result = withZ3(context.config.timeoutMs) { ctx ->
                val encoder = SmtEncoder(ctx)
                val solver = ctx.mkSolver()

                val s1Prefix = "s1"
                val s2Prefix = "s2"
                val rowPrefix = "row_${tcp.table.name}"

                val s1TenantId = encoder.freshVar("${s1Prefix}_session_app.tenant_id")
                val s2TenantId = encoder.freshVar("${s2Prefix}_session_app.tenant_id")
                solver.add(ctx.mkNot(ctx.mkEq(s1TenantId, s2TenantId)))

                val eff1 = buildEffectivePredicate(
                    encoder, ctx, tcp.permissive, tcp.restrictive, s1Prefix, rowPrefix
                )
                val eff2 = buildEffectivePredicate(
                    encoder, ctx, tcp.permissive, tcp.restrictive, s2Prefix, rowPrefix
                )

                solver.add(eff1)
                solver.add(eff2)

                val params = ctx.mkParams()
                params.add("timeout", context.config.timeoutMs)
                solver.setParameters(params)

                when (solver.check()) {
                    Status.UNSATISFIABLE -> ProofResult.IsolationResult(
                        table = tcp.table.name,
                        command = tcp.command,
                        status = com.pgpe.ast.SatResult.UNSAT
                    )
                    Status.SATISFIABLE -> ProofResult.IsolationResult(
                        table = tcp.table.name,
                        command = tcp.command,
                        status = com.pgpe.ast.SatResult.SAT,
                        counterexample = extractCounterexample(solver)
                    )
                    else -> ProofResult.IsolationResult(
                        table = tcp.table.name,
                        command = tcp.command,
                        status = com.pgpe.ast.SatResult.UNKNOWN
                    )
                }
            }
            results.add(result)
        }

        return results
    }
}

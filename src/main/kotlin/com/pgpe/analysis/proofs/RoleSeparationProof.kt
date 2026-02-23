package com.pgpe.analysis.proofs

import com.microsoft.z3.Status
import com.pgpe.analysis.*
import com.pgpe.ast.*

class RoleSeparationProof : Proof {
    override val id = "role-separation"
    override val displayName = "Role Separation"
    override val description = "Checks that specified role pairs have disjoint data access"
    override val enabledByDefault = false

    @Suppress("UNCHECKED_CAST")
    override fun execute(context: ProofContext): List<ProofResult> {
        val rolePairs = context.config.extras["rolePairs"] as? List<Pair<String, String>> ?: return emptyList()
        val results = mutableListOf<ProofResult>()

        for (table in context.metadata.tables) {
            val applicable = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }
            val permissive = applicable.filter { it.type == PolicyType.PERMISSIVE }
            val restrictive = applicable.filter { it.type == PolicyType.RESTRICTIVE }

            for ((role1, role2) in rolePairs) {
                for (cmd in Command.entries) {
                    val cmdPermissive = permissive.filter { cmd in it.commands }
                    if (cmdPermissive.isEmpty()) continue
                    val cmdRestrictive = restrictive.filter { cmd in it.commands }

                    val result = withZ3(context.config.timeoutMs) { ctx ->
                        val encoder = SmtEncoder(ctx)
                        val solver = ctx.mkSolver()

                        val rowPrefix = "row_${table.name}"
                        val s1Prefix = "role_${role1}"
                        val s2Prefix = "role_${role2}"

                        // Set session role for each session
                        val s1Role = encoder.freshVar("${s1Prefix}_session_app.role")
                        val s2Role = encoder.freshVar("${s2Prefix}_session_app.role")
                        val role1Lit = encoder.freshVar("lit_str_$role1")
                        val role2Lit = encoder.freshVar("lit_str_$role2")
                        solver.add(ctx.mkEq(s1Role, role1Lit))
                        solver.add(ctx.mkEq(s2Role, role2Lit))

                        // Build effective predicates for each role session
                        val eff1 = buildEffectivePredicate(
                            encoder, ctx, cmdPermissive, cmdRestrictive, s1Prefix, rowPrefix
                        )
                        val eff2 = buildEffectivePredicate(
                            encoder, ctx, cmdPermissive, cmdRestrictive, s2Prefix, rowPrefix
                        )

                        // Both sessions can access the same row
                        solver.add(eff1)
                        solver.add(eff2)
                        encoder.assertDistinctLiterals(solver)

                        val params = ctx.mkParams()
                        params.add("timeout", context.config.timeoutMs)
                        solver.setParameters(params)

                        when (solver.check()) {
                            Status.UNSATISFIABLE -> ProofResult.RoleSeparationResult(
                                table = table.name,
                                role1 = role1,
                                role2 = role2,
                                status = SatResult.UNSAT
                            )
                            Status.SATISFIABLE -> ProofResult.RoleSeparationResult(
                                table = table.name,
                                role1 = role1,
                                role2 = role2,
                                status = SatResult.SAT,
                                counterexample = extractCounterexample(solver)
                            )
                            else -> ProofResult.RoleSeparationResult(
                                table = table.name,
                                role1 = role1,
                                role2 = role2,
                                status = SatResult.UNKNOWN
                            )
                        }
                    }
                    results.add(result)
                }
            }
        }

        return results
    }
}

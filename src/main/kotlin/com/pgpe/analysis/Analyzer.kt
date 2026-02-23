package com.pgpe.analysis

import com.microsoft.z3.Context
import com.microsoft.z3.Status
import com.pgpe.ast.*
import com.pgpe.compiler.SelectorEvaluator

fun analyze(policySet: PolicySet, metadata: SchemaMetadata): AnalysisReport {
    val normalized = normalize(policySet)
    val evaluator = SelectorEvaluator(metadata)
    val isolationResults = mutableListOf<IsolationResult>()
    val contradictions = mutableListOf<ContradictionResult>()

    for (table in metadata.tables) {
        // Find policies that apply to this table
        val applicablePolicies = normalized.policies.filter { policy ->
            evaluator.matches(policy.selector, table)
        }

        val permissive = applicablePolicies.filter { it.type == PolicyType.PERMISSIVE }
        val restrictive = applicablePolicies.filter { it.type == PolicyType.RESTRICTIVE }

        // Check for contradictions (effective predicate is unsatisfiable)
        for (cmd in Command.entries) {
            val cmdPermissive = permissive.filter { cmd in it.commands }
            val cmdRestrictive = restrictive.filter { cmd in it.commands }

            if (cmdPermissive.isEmpty()) continue  // Default deny, no isolation concern

            // Prove tenant isolation for each command
            val result = proveTenantIsolation(
                table.name, cmd, cmdPermissive, cmdRestrictive
            )
            isolationResults.add(result)
        }
    }

    return AnalysisReport(isolationResults, contradictions, emptyList())
}

private fun proveTenantIsolation(
    tableName: String,
    command: Command,
    permissivePolicies: List<Policy>,
    restrictivePolicies: List<Policy>
): IsolationResult {
    val ctx = Context()
    try {
        val encoder = SmtEncoder(ctx)
        val solver = ctx.mkSolver()
        solver.push()

        val s1Prefix = "s1"
        val s2Prefix = "s2"
        val rowPrefix = "row_$tableName"

        // Assert s1.tenant_id != s2.tenant_id
        val s1TenantId = encoder.freshVar("${s1Prefix}_session_app.tenant_id")
        val s2TenantId = encoder.freshVar("${s2Prefix}_session_app.tenant_id")
        solver.add(ctx.mkNot(ctx.mkEq(s1TenantId, s2TenantId)))

        // Build effective predicate for session 1
        val eff1 = buildEffectivePredicate(
            encoder, ctx, permissivePolicies, restrictivePolicies,
            s1Prefix, rowPrefix
        )

        // Build effective predicate for session 2
        val eff2 = buildEffectivePredicate(
            encoder, ctx, permissivePolicies, restrictivePolicies,
            s2Prefix, rowPrefix
        )

        // Assert both sessions can access the same row
        solver.add(eff1)
        solver.add(eff2)

        // Check satisfiability
        val params = ctx.mkParams()
        params.add("timeout", 5000)  // 5 second timeout
        solver.setParameters(params)

        return when (solver.check()) {
            Status.UNSATISFIABLE -> IsolationResult(tableName, command, SatResult.UNSAT)
            Status.SATISFIABLE -> {
                val model = solver.model
                val counterexample = mutableMapOf<String, String>()
                for (decl in model.constDecls) {
                    counterexample[decl.name.toString()] = model.getConstInterp(decl).toString()
                }
                IsolationResult(tableName, command, SatResult.SAT, counterexample)
            }
            Status.UNKNOWN -> IsolationResult(tableName, command, SatResult.UNKNOWN)
            else -> IsolationResult(tableName, command, SatResult.UNKNOWN)
        }
    } finally {
        ctx.close()
    }
}

private fun buildEffectivePredicate(
    encoder: SmtEncoder,
    ctx: Context,
    permissivePolicies: List<Policy>,
    restrictivePolicies: List<Policy>,
    sessionPrefix: String,
    rowPrefix: String
): com.microsoft.z3.BoolExpr {
    // Permissive: disjunction of all permissive clause disjunctions
    val permissiveClauses = permissivePolicies.flatMap { it.clauses }
    val permissiveExpr = encoder.encodeClauses(permissiveClauses, sessionPrefix, rowPrefix)

    // Restrictive: conjunction of all restrictive clause disjunctions
    if (restrictivePolicies.isEmpty()) return permissiveExpr

    val restrictiveExprs = restrictivePolicies.map { policy ->
        encoder.encodeClauses(policy.clauses, sessionPrefix, rowPrefix)
    }
    val restrictiveExpr = ctx.mkAnd(*restrictiveExprs.toTypedArray())

    return ctx.mkAnd(permissiveExpr, restrictiveExpr)
}

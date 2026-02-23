package com.pgpe.analysis

import com.microsoft.z3.Context
import com.microsoft.z3.Solver
import com.pgpe.ast.*

data class TableCommandPolicies(
    val table: TableMetadata,
    val command: Command,
    val permissive: List<Policy>,
    val restrictive: List<Policy>
)

fun forEachTableCommand(
    context: ProofContext,
    block: (TableCommandPolicies) -> Unit
) {
    for (table in context.metadata.tables) {
        val applicable = context.normalizedPolicySet.policies.filter { policy ->
            context.evaluator.matches(policy.selector, table)
        }
        val permissive = applicable.filter { it.type == PolicyType.PERMISSIVE }
        val restrictive = applicable.filter { it.type == PolicyType.RESTRICTIVE }

        for (cmd in Command.entries) {
            val cmdPermissive = permissive.filter { cmd in it.commands }
            val cmdRestrictive = restrictive.filter { cmd in it.commands }
            block(TableCommandPolicies(table, cmd, cmdPermissive, cmdRestrictive))
        }
    }
}

fun <T> withZ3(timeoutMs: Int, block: (Context) -> T): T {
    val ctx = Context()
    try {
        return block(ctx)
    } finally {
        ctx.close()
    }
}

fun extractCounterexample(solver: Solver): Map<String, String> {
    val model = solver.model
    val counterexample = mutableMapOf<String, String>()
    for (decl in model.constDecls) {
        counterexample[decl.name.toString()] = model.getConstInterp(decl).toString()
    }
    return counterexample
}

fun buildEffectivePredicate(
    encoder: SmtEncoder,
    ctx: Context,
    permissivePolicies: List<Policy>,
    restrictivePolicies: List<Policy>,
    sessionPrefix: String,
    rowPrefix: String
): com.microsoft.z3.BoolExpr {
    val permissiveClauses = permissivePolicies.flatMap { it.clauses }
    val permissiveExpr = encoder.encodeClauses(permissiveClauses, sessionPrefix, rowPrefix)

    if (restrictivePolicies.isEmpty()) return permissiveExpr

    val restrictiveExprs = restrictivePolicies.map { policy ->
        encoder.encodeClauses(policy.clauses, sessionPrefix, rowPrefix)
    }
    val restrictiveExpr = ctx.mkAnd(*restrictiveExprs.toTypedArray())

    return ctx.mkAnd(permissiveExpr, restrictiveExpr)
}

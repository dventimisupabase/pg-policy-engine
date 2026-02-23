package com.pgpe.analysis.proofs

import com.pgpe.analysis.*
import com.pgpe.ast.Command

class CoverageProof : Proof {
    override val id = "coverage"
    override val displayName = "Policy Coverage"
    override val description = "Checks that every table has policies for all commands"
    override val enabledByDefault = true

    override fun execute(context: ProofContext): List<ProofResult> {
        val results = mutableListOf<ProofResult>()

        for (table in context.metadata.tables) {
            val applicable = context.normalizedPolicySet.policies.filter { policy ->
                context.evaluator.matches(policy.selector, table)
            }

            if (applicable.isEmpty()) {
                results.add(ProofResult.CoverageResult(
                    table = table.name,
                    hasPolicies = false
                ))
                continue
            }

            val coveredCommands = applicable
                .filter { it.type == com.pgpe.ast.PolicyType.PERMISSIVE }
                .flatMap { it.commands }
                .toSet()

            val missingCommands = Command.entries.toSet() - coveredCommands
            if (missingCommands.isNotEmpty()) {
                results.add(ProofResult.CoverageResult(
                    table = table.name,
                    hasPolicies = true,
                    missingCommands = missingCommands
                ))
            }
        }

        return results
    }
}

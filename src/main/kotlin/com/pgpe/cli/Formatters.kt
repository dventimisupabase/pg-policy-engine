package com.pgpe.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.pgpe.analysis.ProofResult
import com.pgpe.ast.*

object Formatters {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun formatAnalysisText(report: AnalysisReport): String {
        return buildString {
            val grouped = report.results.groupBy { it.proofId }

            // Tenant Isolation
            val isolation = grouped["tenant-isolation"]?.filterIsInstance<ProofResult.IsolationResult>()
            if (!isolation.isNullOrEmpty()) {
                appendLine("=== Tenant Isolation Analysis ===")
                appendLine()
                for (result in isolation) {
                    val status = when (result.status) {
                        SatResult.UNSAT -> "PROVEN"
                        SatResult.SAT -> "FAILED"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    }
                    appendLine("  ${result.table} (${result.command}): $status")
                    if (result.status == SatResult.SAT && result.counterexample != null) {
                        appendLine("    Counterexample:")
                        for ((k, v) in result.counterexample) {
                            appendLine("      $k = $v")
                        }
                    }
                }
            }

            // Coverage
            val coverage = grouped["coverage"]?.filterIsInstance<ProofResult.CoverageResult>()
            if (!coverage.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Policy Coverage ===")
                for (r in coverage) {
                    if (!r.hasPolicies) {
                        appendLine("  ${r.table}: NO POLICIES")
                    } else {
                        appendLine("  ${r.table}: missing commands ${r.missingCommands}")
                    }
                }
            }

            // Contradictions
            val contradictions = grouped["contradiction"]?.filterIsInstance<ProofResult.ContradictionResult>()
            if (!contradictions.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Contradictions ===")
                for (c in contradictions) {
                    appendLine("  ${c.table} (${c.command}): ${c.message}")
                }
            }

            // Soft Delete
            val softDelete = grouped["soft-delete"]?.filterIsInstance<ProofResult.SoftDeleteResult>()
            if (!softDelete.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Soft Delete Enforcement ===")
                for (r in softDelete) {
                    val status = when (r.status) {
                        SatResult.UNSAT -> "SAFE"
                        SatResult.SAT -> "LEAK"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    }
                    appendLine("  ${r.table}: $status")
                }
            }

            // Subsumption
            val subsumptions = grouped["subsumption"]?.filterIsInstance<ProofResult.SubsumptionResult>()
            if (!subsumptions.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Subsumption ===")
                for (r in subsumptions) {
                    appendLine("  ${r.table}: ${r.subsumingPolicy} subsumes ${r.subsumedPolicy}")
                }
            }

            // Redundancy
            val redundancy = grouped["redundancy"]?.filterIsInstance<ProofResult.RedundancyResult>()
            if (!redundancy.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Redundancy ===")
                for (r in redundancy) {
                    appendLine("  ${r.table} (${r.command}): ${r.redundantPolicy} is redundant")
                }
            }

            // Write Restriction
            val writeRestriction = grouped["write-restriction"]?.filterIsInstance<ProofResult.WriteRestrictionResult>()
            if (!writeRestriction.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Write Restriction ===")
                for (r in writeRestriction) {
                    val status = when (r.status) {
                        SatResult.SAT -> "VIOLATION"
                        SatResult.UNKNOWN -> "UNKNOWN"
                        SatResult.UNSAT -> "OK"
                    }
                    appendLine("  ${r.table} (${r.command}): $status")
                }
            }

            // Role Separation
            val roleSep = grouped["role-separation"]?.filterIsInstance<ProofResult.RoleSeparationResult>()
            if (!roleSep.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Role Separation ===")
                for (r in roleSep) {
                    val status = when (r.status) {
                        SatResult.UNSAT -> "DISJOINT"
                        SatResult.SAT -> "OVERLAP"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    }
                    appendLine("  ${r.table} (${r.role1} vs ${r.role2}): $status")
                }
            }

            // Policy Equivalence
            val equiv = grouped["policy-equivalence"]?.filterIsInstance<ProofResult.PolicyEquivalenceResult>()
            if (!equiv.isNullOrEmpty()) {
                appendLine()
                appendLine("=== Policy Equivalence ===")
                for (r in equiv) {
                    val status = if (r.equivalent) "EQUIVALENT" else "DIFFERENT"
                    appendLine("  ${r.table} (${r.command}): $status")
                }
            }

            // Summary
            appendLine()
            if (report.allPassed) {
                appendLine("Result: All proofs PASSED.")
            } else {
                appendLine("Result: Some proofs FAILED.")
            }
        }
    }

    fun formatAnalysisJson(report: AnalysisReport): String {
        val resultMaps = report.results.map { result ->
            when (result) {
                is ProofResult.IsolationResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "command" to result.command.name,
                    "status" to when (result.status) {
                        SatResult.UNSAT -> "PROVEN"
                        SatResult.SAT -> "FAILED"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    },
                    "counterexample" to result.counterexample
                )
                is ProofResult.CoverageResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "hasPolicies" to result.hasPolicies,
                    "missingCommands" to result.missingCommands.map { it.name }
                )
                is ProofResult.ContradictionResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "command" to result.command.name,
                    "message" to result.message
                )
                is ProofResult.SoftDeleteResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "status" to when (result.status) {
                        SatResult.UNSAT -> "SAFE"
                        SatResult.SAT -> "LEAK"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    },
                    "counterexample" to result.counterexample
                )
                is ProofResult.SubsumptionResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "subsumingPolicy" to result.subsumingPolicy,
                    "subsumedPolicy" to result.subsumedPolicy
                )
                is ProofResult.RedundancyResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "command" to result.command.name,
                    "redundantPolicy" to result.redundantPolicy
                )
                is ProofResult.WriteRestrictionResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "command" to result.command.name,
                    "status" to when (result.status) {
                        SatResult.SAT -> "VIOLATION"
                        SatResult.UNKNOWN -> "UNKNOWN"
                        SatResult.UNSAT -> "OK"
                    },
                    "counterexample" to result.counterexample
                )
                is ProofResult.RoleSeparationResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "role1" to result.role1,
                    "role2" to result.role2,
                    "status" to when (result.status) {
                        SatResult.UNSAT -> "DISJOINT"
                        SatResult.SAT -> "OVERLAP"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    },
                    "counterexample" to result.counterexample
                )
                is ProofResult.PolicyEquivalenceResult -> mapOf(
                    "proofId" to result.proofId,
                    "table" to result.table,
                    "command" to result.command.name,
                    "equivalent" to result.equivalent,
                    "counterexample" to result.counterexample
                )
            }
        }
        val output = mapOf(
            "results" to resultMaps,
            "allPassed" to report.allPassed
        )
        return mapper.writeValueAsString(output)
    }

    fun formatCompiledJson(compiled: CompiledState): String {
        return mapper.writeValueAsString(compiled)
    }

    fun formatDriftText(report: DriftReport): String {
        return buildString {
            if (report.items.isEmpty()) {
                appendLine("No drift detected. All policies match expected state.")
                return@buildString
            }

            appendLine("=== Drift Report ===")
            appendLine()
            for (item in report.items) {
                val severity = item.severity.name
                val description = when (item) {
                    is DriftItem.MissingPolicy -> "Missing policy: ${item.policyName} on ${item.table}"
                    is DriftItem.ExtraPolicy -> "Extra policy: ${item.policyName} on ${item.table}"
                    is DriftItem.ModifiedPolicy -> "Modified policy: ${item.policyName} on ${item.table}"
                    is DriftItem.RlsDisabled -> "RLS disabled on ${item.table}"
                    is DriftItem.RlsNotForced -> "RLS not forced on ${item.table}"
                }
                appendLine("  [$severity] $description")
            }

            appendLine()
            appendLine("Total: ${report.items.size} drift items found.")
        }
    }

    fun formatDriftJson(report: DriftReport): String {
        val items = report.items.map { item ->
            val details: Map<String, String> = when (item) {
                is DriftItem.MissingPolicy -> mapOf("policyName" to item.policyName)
                is DriftItem.ExtraPolicy -> mapOf("policyName" to item.policyName)
                is DriftItem.ModifiedPolicy -> mapOf(
                    "policyName" to item.policyName,
                    "expected" to item.expected,
                    "actual" to item.actual
                )
                is DriftItem.RlsDisabled -> emptyMap()
                is DriftItem.RlsNotForced -> emptyMap()
            }
            mapOf(
                "type" to (item::class.simpleName ?: "Unknown"),
                "table" to item.table,
                "severity" to item.severity.name,
                "details" to details
            )
        }
        val result = mapOf(
            "items" to items,
            "totalItems" to report.items.size
        )
        return mapper.writeValueAsString(result)
    }
}

package com.pgpe.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.pgpe.ast.*

object Formatters {

    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .enable(SerializationFeature.INDENT_OUTPUT)

    fun formatAnalysisText(report: AnalysisReport): String {
        return buildString {
            appendLine("=== Tenant Isolation Analysis ===")
            appendLine()
            for (result in report.isolationResults) {
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

            if (report.contradictions.isNotEmpty()) {
                appendLine()
                appendLine("=== Contradictions ===")
                for (c in report.contradictions) {
                    appendLine("  ${c.table} (${c.command}): ${c.message}")
                }
            }

            val allProven = report.isolationResults.all { it.status == SatResult.UNSAT }
            appendLine()
            if (allProven) {
                appendLine("Result: Tenant isolation PROVEN for all tables.")
            } else {
                appendLine("Result: Tenant isolation NOT proven for all tables.")
            }
        }
    }

    fun formatAnalysisJson(report: AnalysisReport): String {
        val result = mapOf(
            "isolationResults" to report.isolationResults.map { r ->
                mapOf(
                    "table" to r.table,
                    "command" to r.command.name,
                    "status" to when (r.status) {
                        SatResult.UNSAT -> "PROVEN"
                        SatResult.SAT -> "FAILED"
                        SatResult.UNKNOWN -> "UNKNOWN"
                    },
                    "counterexample" to r.counterexample
                )
            },
            "allProven" to report.isolationResults.all { it.status == SatResult.UNSAT }
        )
        return mapper.writeValueAsString(result)
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

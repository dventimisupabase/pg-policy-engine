package com.pgpe.drift

import com.pgpe.ast.*

object DriftDetector {

    fun detectDrift(expected: CompiledState, observed: ObservedState): DriftReport {
        val items = mutableListOf<DriftItem>()

        for (expectedTable in expected.tables) {
            val observedTable = observed.tables.find { it.table == expectedTable.table }

            if (observedTable == null) {
                // All policies missing, RLS not configured
                items.add(DriftItem.RlsDisabled(expectedTable.table))
                for (policy in expectedTable.policies) {
                    items.add(DriftItem.MissingPolicy(expectedTable.table, policy.name))
                }
                continue
            }

            // Check RLS status
            if (!observedTable.rlsEnabled) {
                items.add(DriftItem.RlsDisabled(observedTable.table))
            }
            if (!observedTable.rlsForced) {
                items.add(DriftItem.RlsNotForced(observedTable.table))
            }

            // Check for missing/modified policies
            for (expectedPolicy in expectedTable.policies) {
                val observedPolicy = observedTable.policies.find { it.name == expectedPolicy.name }
                if (observedPolicy == null) {
                    items.add(DriftItem.MissingPolicy(expectedTable.table, expectedPolicy.name))
                } else {
                    // Compare USING expressions (normalize whitespace)
                    val expectedUsing = extractUsingExpr(expectedPolicy.sql)
                    val observedUsing = observedPolicy.usingExpr ?: ""

                    if (normalizeExpr(expectedUsing) != normalizeExpr(observedUsing)) {
                        items.add(DriftItem.ModifiedPolicy(
                            expectedTable.table,
                            expectedPolicy.name,
                            expectedUsing,
                            observedUsing
                        ))
                    }
                }
            }

            // Check for extra policies (not in expected)
            val expectedNames = expectedTable.policies.map { it.name }.toSet()
            for (observedPolicy in observedTable.policies) {
                if (observedPolicy.name !in expectedNames) {
                    items.add(DriftItem.ExtraPolicy(observedTable.table, observedPolicy.name))
                }
            }
        }

        return DriftReport(items)
    }

    private fun extractUsingExpr(sql: String): String {
        val usingMatch = Regex("""USING \((.+)\);""", RegexOption.DOT_MATCHES_ALL).find(sql)
        return usingMatch?.groupValues?.get(1)?.trim() ?: ""
    }

    internal fun normalizeExpr(s: String): String {
        var result = s.trim()
        // Collapse all whitespace to single spaces
        result = result.replace(Regex("\\s+"), " ")
        // PostgreSQL adds ::text casts to string literals
        result = result.replace(Regex("'([^']*)'::text"), "'$1'")
        // PostgreSQL removes schema qualifications in stored expressions
        result = result.replace(Regex("\\bpublic\\."), "")
        // Repeatedly strip all non-essential parentheses
        var prev: String
        do {
            prev = result
            result = unwrapParens(result)
            // Strip inner paren wrapping for sub-expressions
            result = result.replace(Regex("\\(([^()]+)\\)")) { it.groupValues[1] }
        } while (result != prev)
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun unwrapParens(s: String): String {
        if (!s.startsWith("(") || !s.endsWith(")")) return s
        // Only unwrap if the parens are balanced
        var depth = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            if (depth == 0 && i < s.length - 1) return s  // Parens close before end
        }
        return unwrapParens(s.substring(1, s.length - 1).trim())
    }
}

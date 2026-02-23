package com.pgpe.drift

import com.pgpe.ast.*

object Reconciler {

    fun reconcile(driftItems: List<DriftItem>, expected: CompiledState): List<String> {
        val statements = mutableListOf<String>()

        for (item in driftItems) {
            when (item) {
                is DriftItem.RlsDisabled -> {
                    val table = expected.tables.find { it.table == item.table }
                    if (table != null) {
                        statements.add(table.enableRls)
                    }
                }
                is DriftItem.RlsNotForced -> {
                    val table = expected.tables.find { it.table == item.table }
                    if (table != null) {
                        statements.add(table.forceRls)
                    }
                }
                is DriftItem.MissingPolicy -> {
                    val table = expected.tables.find { it.table == item.table }
                    val policy = table?.policies?.find { it.name == item.policyName }
                    if (policy != null) {
                        statements.add(policy.sql)
                    }
                }
                is DriftItem.ModifiedPolicy -> {
                    // Drop and recreate
                    val table = expected.tables.find { it.table == item.table }
                    val schema = table?.schema ?: "public"
                    statements.add("DROP POLICY IF EXISTS ${item.policyName} ON $schema.${item.table};")
                    val policy = table?.policies?.find { it.name == item.policyName }
                    if (policy != null) {
                        statements.add(policy.sql)
                    }
                }
                is DriftItem.ExtraPolicy -> {
                    val table = expected.tables.find { it.table == item.table }
                    val schema = table?.schema ?: "public"
                    statements.add("DROP POLICY IF EXISTS ${item.policyName} ON $schema.${item.table};")
                }
            }
        }

        return statements
    }
}

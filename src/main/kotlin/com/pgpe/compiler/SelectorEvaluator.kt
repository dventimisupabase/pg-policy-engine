package com.pgpe.compiler

import com.pgpe.ast.*

class SelectorEvaluator(private val metadata: SchemaMetadata) {

    fun matches(selector: Selector, table: TableMetadata): Boolean {
        return when (selector) {
            is Selector.All -> true
            is Selector.HasColumn -> {
                val col = table.columns.find { it.name == selector.name }
                if (col == null) false
                else if (selector.type != null) col.type == selector.type
                else true
            }
            is Selector.Named -> {
                matchesPattern(table.name, selector.pattern)
            }
            is Selector.InSchema -> table.schema == selector.schema
            is Selector.Tagged -> false  // Not implemented in PoC
            is Selector.And -> matches(selector.left, table) && matches(selector.right, table)
            is Selector.Or -> matches(selector.left, table) || matches(selector.right, table)
            is Selector.Not -> !matches(selector.inner, table)
        }
    }

    fun governedTables(selector: Selector): List<TableMetadata> {
        return metadata.tables.filter { matches(selector, it) }
    }

    private fun matchesPattern(name: String, pattern: String): Boolean {
        // Simple SQL LIKE pattern matching: % = any chars, _ = single char
        val regex = pattern
            .replace("%", ".*")
            .replace("_", ".")
            .let { "^$it$" }
            .toRegex()
        return regex.matches(name)
    }
}

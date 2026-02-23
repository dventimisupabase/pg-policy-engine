package com.pgpe.engine

import com.pgpe.model.Command
import com.pgpe.model.ParseIssue
import com.pgpe.model.ParseResult
import com.pgpe.model.Policy
import com.pgpe.model.Selector

object PolicyParser {
    fun parse(content: String): ParseResult {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val issues = mutableListOf<ParseIssue>()

        fun find(prefix: String): Pair<Int, String>? {
            val idx = lines.indexOfFirst { it.startsWith(prefix) }
            if (idx < 0) return null
            return (idx + 1) to lines[idx].removePrefix(prefix).trim()
        }

        val policyLine = find("POLICY ")
        if (policyLine == null) issues += ParseIssue(1, "Missing POLICY declaration")

        val forLine = find("FOR ")
        if (forLine == null) issues += ParseIssue(1, "Missing FOR command list")

        val selectorLine = find("SELECTOR ")
        if (selectorLine == null) issues += ParseIssue(1, "Missing SELECTOR")

        val clauseLine = find("CLAUSE ")
        if (clauseLine == null) issues += ParseIssue(1, "Missing CLAUSE")

        if (issues.isNotEmpty()) return ParseResult(null, issues)

        val name = policyLine!!.second
        val commands = parseCommands(forLine!!.second, forLine.first, issues)
        val selector = parseSelector(selectorLine!!.second, selectorLine.first, issues)
        val clause = clauseLine!!.second

        if (clause.isBlank()) issues += ParseIssue(clauseLine.first, "Clause cannot be blank")

        if (issues.isNotEmpty() || commands.isEmpty() || selector == null) {
            return ParseResult(null, issues)
        }

        return ParseResult(
            policy = Policy(name = name, commands = commands, selector = selector, clause = clause),
            issues = emptyList(),
        )
    }

    private fun parseCommands(raw: String, line: Int, issues: MutableList<ParseIssue>): Set<Command> {
        val result = mutableSetOf<Command>()
        raw.split(',').map { it.trim().uppercase() }.forEach { token ->
            val cmd = when (token) {
                "SELECT" -> Command.SELECT
                "INSERT" -> Command.INSERT
                "UPDATE" -> Command.UPDATE
                "DELETE" -> Command.DELETE
                else -> null
            }
            if (cmd == null) {
                issues += ParseIssue(line, "Unknown command '$token'")
            } else {
                result += cmd
            }
        }
        return result
    }

    private fun parseSelector(raw: String, line: Int, issues: MutableList<ParseIssue>): Selector? {
        return when {
            raw.equals("ALL", ignoreCase = true) -> Selector.All
            raw.startsWith("has_column(") && raw.endsWith(")") -> {
                val inside = raw.removePrefix("has_column(").removeSuffix(")").trim(' ', '\'', '"')
                if (inside.isBlank()) {
                    issues += ParseIssue(line, "has_column requires a column name")
                    null
                } else {
                    Selector.HasColumn(inside)
                }
            }
            raw.startsWith("named(") && raw.endsWith(")") -> {
                val inside = raw.removePrefix("named(").removeSuffix(")").trim(' ', '\'', '"')
                if (inside.isBlank()) {
                    issues += ParseIssue(line, "named requires a table name")
                    null
                } else {
                    Selector.Named(inside)
                }
            }
            else -> {
                issues += ParseIssue(line, "Unsupported selector '$raw'")
                null
            }
        }
    }
}

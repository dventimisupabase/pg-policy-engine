package com.pgpe.model

enum class Command { SELECT, INSERT, UPDATE, DELETE }

data class Policy(
    val name: String,
    val commands: Set<Command>,
    val selector: Selector,
    val clause: String,
)

sealed interface Selector {
    data object All : Selector
    data class HasColumn(val column: String) : Selector
    data class Named(val table: String) : Selector
}

data class ParseIssue(val line: Int, val message: String)

data class ParseResult(val policy: Policy?, val issues: List<ParseIssue>)

data class AnalysisResult(
    val policy: Policy,
    val tenantIsolationLikely: Boolean,
    val reasons: List<String>,
)

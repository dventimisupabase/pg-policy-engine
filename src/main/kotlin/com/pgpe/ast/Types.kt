package com.pgpe.ast

// --- Value Sources ---

sealed class LiteralValue {
    data class StringLit(val value: String) : LiteralValue()
    data class IntLit(val value: Long) : LiteralValue()
    data class BoolLit(val value: Boolean) : LiteralValue()
    data object NullLit : LiteralValue()
    data class ListLit(val values: List<LiteralValue>) : LiteralValue()
}

sealed class ValueSource {
    data class Col(val name: String) : ValueSource()
    data class Session(val key: String) : ValueSource()
    data class Lit(val value: LiteralValue) : ValueSource()
    data class Fn(val name: String, val args: List<ValueSource>) : ValueSource()
}

// --- Operators ---

enum class BinaryOp {
    EQ, NEQ, LT, GT, LTE, GTE, IN, NOT_IN, LIKE, NOT_LIKE
}

enum class UnaryOp {
    IS_NULL, IS_NOT_NULL
}

// --- Atoms, Clauses, Policies ---

data class Relationship(
    val sourceTable: String?,  // null = wildcard '_'
    val sourceCol: String,
    val targetTable: String,
    val targetCol: String
)

sealed class Atom {
    data class BinaryAtom(
        val left: ValueSource, val op: BinaryOp, val right: ValueSource
    ) : Atom()

    data class UnaryAtom(
        val source: ValueSource, val op: UnaryOp
    ) : Atom()

    data class TraversalAtom(
        val relationship: Relationship, val clause: Clause
    ) : Atom()
}

data class Clause(val atoms: Set<Atom>)

enum class PolicyType { PERMISSIVE, RESTRICTIVE }

enum class Command { SELECT, INSERT, UPDATE, DELETE }

sealed class Selector {
    data class HasColumn(val name: String, val type: String? = null) : Selector()
    data class Named(val pattern: String) : Selector()
    data class InSchema(val schema: String) : Selector()
    data class Tagged(val tag: String) : Selector()
    data object All : Selector()
    data class And(val left: Selector, val right: Selector) : Selector()
    data class Or(val left: Selector, val right: Selector) : Selector()
    data class Not(val inner: Selector) : Selector()
}

data class Policy(
    val name: String,
    val type: PolicyType,
    val commands: Set<Command>,
    val selector: Selector,
    val clauses: List<Clause>  // disjunction (OR)
)

data class PolicySet(val policies: List<Policy>)

// --- Analysis Results ---

enum class SatResult { SAT, UNSAT, UNKNOWN }

data class AnalysisReport(
    val results: List<com.pgpe.analysis.ProofResult>
) {
    // Backward-compatible accessors
    val isolationResults: List<com.pgpe.analysis.ProofResult.IsolationResult>
        get() = results.filterIsInstance<com.pgpe.analysis.ProofResult.IsolationResult>()

    val contradictions: List<com.pgpe.analysis.ProofResult.ContradictionResult>
        get() = results.filterIsInstance<com.pgpe.analysis.ProofResult.ContradictionResult>()

    val subsumptions: List<com.pgpe.analysis.ProofResult.SubsumptionResult>
        get() = results.filterIsInstance<com.pgpe.analysis.ProofResult.SubsumptionResult>()

    val allPassed: Boolean
        get() = results.none { result ->
            when (result) {
                is com.pgpe.analysis.ProofResult.IsolationResult -> result.status != SatResult.UNSAT
                is com.pgpe.analysis.ProofResult.ContradictionResult -> true
                is com.pgpe.analysis.ProofResult.CoverageResult -> !result.hasPolicies || result.missingCommands.isNotEmpty()
                is com.pgpe.analysis.ProofResult.SoftDeleteResult -> result.status == SatResult.SAT
                is com.pgpe.analysis.ProofResult.SubsumptionResult -> true
                is com.pgpe.analysis.ProofResult.RedundancyResult -> true
                is com.pgpe.analysis.ProofResult.WriteRestrictionResult -> result.status == SatResult.SAT
                is com.pgpe.analysis.ProofResult.RoleSeparationResult -> result.status == SatResult.SAT
                is com.pgpe.analysis.ProofResult.PolicyEquivalenceResult -> !result.equivalent
            }
        }
}

// --- Compilation Output ---

data class CompiledPolicy(
    val name: String,
    val table: String,
    val sql: String
)

data class TableArtifacts(
    val table: String,
    val schema: String = "public",
    val enableRls: String,
    val forceRls: String,
    val policies: List<CompiledPolicy>
)

data class CompiledState(
    val tables: List<TableArtifacts>
)

// --- Drift Types ---

enum class Severity { CRITICAL, HIGH, WARNING }

sealed class DriftItem {
    abstract val table: String
    abstract val severity: Severity

    data class MissingPolicy(
        override val table: String, val policyName: String
    ) : DriftItem() { override val severity = Severity.CRITICAL }

    data class ExtraPolicy(
        override val table: String, val policyName: String
    ) : DriftItem() { override val severity = Severity.WARNING }

    data class ModifiedPolicy(
        override val table: String, val policyName: String,
        val expected: String, val actual: String
    ) : DriftItem() { override val severity = Severity.CRITICAL }

    data class RlsDisabled(
        override val table: String
    ) : DriftItem() { override val severity = Severity.CRITICAL }

    data class RlsNotForced(
        override val table: String
    ) : DriftItem() { override val severity = Severity.HIGH }
}

data class DriftReport(val items: List<DriftItem>)

// --- Schema Metadata ---

data class ColumnInfo(
    val name: String,
    val type: String
)

data class TableMetadata(
    val name: String,
    val schema: String = "public",
    val columns: List<ColumnInfo>
)

data class SchemaMetadata(
    val tables: List<TableMetadata>
)

// --- Observed State (from introspection) ---

data class ObservedPolicy(
    val name: String,
    val table: String,
    val type: String,
    val command: String,
    val usingExpr: String?,
    val checkExpr: String?
)

data class ObservedTableState(
    val table: String,
    val rlsEnabled: Boolean,
    val rlsForced: Boolean,
    val policies: List<ObservedPolicy>
)

data class ObservedState(
    val tables: List<ObservedTableState>
)

# Design Document — pg-policy-engine

> **Status**: Draft
> **Date**: 2026-02-22
> **Audience**: Architects, senior engineers

---

## 1. Overview

This document describes the software architecture of pg-policy-engine (pgpe). It sits between the formal specification and the implementation plan:

- The [Policy Algebra Specification](policy-algebra.md) defines the algebra: types, operations, theorems, and algorithms.
- The [Architecture Decision Record](architecture-decision.md) selects the technology stack and evaluates trade-offs.
- **This document** defines modules, data structures, contracts, and data flow.
- The [Implementation Plan](implementation-plan.md) sequences the work into milestones.
- The [Product Requirements Document](product-requirements.md) defines users and requirements.

All module and type names use the conventions established in ADR Section 4.2 and Section 7 (Kotlin/JVM).

---

## 2. System Overview

```
.policy files ──► [Parser] ──► AST ──► [Normalizer] ──► NormalizedAST
                                                            │
                                                  ┌────────┴─────────┐
                                                  ▼                  ▼
                                             [Analyzer]         [Compiler]
                                                  │                  │
                                            AnalysisReport     CompiledState
                                                                     │
                                                      ┌──────────────┤
                                                      ▼              ▼
                                                [Applier]    [Introspector]
                                                                     │
                                                               ObservedState
                                                                     │
                                                              [DriftDetector]
                                                                     │
                                                               DriftReport
```

The system comprises 7 modules plus a CLI shell. Data flows as immutable values through a pipeline. The AST sealed class hierarchy is the shared language between all modules — it is the only dependency that every module shares.

Key architectural principles:
- **AST as lingua franca**: All modules operate on or produce AST types. No module needs to understand another module's internals.
- **Errors as values**: Modules return result types, not exceptions. The CLI layer formats errors for humans.
- **No shared mutable state**: Each module is a pure function from inputs to outputs (except Introspector and Applier, which perform I/O).

---

## 3. Core Data Structures

These Kotlin sealed class hierarchies are the most important artifact in the system. They encode the spec's algebra as compile-time-checked types. Adding a variant to any sealed class causes compile errors in every unhandled `when` expression, ensuring exhaustive coverage.

### 3.1 Value Sources (spec Section 2)

```kotlin
sealed class ValueSource {
    data class Col(val name: String) : ValueSource()
    data class Session(val key: String) : ValueSource()
    data class Lit(val value: LiteralValue) : ValueSource()
    data class Fn(val name: String, val args: List<ValueSource>) : ValueSource()
}

sealed class LiteralValue {
    data class StringLit(val value: String) : LiteralValue()
    data class IntLit(val value: Long) : LiteralValue()
    data class BoolLit(val value: Boolean) : LiteralValue()
    data object NullLit : LiteralValue()
    data class ListLit(val values: List<LiteralValue>) : LiteralValue()
}
```

### 3.2 Operators (spec Section 2)

```kotlin
enum class BinaryOp {
    EQ, NEQ, LT, GT, LTE, GTE, IN, NOT_IN, LIKE, NOT_LIKE
}

enum class UnaryOp {
    IS_NULL, IS_NOT_NULL
}
```

### 3.3 Atoms, Clauses, Policies (spec Sections 2-5)

```kotlin
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

data class Relationship(
    val sourceTable: String?,  // null = wildcard '_' (ADR Section 9.6)
    val sourceCol: String,
    val targetTable: String,
    val targetCol: String
)

data class Clause(val atoms: Set<Atom>)  // conjunction (AND)
```

### 3.4 Selectors (spec Section 6)

```kotlin
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
```

### 3.5 Policies (spec Sections 4-5)

```kotlin
enum class PolicyType { PERMISSIVE, RESTRICTIVE }

enum class Command { SELECT, INSERT, UPDATE, DELETE }

data class Policy(
    val name: String,
    val type: PolicyType,
    val commands: Set<Command>,
    val selector: Selector,
    val clauses: List<Clause>  // disjunction (OR) of clauses
)

data class PolicySet(val policies: List<Policy>)
```

### 3.6 Analysis Results (spec Section 8)

```kotlin
enum class SatResult { SAT, UNSAT, UNKNOWN }

data class IsolationResult(
    val table: String,
    val command: Command,
    val status: SatResult,
    val counterexample: Map<String, String>? = null  // present when SAT
)

data class AnalysisReport(
    val isolationResults: List<IsolationResult>,
    val contradictions: List<ContradictionResult>,
    val subsumptions: List<SubsumptionResult>
)
```

### 3.7 Compilation Output (spec Section 10)

```kotlin
data class CompiledPolicy(
    val name: String,
    val table: String,
    val sql: String          // CREATE POLICY statement
)

data class TableArtifacts(
    val table: String,
    val enableRls: String,   // ALTER TABLE ... ENABLE ROW LEVEL SECURITY
    val forceRls: String,    // ALTER TABLE ... FORCE ROW LEVEL SECURITY
    val policies: List<CompiledPolicy>
)

data class CompiledState(
    val tables: List<TableArtifacts>
)
```

### 3.8 Drift Types (spec Section 11.3)

```kotlin
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

    data class MissingGrant(
        override val table: String, val role: String
    ) : DriftItem() { override val severity = Severity.CRITICAL }

    data class ExtraGrant(
        override val table: String, val role: String
    ) : DriftItem() { override val severity = Severity.WARNING }

    data class RlsDisabled(
        override val table: String
    ) : DriftItem() { override val severity = Severity.CRITICAL }

    data class RlsNotForced(
        override val table: String
    ) : DriftItem() { override val severity = Severity.HIGH }
}

data class DriftReport(val items: List<DriftItem>)
```

---

## 4. Module Contracts

Each module is a Kotlin package under `com.pgpe`. Modules communicate exclusively through the data types defined in Section 3. No module depends on another module's internal implementation.

### 4.1 Parser (`com.pgpe.parser`)

|                   |                                                          |
|-------------------|----------------------------------------------------------|
| **Entry point**   | `fun parse(source: String, fileName: String): PolicySet` |
| **Input**         | DSL source text, file name for error reporting           |
| **Output**        | `PolicySet` (AST)                                        |
| **Dependencies**  | `com.pgpe.ast` (types only), ANTLR4 runtime              |
| **External deps** | ANTLR4-generated lexer/parser (Java)                     |
| **Testability**   | Pure function; no I/O, no Z3, no PostgreSQL              |

Internally: ANTLR4 grammar generates Java lexer/parser. A Kotlin `AstBuilder` visitor converts the ANTLR parse tree to the sealed class AST. Parse errors are collected with file/line/column context.

### 4.2 Normalizer (`com.pgpe.ast`)

|                   |                                                  |
|-------------------|--------------------------------------------------|
| **Entry point**   | `fun normalize(policySet: PolicySet): PolicySet` |
| **Input**         | `PolicySet` (potentially non-normalized)         |
| **Output**        | `PolicySet` (normalized to fixpoint)             |
| **Dependencies**  | `com.pgpe.ast` (types only)                      |
| **External deps** | None                                             |
| **Testability**   | Pure function; no I/O, no Z3, no PostgreSQL      |

Applies the 6 rewrite rules (spec Section 9.1) to each policy's clauses until a fixpoint is reached: idempotence, absorption, contradiction elimination, tautology detection, subsumption elimination, atom merging. Syntactic subsumption only in PoC; semantic subsumption via SMT deferred to Phase 1.

### 4.3 Analyzer (`com.pgpe.analysis`)

|                   |                                                                               |
|-------------------|-------------------------------------------------------------------------------|
| **Entry point**   | `fun analyze(policySet: PolicySet, metadata: SchemaMetadata): AnalysisReport` |
| **Input**         | Normalized `PolicySet`, table metadata (names, columns, types)                |
| **Output**        | `AnalysisReport` (isolation proofs, contradictions, subsumptions)             |
| **Dependencies**  | `com.pgpe.ast` (types only)                                                   |
| **External deps** | Z3 (via z3-turnkey JNI bindings)                                              |
| **Testability**   | Requires Z3 native library; no PostgreSQL                                     |

Encodes the effective access predicate (spec Section 5) as SMT formulas in QF-LIA ∪ QF-EUF. For tenant isolation (spec Section 8.5): creates two session variable sets with differing `app.tenant_id`, asserts both sessions can access the same row, checks for UNSAT. Timeout produces UNKNOWN, not an error.

### 4.4 Compiler (`com.pgpe.compiler`)

|                   |                                                                              |
|-------------------|------------------------------------------------------------------------------|
| **Entry point**   | `fun compile(policySet: PolicySet, metadata: SchemaMetadata): CompiledState` |
| **Input**         | Normalized `PolicySet`, table metadata                                       |
| **Output**        | `CompiledState` (DDL statements per table)                                   |
| **Dependencies**  | `com.pgpe.ast` (types only)                                                  |
| **External deps** | None                                                                         |
| **Testability**   | Pure function; no I/O, no Z3, no PostgreSQL                                  |

Evaluates selectors against metadata to determine which tables each policy governs. Compiles atoms to SQL expressions per spec Section 10.2. Policy naming convention: `<policy_name>_<table_name>`. Traversal atoms compile to `EXISTS (SELECT 1 FROM ...)` subqueries. Session variables compile to `current_setting('key')` with type casts per ADR Section 9.2.

### 4.5 Introspector (`com.pgpe.introspect`)

|                   |                                                                               |
|-------------------|-------------------------------------------------------------------------------|
| **Entry point**   | `fun introspect(connection: Connection, tables: List<String>): ObservedState` |
| **Input**         | JDBC connection, list of governed table names                                 |
| **Output**        | `ObservedState` (policies, RLS status, grants per table)                      |
| **Dependencies**  | `com.pgpe.ast` (types only)                                                   |
| **External deps** | PostgreSQL JDBC driver, HikariCP                                              |
| **Testability**   | Requires PostgreSQL (Testcontainers in tests)                                 |

Queries `pg_policies`, `pg_class` catalog views to extract current RLS state: enabled/forced status, policy names, USING/CHECK expressions, command applicability.

### 4.6 Drift Detector (`com.pgpe.drift`)

|                   |                                                                                  |
|-------------------|----------------------------------------------------------------------------------|
| **Entry point**   | `fun detectDrift(expected: CompiledState, observed: ObservedState): DriftReport` |
| **Input**         | `CompiledState` (from compiler), `ObservedState` (from introspector)             |
| **Output**        | `DriftReport` (list of `DriftItem` with severity)                                |
| **Dependencies**  | `com.pgpe.ast` (types only)                                                      |
| **External deps** | None                                                                             |
| **Testability**   | Pure function; no I/O, no Z3, no PostgreSQL                                      |

Compares expected vs. observed state and classifies discrepancies into 7 drift types (spec Section 11.3). Expression comparison normalizes whitespace before diffing.

### 4.7 Reconciler (`com.pgpe.drift`)

|                   |                                                                                              |
|-------------------|----------------------------------------------------------------------------------------------|
| **Entry point**   | `fun reconcile(driftItems: List<DriftItem>, strategy: ReconciliationStrategy): List<String>` |
| **Input**         | Drift items, reconciliation strategy (auto / alert / dry-run)                                |
| **Output**        | List of SQL remediation statements                                                           |
| **Dependencies**  | `com.pgpe.ast` (types only)                                                                  |
| **External deps** | None                                                                                         |
| **Testability**   | Pure function; no I/O                                                                        |

Generates SQL statements to remediate each drift item. PoC produces SQL strings only (dry-run); Phase 1 adds execution capability.

### 4.8 CLI (`com.pgpe.cli`)

|                   |                                          |
|-------------------|------------------------------------------|
| **Entry point**   | `fun main(args: Array<String>)`          |
| **Commands**      | `analyze`, `compile`, `apply`, `monitor` |
| **Dependencies**  | All modules                              |
| **External deps** | Clikt (command-line parsing)             |

Clikt command tree wrapping all modules. Handles `--format` (text/json), `--dry-run`, `--verbose`, `--target`, `--policy-dir`, `--config`. Formats errors for humans (parse errors with file/line/column). Maps results to exit codes: 0/1/2.

---

## 5. Module Dependency Graph

```
                     ┌─────┐
                     │ ast │  (types only — leaf dependency)
                     └──┬──┘
           ┌───────────┬┼──────────┬───────────┐
           ▼           ▼▼          ▼           ▼
      ┌────────┐  ┌─────────┐  ┌────────┐  ┌───────┐
      │ parser │  │analyzer │  │compiler│  │ drift │
      └────────┘  └─────────┘  └────────┘  └───────┘
                                    │
           ┌────────────┐           │
           │ introspect │           │
           └────────────┘           │
                                    │
                     ┌─────┐        │
                     │ cli │◄───────┘
                     └─────┘
                  (depends on all)
```

Key properties:
- **No circular dependencies**: the graph is a DAG.
- **`ast` is the leaf**: it has zero dependencies on other pgpe packages.
- **`parser`, `introspect`, and `drift` are independent** of each other.
- **`analyzer` and `compiler` are independent** of each other and can be developed in parallel.
- **Only `cli` depends on all modules** — it is the composition root.

---

## 6. Data Flow Through the Governance Loop

The governance loop (spec Section 12) maps to CLI commands and module chains:

| Phase     | CLI Command                | Module Chain                                                   | Input                            | Output                |
|-----------|----------------------------|----------------------------------------------------------------|----------------------------------|-----------------------|
| Define    | *(editor)*                 | —                                                              | —                                | `.policy` files       |
| Analyze   | `pgpe analyze`             | Parser → Normalizer → Analyzer                                 | `.policy` files, schema metadata | `AnalysisReport`      |
| Compile   | `pgpe compile`             | Parser → Normalizer → Compiler                                 | `.policy` files, schema metadata | `CompiledState` (SQL) |
| Apply     | `pgpe apply`               | Parser → Normalizer → Compiler → Applier                       | `.policy` files, DB connection   | DDL executed on DB    |
| Monitor   | `pgpe monitor`             | Parser → Normalizer → Compiler + Introspector → Drift Detector | `.policy` files, DB connection   | `DriftReport`         |
| Reconcile | `pgpe monitor --reconcile` | ...Monitor chain → Reconciler                                  | `DriftReport`, strategy          | Remediation SQL       |

Schema metadata is obtained either from a live database connection or from a metadata file (Phase 1).

---

## 7. Error Handling Strategy

Errors are modeled as a sealed class hierarchy — values, not exceptions. Modules return errors through result types. The CLI layer is the only place that formats errors for display and maps them to exit codes.

```kotlin
sealed class PgpeError {
    data class ParseError(
        val file: String, val line: Int, val column: Int, val message: String
    ) : PgpeError()

    data class ValidationError(
        val policy: String, val message: String
    ) : PgpeError()

    data class NormalizationError(
        val policy: String, val message: String
    ) : PgpeError()

    data class AnalysisError(
        val table: String, val message: String
    ) : PgpeError()

    data class CompilationError(
        val policy: String, val table: String, val message: String
    ) : PgpeError()

    data class ConnectionError(
        val target: String, val cause: String
    ) : PgpeError()

    data class IntrospectionError(
        val table: String, val message: String
    ) : PgpeError()
}
```

Exit code mapping:
- **0**: Operation succeeded, no issues found
- **1**: Operation succeeded but issues detected (drift found, isolation failed, etc.)
- **2**: Operation failed (parse error, connection error, internal error)

---

## 8. Configuration Model

### 8.1 Configuration File (`pgpe.yaml`)

```yaml
target:
  host: localhost
  port: 5432
  database: myapp
  user: pgpe_user

governance:
  schemas: [public]
  max_traversal_depth: 3
  smt_timeout_ms: 5000
  reconciliation_strategy: dry-run  # dry-run | auto | alert
```

Configuration file is optional during PoC (all values provided via CLI flags). Phase 1 adds full configuration file support.

### 8.2 CLI Flags

| Flag           | Default       | Description                          |
|----------------|---------------|--------------------------------------|
| `--target`     | from config   | PostgreSQL connection string         |
| `--policy-dir` | `./policies`  | Directory containing `.policy` files |
| `--config`     | `./pgpe.yaml` | Configuration file path              |
| `--format`     | `text`        | Output format: `text` or `json`      |
| `--dry-run`    | `false`       | Preview without executing DDL        |
| `--verbose`    | `false`       | Detailed output including timing     |

### 8.3 Relationship Declaration

Relationships are declared inline in the DSL within `exists()` traversal atoms (ADR Section 9.5). There is no separate `relationships.yaml` file. The compiler extracts all relationship declarations from the AST when generating traversal subqueries.

---

## 9. Extensibility Points

The sealed class hierarchy is the primary extension mechanism. Adding a new variant to any sealed class causes compile-time errors in every `when` expression that handles that type, ensuring all modules are updated.

| Future Feature      | Where It Plugs In    | What Changes                                                                                    |
|---------------------|----------------------|-------------------------------------------------------------------------------------------------|
| `fn()` value source | `ValueSource.Fn`     | Parser: grammar rule. Compiler: SQL generation. Analyzer: SMT encoding (uninterpreted function) |
| `tagged()` selector | `Selector.Tagged`    | Parser: grammar rule. Compiler: metadata lookup. Requires tag storage mechanism                 |
| `NOT` selector      | `Selector.Not`       | Parser: grammar rule. Compiler: selector evaluation negation                                    |
| New binary operator | `BinaryOp` enum      | Parser: grammar rule. Compiler: SQL operator mapping. Analyzer: SMT encoding                    |
| New drift type      | `DriftItem` subclass | Introspector: catalog query. Drift Detector: comparison logic. Reconciler: remediation SQL      |
| New CLI command     | `cli` package        | Clikt subcommand wiring. No core module changes                                                 |
| WITH CHECK support  | `CompiledPolicy`     | Compiler: separate check expression. Parser: grammar extension                                  |
| Configuration file  | `cli` package        | YAML parsing. No core module changes                                                            |

Phase 1 features (`fn()`, `tagged()`, `NOT`, GRANTs) are designed to slot into the existing architecture without structural changes. Each adds a variant to an existing sealed class and extends the relevant `when` expressions.

---

## 10. Cross-References

| Design Doc Section      | Spec Sections             | ADR Sections                      |
|-------------------------|---------------------------|-----------------------------------|
| 2. System Overview      | 12 (Governance Loop)      | 4.2 (Package Layout)              |
| 3. Core Data Structures | 2-6 (Algebra Definitions) | 9.6 (Wildcard Handling)           |
| 4.1 Parser              | 13 (BNF Grammar)          | 8.1 (PoC: Parse)                  |
| 4.2 Normalizer          | 9.1-9.6 (Normalization)   | 8.2 (PoC: Normalize)              |
| 4.3 Analyzer            | 8.1-8.5 (Analysis)        | 8.3 (PoC: Isolation Proof)        |
| 4.4 Compiler            | 10.1-10.5 (Compilation)   | 8.4 (PoC: Compile), 9.2 (Casting) |
| 4.5 Introspector        | 11.1-11.2 (Introspection) | 8.5 (PoC: Drift)                  |
| 4.6 Drift Detector      | 11.3 (Drift Types)        | 8.5 (PoC: Drift)                  |
| 4.7 Reconciler          | 11.4 (Reconciliation)     | —                                 |
| 7. Error Handling       | —                         | 10.2.4 (Code Quality)             |
| 8. Configuration        | —                         | 9.5 (Inline Relationships)        |
| 9. Extensibility        | —                         | 10.2.5 G8 (Phase 1 Extensions)    |

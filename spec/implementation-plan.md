# Technical Implementation Plan — pg-policy-engine

> **Status**: Draft
> **Date**: 2026-02-22
> **Audience**: Developers (day-1 onboarding)

---

## 1. Project Setup Specification

### 1.1 Toolchain

| Tool   | Version        | Purpose          |
|--------|----------------|------------------|
| Kotlin | 2.x            | Primary language |
| JVM    | 21             | Runtime target   |
| Gradle | 8.x+           | Build system     |
| ANTLR4 | 4.13+          | Parser generator |
| Z3     | via z3-turnkey | SMT solver (JNI) |

### 1.2 Gradle Plugins

```kotlin
plugins {
    kotlin("jvm") version "2.x.x"
    antlr
    application
}
```

### 1.3 Runtime Dependencies

| Dependency      | Coordinates                          | Purpose                                |
|-----------------|--------------------------------------|----------------------------------------|
| ANTLR4 Runtime  | `org.antlr:antlr4-runtime:4.13.x`    | Parser runtime                         |
| z3-turnkey      | `tools.aqua:z3-turnkey:4.13.x`       | Z3 SMT solver with bundled native libs |
| PostgreSQL JDBC | `org.postgresql:postgresql:42.x.x`   | Database connectivity                  |
| HikariCP        | `com.zaxxer:HikariCP:5.x.x`          | Connection pooling                     |
| Clikt           | `com.github.ajalt.clikt:clikt:4.x.x` | CLI framework                          |

### 1.4 Test Dependencies

| Dependency           | Coordinates                               | Purpose                |
|----------------------|-------------------------------------------|------------------------|
| JUnit 5              | `org.junit.jupiter:junit-jupiter:5.x.x`   | Test framework         |
| Kotest Assertions    | `io.kotest:kotest-assertions-core:5.x.x`  | Fluent assertions      |
| Kotest Property      | `io.kotest:kotest-property:5.x.x`         | Property-based testing |
| Testcontainers Core  | `org.testcontainers:testcontainers:1.x.x` | Container management   |
| Testcontainers PG    | `org.testcontainers:postgresql:1.x.x`     | PostgreSQL container   |
| Testcontainers JUnit | `org.testcontainers:junit-jupiter:1.x.x`  | JUnit 5 integration    |

### 1.5 ANTLR Wiring

ANTLR generates Java sources from `.g4` grammar files. Kotlin consumes these via interop:

```kotlin
tasks.named("compileKotlin") {
    dependsOn("generateGrammarSource")
}

sourceSets {
    main {
        java.srcDir(tasks.named("generateGrammarSource").map { it.outputDirectory })
    }
}
```

### 1.6 Application Entry Point

```kotlin
application {
    mainClass.set("com.pgpe.cli.MainKt")
}
```

---

## 2. Directory and Package Structure

```
pg-policy-engine/
├── build.gradle.kts
├── settings.gradle.kts
├── pgpe.yaml                              # (Phase 1) configuration
├── policies/                              # example policy files
│   ├── tenant-isolation.policy
│   └── soft-delete.policy
├── spec/                                  # specifications (existing)
│   ├── policy-algebra.md
│   ├── architecture-decision.md
│   ├── product-requirements.md
│   ├── design.md
│   └── implementation-plan.md
└── src/
    ├── main/
    │   ├── antlr/
    │   │   └── PolicyDsl.g4               # ANTLR grammar
    │   └── kotlin/com/pgpe/
    │       ├── ast/                        # AST types + normalization
    │       │   ├── Types.kt               # sealed classes (Section 3 of design doc)
    │       │   └── Normalizer.kt          # rewrite rules (spec Section 9)
    │       ├── parser/                     # DSL → AST
    │       │   ├── AstBuilder.kt          # ANTLR visitor → AST
    │       │   └── ParseErrors.kt         # error collection
    │       ├── analysis/                   # SMT encoding + analysis
    │       │   ├── SmtEncoder.kt          # AST → Z3 formulas
    │       │   └── Analyzer.kt            # isolation proof, contradiction, subsumption
    │       ├── compiler/                   # AST → SQL
    │       │   ├── SqlCompiler.kt         # atom/clause/policy compilation
    │       │   └── SelectorEvaluator.kt   # selector → table matching
    │       ├── introspect/                 # PostgreSQL catalog queries
    │       │   └── Introspector.kt        # pg_policies, pg_class queries
    │       ├── drift/                      # diff + reconciliation
    │       │   ├── DriftDetector.kt       # expected vs observed comparison
    │       │   └── Reconciler.kt          # drift → remediation SQL
    │       └── cli/                        # CLI entry point
    │           ├── Main.kt                # Clikt command tree
    │           └── Formatters.kt          # text + JSON output
    └── test/
        ├── kotlin/com/pgpe/
        │   ├── ast/
        │   │   └── NormalizerTest.kt
        │   ├── parser/
        │   │   └── ParserTest.kt
        │   ├── analysis/
        │   │   └── AnalyzerTest.kt
        │   ├── compiler/
        │   │   └── CompilerTest.kt
        │   ├── introspect/
        │   │   └── IntrospectorTest.kt
        │   ├── drift/
        │   │   └── DriftDetectorTest.kt
        │   └── cli/
        │       └── EndToEndTest.kt
        └── resources/
            └── golden/                    # spec-derived golden files
                ├── appendix-a1.policy     # 3 Appendix A.1 policies
                ├── appendix-a5.sql        # compiled SQL (Appendix A.5)
                ├── section-9-6-input.json # normalization input (4 clauses)
                ├── section-9-6-output.json# normalization output (2 clauses)
                └── appendix-a7.json       # drift report (Appendix A.7)
```

---

## 3. Milestones

### M0: Project Scaffold + Toolchain Validation

**Goal**: Verify that all three external technologies (ANTLR, Z3, Testcontainers) work together on the target platform before writing any domain code.

**Deliverables**:
- `build.gradle.kts` with all plugins, dependencies, and ANTLR wiring
- Trivial ANTLR grammar (e.g., `HELLO: 'hello';`) proving Java-from-Kotlin interop
- Test that loads Z3 via z3-turnkey and solves a trivial SAT problem
- Test that starts a PostgreSQL container and executes `SELECT 1`

**Dependencies**: None (first milestone).

**Definition of done**:
- `./gradlew clean build` passes on macOS ARM64
- All three smoke tests pass: ANTLR parse, Z3 solve, Testcontainers connect
- No ANTLR ambiguity warnings

**Key risks**:
- z3-turnkey may not bundle macOS ARM64 natives → fallback: manual Z3 installation with `java.library.path`
- ANTLR Kotlin target is experimental → use Java target with Kotlin interop (proven path)

**Stubbed**: Everything except scaffold.

**Estimated effort**: 0.5 days.

---

### M1: ANTLR Grammar + Parser

**Goal**: Parse all 3 Appendix A.1 policies into the AST sealed class hierarchy.

**Deliverables**:
- Complete `PolicyDsl.g4` grammar covering the PoC subset of spec Section 13 BNF
- `AstBuilder.kt` visitor converting ANTLR parse tree to Kotlin AST
- `ParseErrors.kt` collecting errors with file/line/column
- Golden file tests: each Appendix A.1 policy round-trips through parse → AST → string representation

**Grammar scope (PoC subset)**:
- Policy, clause, atom structure
- All binary operators: `=`, `!=`, `<`, `>`, `<=`, `>=`, `IN`, `NOT IN`, `LIKE`, `NOT LIKE`
- All unary operators: `IS NULL`, `IS NOT NULL`
- Value sources: `col()`, `session()`, `lit()` — `fn()` deferred to Phase 1
- Selectors: `has_column()`, `named()`, `in_schema()`, `ALL`, `AND`, `OR` — `tagged()` and `NOT` deferred
- Traversal atoms: `exists(rel(...), {...})`
- Literals: strings, integers, booleans, null, lists
- Comments (line and block)

**Dependencies**: M0 (scaffold).

**Definition of done**:
- All 3 Appendix A.1 policies parse without error
- Golden file tests pass (AST matches expected structure)
- No ANTLR ambiguity warnings
- Parse error test: malformed input produces error with file, line, column
- ADR Section 8.1 PoC criterion met

**Key risks**:
- ANTLR grammar ambiguity in selector combinators → resolve with explicit precedence rules
- Whitespace handling in traversal atom syntax → careful lexer mode design

**Stubbed**: Normalization, analysis, compilation, introspection, drift, CLI.

**Estimated effort**: 2-3 days.

---

### M2: AST Normalization

**Goal**: Implement all 6 rewrite rules and validate with the Section 9.6 worked example (4 clauses → 2).

**Deliverables**:
- `Normalizer.kt` implementing all 6 rewrite rules from spec Section 9.1:
  1. Idempotence: remove duplicate atoms within a clause
  2. Absorption: remove clause c₁ if c₁ ⊇ c₂ (c₂ is more general)
  3. Contradiction elimination: remove clauses containing `col(x) = lit(a)` AND `col(x) = lit(b)` where a ≠ b
  4. Tautology detection: handle always-true atoms
  5. Subsumption elimination: if atoms(c₁) ⊆ atoms(c₂), remove c₂ (c₁ subsumes c₂)
  6. Atom merging: combine compatible atoms
- Fixpoint loop: apply rules until no changes occur
- Golden file test for Section 9.6 example

**Dependencies**: M1 (parser provides AST).

**Definition of done**:
- Section 9.6 input (4 clauses) normalizes to exactly 2 clauses (golden file match)
- Idempotence test: `normalize(normalize(x)) == normalize(x)` for all test inputs
- Each rewrite rule has at least one targeted unit test
- ADR Section 8.2 PoC criterion met

**Key risks**:
- Fixpoint termination: rewrite rules must be strictly reducing (spec Property 9.1 guarantees this)
- Subsumption is syntactic only in PoC — semantic subsumption (requiring SMT) deferred to Phase 1

**Stubbed**: Semantic subsumption via SMT, `fn()` normalization.

**Estimated effort**: 2-3 days.

---

### M3: SMT Integration + Tenant Isolation Proof ← Riskiest Milestone

**Goal**: Encode policies as SMT formulas and prove tenant isolation for all governed tables.

**Deliverables**:
- `SmtEncoder.kt`: translates AST atoms to Z3 formulas
  - Column references → Z3 integer or uninterpreted constants
  - Session variables → Z3 constants
  - Literal values → Z3 values
  - Binary operators → Z3 comparisons
  - Traversal atoms → existentially quantified formulas
- `Analyzer.kt`: orchestrates isolation proof per spec Section 8.5 algorithm:
  1. For each governed table T and command CMD:
  2. Create two session variable sets s₁, s₂ with `s₁.tenant_id ≠ s₂.tenant_id`
  3. Encode effective predicate for both sessions accessing the same row
  4. Submit conjunction to Z3
  5. UNSAT → PROVEN, SAT → FAILED (with counterexample), timeout → UNKNOWN
- SMT theory: QF-LIA ∪ QF-EUF (quantifier-free linear integer arithmetic + uninterpreted functions)

**Dependencies**: M1 (parser), M2 (normalizer — analysis operates on normalized AST).

**Definition of done**:
- Tenant isolation PROVEN (UNSAT) for all 5 governed tables (users, projects, tasks, comments, files)
- Z3 solve time < 1 second per table
- Timeout produces UNKNOWN, not crash
- Satisfiability test: a deliberately weak policy returns SAT with counterexample
- ADR Section 8.3 PoC criterion met
- ADR Section 10.2.5 gate G1 (traversal encoding) validated

**Key risks** (highest of any milestone):
- Traversal atom encoding may be incorrect — existential quantification over FK relationships is subtle
- Z3 may timeout on complex traversal chains → mitigate with max traversal depth
- z3-turnkey JNI may have platform-specific issues → M0 smoke test de-risks this
- If SMT encoding is fundamentally unreliable, architecture must be reconsidered (ADR Section 8.3)

**Stubbed**: Contradiction detection (beyond isolation), subsumption analysis via SMT.

**Estimated effort**: 3-4 days.

---

### M4: SQL Compiler

**Goal**: Compile policies to SQL that is character-identical to Appendix A.5 golden file.

**Deliverables**:
- `SelectorEvaluator.kt`: evaluates selectors against schema metadata to determine governed tables
- `SqlCompiler.kt`: implements compilation rules from spec Section 10.2:
  - Atom compilation: each atom type → SQL expression (see spec Section 10.2 mapping table)
  - Clause compilation: atoms joined with `AND`
  - Policy compilation: clauses joined with `OR`, wrapped in `CREATE POLICY`
  - Table compilation: `ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY` + policy statements
  - Traversal compilation: `EXISTS (SELECT 1 FROM T WHERE T.tc = S.sc AND inner_clause)`
  - Policy naming: `<policy_name>_<table_name>`
  - Session casting: `current_setting('key')` with type cast for non-text columns (stub in PoC, full in Phase 1)
- Wildcard resolution: `_` in `rel()` source position replaced with actual table name per ADR Section 9.6

**Dependencies**: M1 (parser), M2 (normalizer). **Can run in parallel with M3.**

**Definition of done**:
- Compiled SQL is character-identical to Appendix A.5 golden file
- Determinism: two compilations of the same input produce identical output
- All 5 tables receive correct policies based on selector evaluation
- Traversal atoms compile to correct EXISTS subqueries
- ADR Section 8.4 PoC criterion met

**Key risks**:
- Whitespace/formatting differences vs. golden file → define deterministic formatting rules early
- Selector evaluation edge cases (pattern matching in `named()`) → test with multiple patterns

**Stubbed**: Session variable type casting (hardcoded to text in PoC), `fn()` compilation, compile-time round-trip validation.

**Estimated effort**: 2-3 days.

---

### M5: PostgreSQL Introspection + Drift Detection

**Goal**: Query a live PostgreSQL instance and detect discrepancies between expected and observed state.

**Deliverables**:
- `Introspector.kt`: queries PostgreSQL catalog views
  - `pg_policies`: policy name, table, type, command, USING/CHECK expressions
  - `pg_class`: `relrowsecurity` (RLS enabled), `relforcerowsecurity` (RLS forced)
  - Returns `ObservedState` data structure
- `DriftDetector.kt`: compares `CompiledState` vs. `ObservedState`
  - Implements 5 of 7 drift types (grant-related types stubbed):
    1. Missing policy (Critical)
    2. Extra policy (Warning)
    3. Modified policy (Critical) — expression comparison with whitespace normalization
    4. RLS disabled (Critical)
    5. RLS not forced (High)
  - Returns `DriftReport` with severity classification

**Dependencies**: M4 (compiler provides `CompiledState`).

**Definition of done**:
- Integration test with Testcontainers:
  1. Apply compiled policies to fresh PostgreSQL container
  2. Introspect → zero drift
  3. Manually disable RLS on one table → detect `RlsDisabled` drift
  4. Drop one policy → detect `MissingPolicy` drift
  5. Add unmanaged policy → detect `ExtraPolicy` drift
- Expression comparison handles PostgreSQL's whitespace normalization
- ADR Section 8.5 PoC criterion met (criterion 5)
- ADR Section 10.2.5 gate G5 (Testcontainers operational) validated

**Key risks**:
- PostgreSQL normalizes policy expressions when storing them — read-back may differ from input
- Catalog view schema may vary across PostgreSQL versions → test with PG 14 and 17

**Stubbed**: GRANT introspection (drift types 4-5), reconciliation execution.

**Estimated effort**: 2-3 days.

---

### M6: CLI Wiring + End-to-End

**Goal**: Wire all modules into a Clikt CLI and run the full governance loop end-to-end.

**Deliverables**:
- `Main.kt`: Clikt command tree with subcommands:
  - `pgpe analyze` → Parser → Normalizer → Analyzer → report
  - `pgpe compile` → Parser → Normalizer → Compiler → SQL output
  - `pgpe apply` → ...Compile → execute DDL on target database
  - `pgpe monitor` → ...Compile + Introspect → Drift Detector → report
- `Formatters.kt`: text and JSON output formatting
- CLI flags: `--target`, `--policy-dir`, `--format`, `--dry-run`, `--verbose`
- Exit codes: 0 (success), 1 (issues detected), 2 (tool error)
- End-to-end integration test:
  1. Parse policies from files
  2. Normalize
  3. Analyze (isolation proven)
  4. Compile
  5. Apply to Testcontainers PostgreSQL
  6. Monitor (zero drift)

**Dependencies**: M3 (analyzer), M4 (compiler), M5 (introspector + drift).

**Definition of done**:
- Full governance loop passes end-to-end in a single test
- All 5 PoC exit criteria (ADR Section 8.6) are met
- All 8 production-readiness gates (ADR Section 10.2.5) are evaluated (MUST-pass gates all pass)
- `--help` works on all subcommands
- `--format json` produces valid JSON on `analyze` and `monitor`
- Exit codes are correct for each scenario

**Key risks**:
- Module integration issues (type mismatches between modules) → mitigated by shared AST types
- Error propagation: ensuring module errors surface correctly in CLI output

**Stubbed**: `pgpe.yaml` configuration file parsing, `--config` flag.

**Estimated effort**: 1-2 days.

---

### M7: Property-Based Tests + Polish

**Goal**: Validate correctness properties across large random inputs and meet code quality standards.

**Deliverables**:
- Kotest property-based tests:
  - **Normalization idempotence**: `normalize(normalize(p)) == normalize(p)` for 10,000+ random policy sets
  - **Normalization denotation preservation**: normalized policy has same effective predicate (spot-checked via SMT)
  - **Compilation determinism**: `compile(p) == compile(p)` for 10,000+ random policy sets
  - **Clause count non-increase**: `|normalize(p).clauses| <= |p.clauses|` for all inputs
  - **Permissive monotonicity**: adding a permissive clause never reduces access (spot-checked)
- Kotest generators for random AST elements: atoms, clauses, policies, selectors
- Code quality audit:
  - All `when` expressions on sealed classes are exhaustive (no `else` branches)
  - No `!!` operators outside test code
  - All AST types are `data class` or `data object`
  - Immutable AST: no `var` fields on AST types
- Performance sanity check against ADR Section 10.2.2 targets

**Dependencies**: M6 (all modules complete).

**Definition of done**:
- 10,000+ random inputs pass normalization idempotence
- 10,000+ random inputs pass compilation determinism
- No `!!` in production code
- All `when` expressions exhaustive
- Performance within 2x of ADR Section 10.2.2 targets (Acceptable threshold)
- ADR Section 10.2.5 gate G6 (normalization properties under 10K+ inputs) passes

**Key risks**:
- Random generator may not produce sufficiently diverse inputs → tune distributions
- Performance may exceed targets → profile and optimize hot paths

**Stubbed**: Nothing — this is the final PoC milestone.

**Estimated effort**: 1-2 days.

---

## 4. Milestone Dependency Graph

```
    ┌────┐
    │ M0 │  Project Scaffold
    └──┬─┘
       │
    ┌──▼─┐
    │ M1 │  Grammar + Parser
    └──┬─┘
       │
   ┌───┴───┐
   │       │
┌──▼─┐  ┌─▼──┐
│ M2 │  │ M4 │  Normalization ║ Compiler (parallel after M1)
└──┬─┘  └─┬──┘
   │       │
┌──▼─┐     │
│ M3 │     │    SMT Integration (needs M2)
└──┬─┘     │
   │    ┌──▼─┐
   │    │ M5 │  Introspection + Drift (needs M4)
   │    └──┬─┘
   │       │
   └───┬───┘
       │
    ┌──▼─┐
    │ M6 │  CLI + End-to-End (needs M3, M4, M5)
    └──┬─┘
       │
    ┌──▼─┐
    │ M7 │  Property Tests + Polish
    └────┘
```

**Critical path**: M0 → M1 → M2 → M3 → M6 → M7.

**Parallel opportunity**: M4 (Compiler) can start as soon as M1 completes, independently of M2 and M3. M5 (Introspection) can start as soon as M4 completes.

---

## 5. Stubbed vs. Fully Implemented Summary

| Feature                              | PoC Status        | Phase 1                                 |
|--------------------------------------|-------------------|-----------------------------------------|
| Grammar (core subset)                | Full              | Extended with `fn()`, `tagged()`, `NOT` |
| Grammar (`fn()` value source)        | Stubbed           | Full                                    |
| Grammar (`tagged()` selector)        | Not present       | Full                                    |
| Grammar (`NOT` selector)             | Not present       | Full                                    |
| Normalization (6 rules, syntactic)   | Full              | Full                                    |
| Normalization (semantic subsumption) | Not present       | Full (via SMT)                          |
| SMT encoding (atoms, clauses)        | Full              | Full                                    |
| SMT encoding (traversals)            | Full              | Validated with more edge cases          |
| Session variable type casting        | Stub (text only)  | Full (all types per ADR 9.2)            |
| Compile-time round-trip              | Not present       | Full (ADR 9.1)                          |
| SQL compilation (atoms, traversals)  | Full              | Extended with `fn()`                    |
| Selector evaluation                  | Full (PoC subset) | Extended with `tagged()`, `NOT`         |
| Introspection (policies, RLS status) | Full              | Full                                    |
| Introspection (GRANTs)               | Not present       | Full                                    |
| Drift detection (5 of 7 types)       | Full              | Full (7 of 7)                           |
| Reconciliation (SQL generation)      | Full (dry-run)    | Full (execution)                        |
| CLI commands                         | Full              | Extended with `--config`                |
| `pgpe.yaml` configuration            | Not present       | Full                                    |

---

## 6. Golden File References

Golden files are derived directly from the spec and serve as the definitive correctness oracle.

| Golden File               | Spec Source  | Validates                                         |
|---------------------------|--------------|---------------------------------------------------|
| `appendix-a1.policy`      | Appendix A.1 | Parser: 3 policy definitions parse to correct AST |
| `appendix-a5.sql`         | Appendix A.5 | Compiler: character-identical SQL output          |
| `section-9-6-input.json`  | Section 9.6  | Normalizer input: 4 clauses                       |
| `section-9-6-output.json` | Section 9.6  | Normalizer output: 2 clauses (after rules 3, 5)   |
| `appendix-a7.json`        | Appendix A.7 | Drift detector: RLS disabled + extra policy       |

Golden files live in `src/test/resources/golden/`. Tests compare module output against these files byte-for-byte (SQL) or structurally (JSON). Any spec change that modifies these appendices requires updating the corresponding golden file.

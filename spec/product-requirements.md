# Product Requirements Document — pg-policy-engine

> **Status**: Draft
> **Date**: 2026-02-22
> **Audience**: Product managers, stakeholders, engineering leadership

---

## 1. Overview

**pg-policy-engine** (pgpe) is a command-line tool that governs PostgreSQL Row-Level Security (RLS) policies through a formally verifiable pipeline. Security engineers author policies in a restricted domain-specific language (DSL), and the tool analyzes, compiles, applies, monitors, and reconciles those policies against live databases.

| Document                                                 | Role                                                                  |
|----------------------------------------------------------|-----------------------------------------------------------------------|
| [Policy Algebra Specification](policy-algebra.md)        | Formal algebra: definitions, theorems, proofs, algorithms             |
| [Architecture Decision Record](architecture-decision.md) | Technology choices, trade-offs, evaluation rubric                     |
| **This document**                                        | **Who** uses the system and **what** it must do                       |
| [Design Document](design.md)                             | How the software is structured (modules, data flow, contracts)        |
| [Implementation Plan](implementation-plan.md)            | What to build in what order (milestones, dependencies, exit criteria) |

This PRD defines the *users* and *requirements*. The spec defines *how the algebra works*. The ADR defines *which tools and why*.

---

## 2. Problem Statement

PostgreSQL RLS policies are written in arbitrary SQL. Because SQL USING clauses can invoke PL/pgSQL functions, the policy language is Turing-complete, making it formally impossible to prove properties such as tenant isolation, detect contradictions, or verify that a policy change preserves intended semantics (spec Theorem 1.1).

**Business consequence**: organizations relying on RLS for multi-tenant data isolation cannot obtain formal assurance that their policies are correct. Security review is manual, error-prone, and does not scale with the number of tables, policies, or development velocity. A tenant isolation bug is an existential risk for any SaaS platform.

**Core insight**: if we restrict policy authoring to a decidable DSL — eliminating arbitrary SQL — the resulting algebra admits automated analysis, provable tenant isolation, and deterministic compilation. pgpe implements this insight as a practical governance tool.

---

## 3. Target Users and Personas

### 3.1 Platform Security Engineer (Primary)

**Context**: Owns RLS policy definitions across a multi-tenant PostgreSQL deployment. Responsible for ensuring that tenants cannot access each other's data and that security policies remain consistent as the schema evolves.

**Goals**:
- Author policies once and apply them consistently across all governed tables
- Obtain formal proof of tenant isolation without manual review
- Detect and remediate drift between intended and actual database state
- Integrate policy governance into CI/CD pipelines

**Pain points**:
- Manual review of hand-written RLS policies is slow and unreliable
- No existing tool proves isolation properties across a full schema
- Schema migrations can silently invalidate security assumptions
- Drift between policy intent and database state goes undetected

**Tool interaction**: Authors `.policy` files, runs `pgpe analyze`, `pgpe compile`, `pgpe apply`, `pgpe monitor`. Configures `pgpe.yaml` for target databases and governed schemas.

### 3.2 Application Developer

**Context**: Builds features against a PostgreSQL database governed by RLS policies. Needs to understand what access rules apply to which tables and how schema changes affect policy coverage.

**Goals**:
- Understand effective access predicates for any table without reading raw SQL
- Validate that schema migrations do not break policy selectors
- Verify that new tables are covered by appropriate policies before deployment

**Pain points**:
- RLS policies are opaque SQL fragments scattered across migration files
- No way to preview the effect of a schema change on security posture
- Unclear which tables are governed and which are not

**Tool interaction**: Runs `pgpe compile --dry-run` to preview generated SQL. Reads analysis reports to understand policy coverage. Integrates `pgpe monitor` as a CI gate.

### 3.3 Compliance / Security Auditor

**Context**: Must verify and document that the organization's data access controls meet regulatory or contractual requirements (SOC 2, GDPR, customer-specific isolation guarantees).

**Goals**:
- Obtain machine-verifiable proof artifacts for tenant isolation claims
- Review a complete inventory of governed tables, policies, and their properties
- Trace policy definitions to compiled SQL and live database state
- Produce audit trails showing policy history and drift events

**Pain points**:
- Current evidence is screenshots and manual attestations
- No formal proof that isolation holds across the full schema
- Audit preparation is time-consuming and requires deep PostgreSQL expertise

**Tool interaction**: Reviews JSON output from `pgpe analyze` and `pgpe monitor`. Uses analysis reports as compliance evidence. Examines drift reports for historical anomalies.

---

## 4. User Stories and Acceptance Criteria

Stories are organized by governance loop phase (spec Section 12).

### Define Phase

**US-1**: As a security engineer, I want to write tenant isolation policies in a DSL so that I can govern all tenant-scoped tables with a single policy definition.
- *AC*: A policy with `SELECTOR has_column('tenant_id')` applies to every table containing that column.
- *AC*: Policy files parse without error and produce a valid AST.

**US-2**: As a security engineer, I want to express cross-table isolation via foreign-key traversal so that tables without a direct `tenant_id` column are still isolated.
- *AC*: `exists(rel(_, project_id, projects, id), {...})` compiles to a correct EXISTS subquery.
- *AC*: The traversal atom references a declared relationship.

### Analyze Phase

**US-3**: As a security engineer, I want the tool to prove tenant isolation for all governed tables so that I have formal assurance without manual review.
- *AC*: For each governed table, the analysis report states PROVEN, FAILED, or UNKNOWN.
- *AC*: PROVEN means the SMT solver returned UNSAT (no cross-tenant access possible).
- *AC*: Analysis completes in under 5 seconds for 6 tables.

**US-4**: As an auditor, I want a machine-readable analysis report so that I can include it in compliance evidence.
- *AC*: `pgpe analyze --format json` produces a JSON report with per-table isolation status.

### Compile Phase

**US-5**: As a security engineer, I want the tool to generate deterministic SQL from my policy definitions so that I can review and version-control the output.
- *AC*: Compiled SQL is character-identical across repeated invocations.
- *AC*: Output includes `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` and `CREATE POLICY` statements.

**US-6**: As a developer, I want to preview compiled SQL without applying it so that I can review changes before deployment.
- *AC*: `pgpe compile --dry-run` prints SQL to stdout without connecting to a database.

### Apply Phase

**US-7**: As a security engineer, I want to apply compiled policies to a target database so that the live state matches my definitions.
- *AC*: Apply is idempotent: running it twice produces no change the second time.
- *AC*: Apply executes within a single transaction (atomic rollback on error).

### Monitor Phase

**US-8**: As a security engineer, I want to detect drift between intended and actual database state so that unauthorized changes are surfaced.
- *AC*: Drift detection identifies at least: missing policy, extra policy, modified policy, RLS disabled, RLS not forced (5 of 7 drift types per spec Section 11.3).
- *AC*: `pgpe monitor --format json` produces a machine-readable drift report.

**US-9**: As an auditor, I want to see a drift report that classifies each discrepancy by severity so that I can prioritize remediation.
- *AC*: Each drift item includes a type, severity (Critical / High / Warning), and description.

### Reconcile Phase

**US-10**: As a security engineer, I want the tool to suggest or execute remediation for detected drift so that I can restore the intended state.
- *AC*: For each drift item, the tool produces a SQL remediation statement.
- *AC*: `--dry-run` shows remediation SQL without executing it.

### Cross-Cutting

**US-11**: As a developer, I want clear error messages with file, line, and column information so that I can fix policy syntax errors quickly.
- *AC*: Parse errors include the file name, line number, column number, and a descriptive message.

**US-12**: As a security engineer, I want structured exit codes so that CI/CD pipelines can gate on policy status.
- *AC*: Exit code 0 = success, 1 = policy violation or drift detected, 2 = tool error.

*Traceability*: Test cases for these stories map to ADR Section 10.1. PoC exit criteria (ADR Section 8.6) are a subset covering US-1, US-3, US-5, US-8.

---

## 5. Scope Boundaries

| Feature                                        | PoC  | Phase 1 | Phase 2+ |
|------------------------------------------------|:----:|:-------:|:--------:|
| Parse 3 Appendix A policies                    | Yes  |   Yes   |   Yes    |
| Normalization (6 rewrite rules)                | Yes  |   Yes   |   Yes    |
| SMT tenant isolation proof                     | Yes  |   Yes   |   Yes    |
| SQL compilation (atoms, clauses, traversals)   | Yes  |   Yes   |   Yes    |
| Drift detection (5 of 7 types)                 | Yes  |   Yes   |   Yes    |
| CLI (`analyze`, `compile`, `apply`, `monitor`) | Yes  |   Yes   |   Yes    |
| Session variable type casting                  | Stub |   Yes   |   Yes    |
| `fn()` value source                            |  No  |   Yes   |   Yes    |
| `tagged()` selector                            |  No  |   Yes   |   Yes    |
| `NOT` selector combinator                      |  No  |   Yes   |   Yes    |
| GRANT management (drift types 4-5)             |  No  |   Yes   |   Yes    |
| Compile-time round-trip validation             |  No  |   Yes   |   Yes    |
| Reconciliation execution (not just dry-run)    |  No  |   Yes   |   Yes    |
| `pgpe.yaml` configuration file                 |  No  |   Yes   |   Yes    |
| Multiple policy directories                    |  No  |   No    |   Yes    |
| Policy versioning / history                    |  No  |   No    |   Yes    |
| Web UI / dashboard                             |  No  |   No    |   Yes    |
| Multi-database orchestration                   |  No  |   No    |   Yes    |

**Explicit exclusions** (never in scope):
- Reverse-engineering existing hand-written RLS policies into DSL
- Installing engine artifacts (JVM, libraries) on the target database server
- Non-PostgreSQL database support
- Runtime authorization checks (pgpe is a governance tool, not an authorization engine)

---

## 6. Non-Functional Requirements

### 6.1 Performance

| Metric                                  | Target   |
|-----------------------------------------|----------|
| Parse 3 policies                        | < 100 ms |
| Normalize 10-clause policy              | < 50 ms  |
| SMT isolation proof (1 table)           | < 1 s    |
| SMT full analysis (6 tables)            | < 5 s    |
| SQL compilation                         | < 100 ms |
| Introspection (6 tables)                | < 500 ms |
| Drift detection                         | < 200 ms |
| End-to-end governance loop (100 tables) | < 30 s   |
| CLI cold start (JVM)                    | < 3 s    |

Performance targets are informational during PoC (ADR Section 10.2.2). "Acceptable" threshold is 2x target.

### 6.2 Security

- No credentials logged to stdout, stderr, or any file
- DDL execution only during explicit `apply` command; all other commands are read-only
- No data transmitted to external services (SMT solving is local via z3-turnkey)
- No superuser or replication privileges required; standard GRANT-based access only

### 6.3 Reliability

- `apply` is idempotent: re-running produces no change if state already matches
- `apply` executes within a single database transaction (atomic rollback on failure)
- SMT solver timeout produces UNKNOWN status, not an error (graceful degradation)

### 6.4 Usability

- Parse errors include file name, line number, column number, and descriptive message
- All CLI subcommands support `--help`
- Machine-readable output via `--format json` on all reporting commands
- Exit codes: 0 (success), 1 (policy issue / drift detected), 2 (tool error)

### 6.5 Compatibility

- PostgreSQL 14, 15, 16, 17
- macOS ARM64 (Apple Silicon), Linux x86-64
- JVM 21+

---

## 7. Success Metrics

### PoC (ADR Section 8.6)

1. All 3 Appendix A policies parse without error
2. 4-clause normalization example reduces to exactly 2 clauses
3. Tenant isolation proven (UNSAT) for all governed tables
4. Compiled SQL is character-identical to Appendix A.5 golden file
5. Drift detection correctly identifies RLS disabled, missing policy, and extra policy

### Phase 1

- 100% tenant isolation coverage: every governed table has a PROVEN isolation status
- Drift detection latency < 5 minutes in CI/CD pipeline
- CI/CD integration: `pgpe monitor` runs as a pipeline gate in at least one deployment workflow
- All 8 production-readiness gates (ADR Section 10.2.5) pass or reach Acceptable

### Phase 2+

- Scale to 50+ policies governing 200+ tables within performance targets
- Mean time to auto-remediate drift < 2 minutes (for auto-remediable drift types)
- Auditor self-service: compliance evidence generated without engineering involvement

---

## 8. Constraints and Assumptions

### Constraints

- **Language/Runtime**: Kotlin on JVM 21+ (ADR Section 7)
- **Leave-no-trace**: pgpe does not install artifacts on the target database; it connects as a client
- **Restricted DSL**: no arbitrary SQL in policy definitions; only the constructs defined in spec Section 13 BNF
- **Local SMT**: Z3 runs in-process via z3-turnkey; no external solver service

### Assumptions

- Target databases follow the `current_setting('app.tenant_id')` pattern for session-based tenant identification
- A PostgreSQL instance is available at compile time for round-trip validation (Phase 1; not required for PoC)
- z3-turnkey provides working Z3 native binaries for macOS ARM64 and Linux x86-64
- Tables to be governed are identifiable by column presence, naming pattern, or schema membership

---

## 9. Glossary

| Term          | Definition                                                                         |
|---------------|------------------------------------------------------------------------------------|
| Atom          | Irreducible boolean comparison: `(left, op, right)` or `(source, unary_op)`        |
| Clause        | Conjunction (AND) of atoms                                                         |
| Policy        | Named, typed rule: `(name, type, commands, selector, clauses)`                     |
| PolicySet     | Complete collection of policies governing a database                               |
| Selector      | Predicate over table metadata determining which tables a policy governs            |
| Traversal     | `exists(relationship, clause)` — follows FK to prove property on related table     |
| Drift         | Discrepancy between expected (compiled) and observed (introspected) database state |
| Normalization | Applying rewrite rules to reduce a policy to canonical form                        |

See spec Appendix B for the full glossary.

# pg-policy-engine

CLI governance tool for PostgreSQL Row-Level Security policies backed by formal verification.

## What

pg-policy-engine (pgpe) is a command-line tool that governs PostgreSQL Row-Level Security (RLS) policies through a formally verifiable pipeline. Security engineers author policies in a restricted domain-specific language (DSL), and the tool analyzes, compiles, applies, monitors, and reconciles those policies against live databases.

The DSL is formally specified with a decidable algebra, enabling automated proofs of properties like tenant isolation. By restricting what authors can express, pgpe makes it possible to answer questions that are unanswerable for arbitrary SQL predicates — such as "does this policy set guarantee that no tenant can read another tenant's rows?"

The governance loop:

1. **Parse** — read policy definitions written in the DSL
2. **Normalize** — reduce to canonical form via algebraic rewriting
3. **Analyze** — discharge proof obligations through an SMT solver (Z3)
4. **Compile** — emit PostgreSQL `CREATE POLICY` SQL
5. **Apply** — execute DDL against live databases
6. **Introspect** — read back the policies that actually exist
7. **Detect drift** — diff intended state against observed state
8. **Reconcile** — bring the database back into compliance

## Why

RLS policies are written in SQL. SQL is Turing-complete. An RLS predicate may invoke user-defined functions, reference arbitrary subqueries, or encode complex recursive logic. This means that determining whether a given row is accessible to a given user is **undecidable** in general for arbitrary RLS policies.

Manual security review doesn't scale. Every schema change, every new table, every policy update requires re-auditing. Tenant isolation bugs are existential for multi-tenant SaaS — a single leaked row can destroy customer trust.

The core insight: **restrict the authoring language to make analysis decidable.** By limiting policies to a fragment of SQL whose satisfiability is decidable, pgpe can automatically prove isolation properties that would be impossible to verify for unconstrained SQL.

pgpe turns this insight into a practical governance tool with a closed-loop workflow from policy authoring through live-database enforcement.

## How It Works

A policy definition in the DSL:

```
POLICY tenant_isolation
  PERMISSIVE
  FOR SELECT, INSERT, UPDATE, DELETE
  SELECTOR has_column('tenant_id')
  CLAUSE col('tenant_id') = session('app.tenant_id')
```

This declares that any table with a `tenant_id` column gets a permissive policy filtering rows to the current tenant. pgpe will:

- **Prove** that the policy enforces tenant isolation (via Z3)
- **Compile** it to `CREATE POLICY ... USING (tenant_id = current_setting('app.tenant_id'))` SQL
- **Detect drift** if someone modifies the live policy out-of-band
- **Reconcile** by reapplying the intended policy

## Technology

Kotlin/JVM (Java 21), ANTLR4 parser generator, Z3 SMT solver (via z3-turnkey), PostgreSQL JDBC with HikariCP, Clikt CLI framework, Gradle build system.

No runtime dependencies are installed on the target database. pgpe connects as a standard PostgreSQL client.

## Project Status

**MVP implemented.** The core governance pipeline is complete — parsing, normalization, analysis, compilation, application, introspection, drift detection, and reconciliation all work end-to-end. The extensible proof framework ships with 9 proof types. Tests are passing.

See the [Technical Implementation Plan](spec/implementation-plan.md) for milestone details.

## Quick Start

```bash
# Build
./gradlew build

# Analyze policies against a live database (exits 1 on proof failure)
pgpe analyze --policy-dir policies/ --target postgresql://user:pass@localhost:5432/mydb

# Compile policies to SQL
pgpe compile --policy-dir policies/ --target postgresql://user:pass@localhost:5432/mydb

# Apply policies (with dry-run preview)
pgpe apply --policy-dir policies/ --target postgresql://user:pass@localhost:5432/mydb --dry-run

# Detect drift and reconcile
pgpe monitor --policy-dir policies/ --target postgresql://user:pass@localhost:5432/mydb --reconcile
```

## CLI Reference

### `pgpe analyze`

Run proof obligations against policies and a live schema.

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--policy-dir` | yes | — | Directory containing `.policy` files |
| `--target` | yes | — | PostgreSQL connection URL |
| `--format` | no | `text` | Output format: `text` or `json` |
| `--proofs` | no | — | Comma-separated proof IDs to run (whitelist) |
| `--disable-proofs` | no | — | Comma-separated proof IDs to skip |

Exits with code **1** if any proof fails.

### `pgpe compile`

Compile policies to `CREATE POLICY` SQL.

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--policy-dir` | yes | — | Directory containing `.policy` files |
| `--target` | yes | — | PostgreSQL connection URL |
| `--format` | no | `text` | Output format: `text` or `json` |

### `pgpe apply`

Execute compiled DDL against the target database.

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--policy-dir` | yes | — | Directory containing `.policy` files |
| `--target` | yes | — | PostgreSQL connection URL |
| `--dry-run` | no | off | Print SQL without executing |

### `pgpe monitor`

Introspect live policies, detect drift from intended state.

| Flag | Required | Default | Description |
|------|----------|---------|-------------|
| `--policy-dir` | yes | — | Directory containing `.policy` files |
| `--target` | yes | — | PostgreSQL connection URL |
| `--format` | no | `text` | Output format: `text` or `json` |
| `--reconcile` | no | off | Emit remediation SQL for detected drift |

Exits with code **1** if drift is detected.

## Proof Types

All 9 proofs are registered in `ProofRegistry`. Use `--proofs` to run only specific proofs, or `--disable-proofs` to skip specific ones.

| ID | Default | Description |
|----|---------|-------------|
| `tenant-isolation` | on | Proves no cross-tenant row access via SMT satisfiability check |
| `coverage` | on | Detects tables/commands with no governing policy |
| `contradiction` | on | Finds unsatisfiable effective predicates (always-false policies) |
| `soft-delete` | on | Checks for deleted-row leakage (soft-delete columns still visible) |
| `subsumption` | on | Detects when one policy is a strict superset of another |
| `redundancy` | on | Identifies policies that can be removed without changing behavior |
| `write-restriction` | on | Checks that write predicates don't exceed read predicates |
| `role-separation` | off | Proves disjoint access between role pairs; requires `rolePairs` in config |
| `policy-equivalence` | off | Proves two policy sets produce identical access; requires `comparePolicySet` in config |

## Policy DSL

Policies are written in `.policy` files using a restricted DSL. The grammar supports:

- **Policy types:** `PERMISSIVE` (grant access) or `RESTRICTIVE` (deny access)
- **Commands:** `SELECT`, `INSERT`, `UPDATE`, `DELETE` (comma-separated)
- **Selectors:** `ALL`, `has_column('col')`, `has_column('col', type)`, `in_schema('schema')`, `named('table')`, `tagged('tag')` — combinable with `AND`/`OR`
- **Clauses:** conjunctions of atoms, with `OR CLAUSE` for disjunction
- **Value sources:** `col('name')`, `session('setting')`, `lit(value)`, `fn('name', [args])`
- **Operators:** `=`, `!=`, `<`, `>`, `<=`, `>=`, `IN`, `NOT IN`, `LIKE`, `NOT LIKE`, `IS NULL`, `IS NOT NULL`
- **Traversals:** `exists(rel(source, fk_table, fk_col, pk_col), { clause })`
- **Literals:** strings, integers, booleans, `null`, lists (`[1, 2, 3]`)
- **Comments:** `// line` and `/* block */`

Example — a restrictive soft-delete filter combined with a permissive tenant isolation policy:

```
POLICY tenant_isolation
  PERMISSIVE
  FOR SELECT, INSERT, UPDATE, DELETE
  SELECTOR has_column('tenant_id')
  CLAUSE col('tenant_id') = session('app.tenant_id')

POLICY hide_deleted
  RESTRICTIVE
  FOR SELECT
  SELECTOR has_column('deleted_at', timestamp)
  CLAUSE col('deleted_at') IS NULL
```

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success — all proofs passed / no drift detected |
| `1` | Failure — proof failure (`analyze`) or drift detected (`monitor`) |

Designed for CI/CD gates: `pgpe analyze ... || exit 1`

## Documentation

| Document | Description |
|----------|-------------|
| [Policy Algebra Specification](spec/policy-algebra.md) | Formal definitions, theorems, and proofs for the policy DSL |
| [Architecture Decision Record](spec/architecture-decision.md) | Technology selection rationale and evaluation rubric |
| [Product Requirements Document](spec/product-requirements.md) | Personas, user stories, and project scope |
| [Design Document](spec/design.md) | Modules, data structures, contracts, and data flow |
| [Technical Implementation Plan](spec/implementation-plan.md) | Milestones, dependencies, and development setup |
| [MVP Extension Risk Review](spec/mvp-extension-risk-review.md) | Lock-in risks and forward-compatibility guardrails for TrustLogix-style evolution |

**Interactive explainer:** [visual walkthrough of the policy algebra](https://dventimisupabase.github.io/pg-policy-engine/explainer/index.html).

## License

No license has been selected yet.

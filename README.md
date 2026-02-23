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

**Current phase: MVP scaffold implemented.** A working Kotlin CLI now supports policy parsing, lightweight tenant-isolation analysis, deterministic SQL compilation, and SQL drift comparison for baseline governance workflows.

See the [Technical Implementation Plan](spec/implementation-plan.md) for milestone details and the remaining roadmap beyond this MVP scaffold.


## MVP Quickstart

```bash
./gradlew run --args="parse --file policies/tenant-isolation.policy"
./gradlew run --args="analyze --file policies/tenant-isolation.policy --format json"
./gradlew run --args="compile --file policies/tenant-isolation.policy --table public.accounts"
```

> Note: This MVP intentionally uses a restricted parser/analyzer implementation and does not yet include ANTLR, Z3, or live PostgreSQL apply/introspection flows.

### Kotlin plugin resolution in restricted networks

If `./gradlew` fails with `403 Forbidden` while resolving `org.jetbrains.kotlin.jvm`, configure an internal Maven proxy/mirror and export:

```bash
export PGPE_MAVEN_REPO_URL="https://<your-artifact-proxy>/maven"
export PGPE_MAVEN_REPO_USER="<username>"
export PGPE_MAVEN_REPO_PASSWORD="<password>"
```

`settings.gradle.kts` will use that mirror for both plugin resolution and normal dependencies before falling back to public repositories.

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

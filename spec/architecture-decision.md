# Architecture Decision Record: pg-policy-engine

**Version**: 1.0
**Date**: 2026-02-22
**Status**: Draft — No Recommendation Made

---

## 1. Requirements

The formal specification (`spec/policy-algebra.md`, 2,295 lines) defines the
full governance lifecycle for a PostgreSQL Row-Level Security policy engine.
This section distills that specification into concrete implementation
requirements, each traced to its source.

### 1.1 DSL Parsing

The engine must parse a domain-specific language whose complete BNF grammar is
given in Section 13.  The grammar includes:

- **Top-level structure**: policy sets containing named policies with type
  (permissive/restrictive), command lists, selector clauses, and clause blocks
  (Sections 4.1, 13)
- **Selectors**: boolean combinations of structural predicates over table
  metadata — `has_column`, `in_schema`, `named`, `tagged`, `ALL`, with `AND`,
  `OR`, `NOT` connectives (Sections 6.1, 13)
- **Clauses**: conjunctions of atoms joined by `AND`, with disjunction between
  clauses via `OR CLAUSE` (Sections 3.1, 13)
- **Atoms**: comparisons between value sources (`col`, `session`, `lit`, `fn`)
  using binary operators (`=`, `!=`, `<`, `>`, `<=`, `>=`, `IN`, `NOT IN`,
  `LIKE`, `NOT LIKE`) and unary operators (`IS NULL`, `IS NOT NULL`)
  (Sections 2.1–2.3, 13)
- **Traversal atoms**: existential subqueries following declared foreign-key
  relationships, with bounded depth (Sections 7.1–7.3, 13)
- **Literals**: strings, integers, booleans, null, lists (Section 13)

The parser must produce a typed abstract syntax tree (AST) suitable for
manipulation by the normalization, analysis, and compilation phases.

**Ambiguity note**: The keyword `AND` is overloaded — it appears within clauses
(atom conjunction) and within selectors (selector intersection).  The parser
must disambiguate these contexts, likely through the clause/selector block
structure (Section 13).

### 1.2 AST Manipulation — Normalization and Rewrite Rules

The engine must normalize policies to a canonical form by applying six rewrite
rules to a fixpoint (Sections 9.1–9.5):

1. **Idempotence** — duplicate atoms removed (Rule 1)
2. **Absorption** — if clause c1 subsumes c1 ∧ c2, the more restrictive clause
   is absorbed (Rule 2)
3. **Contradiction elimination** — clauses with contradictory atoms reduce to
   ⊥ and are removed (Rule 3)
4. **Tautology detection** — tautological atoms (e.g., `col(x) = col(x)`) are
   removed; all-tautology clauses become ⊤ (Rule 4)
5. **Subsumption elimination** — in a disjunction of clauses, subsumed clauses
   are removed (Rule 5)
6. **Atom merging** — intersect IN-lists, collapse equality + IN on same column
   (Rule 6)

Atom normalization (Section 2.3) must enforce column-left ordering, operator
canonicalization, and literal simplification.

The normalization algorithm must terminate (Property 9.1, proved via a
lexicographic complexity measure) and preserve denotation (Property 9.2).

### 1.3 SMT Integration

The engine must interface with an SMT solver (Z3 or cvc5) for analysis
operations that go beyond syntactic checks (Section 8):

- **Satisfiability** — encode clauses as QF-LIA ∪ QF-EUF formulas and check
  for UNSAT (Section 8.1, Property 2.1)
- **Subsumption** — determine whether one clause/policy is strictly more
  permissive than another via SMT implication checks (Section 8.2)
- **Redundancy** — determine whether removing a policy leaves the effective
  access predicate unchanged (Section 8.3)
- **Contradiction** — check whether the effective predicate for a table is
  unsatisfiable (Section 8.4)
- **Tenant isolation proofs** — prove that no row can be accessed by two
  sessions with different `app.tenant_id` values, by encoding the negation and
  checking UNSAT (Section 8.5, Theorem 8.1)

The SMT encoding must handle traversal atoms by introducing existentially
quantified variables for the target table row (Section 8.1).

Timeout handling is required — the spec suggests 5 seconds per satisfiability
check, with an `UNKNOWN` result if the solver does not terminate in time
(Section 8.1).

### 1.4 SQL Generation

The engine must deterministically compile normalized policies to PostgreSQL DDL
(Section 10):

- **Atom compilation** — each atom type maps to a specific SQL expression
  pattern (Section 10.2)
- **Traversal compilation** — `exists(rel, clause)` compiles to
  `EXISTS (SELECT 1 FROM T WHERE join_cond AND inner)` (Section 10.2)
- **Clause compilation** — atoms joined with `AND` (Section 10.2)
- **Policy compilation** — clauses joined with `OR` in `USING` expression,
  with optional separate `WITH CHECK` for write commands (Sections 10.2, 4.3)
- **Table compilation** — `ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY`
  plus `CREATE POLICY` for each matching policy (Section 10.2)
- **Naming convention** — `<policy_name>_<table_name>` (Section 10.6)

Compilation correctness is stated as Theorem 10.1 and proved by structural
induction.  Determinism is guaranteed by Property 10.2: same normal form
produces identical SQL output.

### 1.5 PostgreSQL Introspection

The engine must query the target database's system catalogs to obtain the
observed state (Section 11.1):

- **RLS status** — `pg_class.relrowsecurity`, `pg_class.relforcerowsecurity`
- **Live policies** — `pg_policies` view: name, type, commands, roles,
  `qual` (USING expression), `with_check` expression
- **Grants** — `information_schema.table_privileges`
- **Table metadata** — column names, types, schemas, for selector evaluation
  (Section 6.2)

The introspect function must produce a structured representation of the
observed state suitable for comparison with the expected (compiled) state.

### 1.6 Diff and Reconciliation

The engine must detect drift (Section 11.2–11.4) and reconcile (Section 11.5):

- **Drift detection** — symmetric difference between observed and expected
  states, classified into seven drift types: missing policy, extra policy,
  modified policy, missing GRANT, extra GRANT, RLS disabled, RLS not forced
  (Section 11.3)
- **Expression comparison** — the `modified_policy` drift type requires
  comparing USING/CHECK expressions.  The spec compares expression strings
  (Section 11.4), but this is identified as an open question below.
- **Reconciliation strategies** — auto-remediate (re-apply expected state),
  alert (notify operators), quarantine (restrict access pending review)
  (Section 11.5)

#### 1.6.1 Design Constraint: Leave No Trace

The engine leaves no artifacts on the target database beyond the RLS policies
themselves — `CREATE POLICY`, `ALTER TABLE ... ENABLE/FORCE ROW LEVEL SECURITY`,
and `GRANT` statements.  Specifically, the engine does not install:

- `COMMENT` tags on policies or tables
- Helper functions (PL/pgSQL or otherwise)
- Introspection functions
- Metadata tables or schemas

This constraint rules out PL/pgSQL Roles C and D on the target database
(strengthening the assessment in Section 3.2): the engine does not install
server-side helpers for introspection or runtime access checking.  The
introspection queries from Section 11.4 are executed remotely by the engine via
the PostgreSQL client library — no server-side functions are needed.

**Rationale**: The engine must be safe to point at any PostgreSQL database
without requiring installation privileges beyond policy management (`CREATE
POLICY`, `ALTER TABLE`, `GRANT`).  A leave-no-trace design ensures the engine
never creates a deployment dependency on the target database and never risks
interfering with the governed system's own objects.

#### 1.6.2 Policy Ownership Identification

The monitor phase (Section 11) must distinguish engine-authored policies from
human-written or third-party policies already present on the target database.
The leave-no-trace constraint (Section 1.6.1) rules out database-side tagging
mechanisms (e.g., `COMMENT` annotations, metadata tables).  Instead,
engine-authored policies are identified by **name-matching against the compiled
expected state**.

The mechanism works as follows:

1. **Compile**: The engine compiles `.policy` files into an expected state E — a
   set of policy names (following the `<policy_name>_<table_name>` naming
   convention from Section 10.6) paired with their expected SQL expressions.
2. **Introspect**: The engine queries `pg_policies` for the observed state O on
   governed tables.
3. **Classify**: For each policy in O on a governed table:
   - If the policy name appears in E, it is **managed** — subject to
     `missing_policy` and `modified_policy` drift checks.
   - If the policy name does not appear in E, it is **foreign** — classified as
     `extra_policy` (Warning severity) and reported without further analysis.
4. **Diff**: For managed policies, the engine compares the observed
   USING/CHECK expression strings against the expected strings (see Open
   Question 8.1 for comparison strategies).

The engine never attempts to parse observed USING/CHECK expressions back into
the constraint DSL.  It never performs semantic analysis of any policy —
managed or foreign.  All comparisons are mechanical: name lookup and string
diffing.  This preserves the "don't analyze RLS — generate it" principle: the
monitor phase does bookkeeping, not reverse engineering.

### 1.7 Policy Storage

The spec assumes a policy set `S` exists but does not prescribe a storage
format.  The governance loop (Section 12) requires:

- Version control of policy definitions (Section 12.1, Phase 1: "Policies are
  version-controlled")
- History for drift audit trail (Section 11)
- Reproducible compilation from stored definitions (Property 10.2)

### 1.8 User Interface — The Governance Loop

The governance loop (Section 12) defines six phases that map to CLI commands:

| Phase     | Operation                                                      | Spec section |
|-----------|----------------------------------------------------------------|--------------|
| Define    | Author/edit `.policy` files                                    | 12.1         |
| Analyze   | Validate policy set (satisfiability, contradiction, isolation) | 12.1, 8      |
| Compile   | Generate SQL from normalized policies                          | 12.1, 10     |
| Apply     | Execute compiled SQL against target database                   | 12.1         |
| Monitor   | Introspect database and detect drift                           | 12.1, 11     |
| Reconcile | Resolve drift via auto/alert/quarantine                        | 12.1, 11.5   |

The TrustLogix discussion (Section 14 of `trust_logix_style_system_mvp_discussion`)
reinforces this as the core operational loop: "Policy intent → compile →
enforce → detect drift → reconcile."

---

## 2. Language Evaluation

Six candidate languages are evaluated against a structured rubric covering the
implementation requirements above.  Each dimension is graded A–F, where:

- **A** = excellent native support, mature ecosystem, minimal friction
- **B** = good support, some rough edges or boilerplate
- **C** = possible but requires significant effort or workarounds
- **D** = poor support, experimental or incomplete
- **F** = not feasible without fundamental compromise

### 2.1 Evaluation Matrix

| Dimension                    | Python | Rust | TypeScript | Java/Kotlin | Go | Clojure |
|------------------------------|--------|------|------------|-------------|----|---------|
| **SMT integration**          | A      | B    | D          | B+          | F  | B+      |
| **Parsing / grammar**        | A      | A-   | B+         | A+          | B  | A       |
| **AST / type modeling**      | B      | A    | B-         | B / B+      | B- | A       |
| **PostgreSQL client**        | A      | B+   | B+         | A           | A  | B+      |
| **Prototyping speed**        | A+     | C    | B+         | B-          | B  | B+      |
| **Packaging / distribution** | C+     | A+   | B+         | B-          | A+ | C+      |

### 2.2 Dimension Analysis

#### SMT Integration

This is the single most constraining requirement.  The spec (Section 8)
requires encoding atoms, clauses, and effective predicates as QF-LIA/EUF
formulas and submitting them to an SMT solver.  The solver must support
existential quantifiers for traversal atom encoding.

**Python — A**.  The `z3-solver` package (pip-installable, maintained by
Microsoft Research) provides the canonical Z3 Python API.  It is the most
widely used Z3 binding outside of C/C++.  Formula construction is idiomatic,
and the package includes pre-built native binaries for macOS, Linux, and
Windows.

**Rust — B**.  The `z3` crate wraps the Z3 C API.  It works but requires the
Z3 shared library to be present at build time, complicating cross-compilation.
The API is lower-level than Python's.  The `rsmt2` crate offers an alternative
SMT-LIB-based interface but is less mature.

**TypeScript — D**.  Z3 is available only as a WASM build (`z3-solver` npm
package).  The WASM version has significant limitations: no incremental
solving, reduced performance, incomplete theory support, and a non-standard
API.  For the satisfiability, subsumption, and isolation proof workloads
specified in Section 8, the WASM build is a poor fit.

**Java/Kotlin — B+**.  Z3 ships official Java bindings (`com.microsoft.z3`).
They are well-maintained and closely track the C API.  JNI overhead is minimal
for formula-level operations.  Kotlin can use these directly.

**Go — F**.  No maintained Go bindings for Z3 or cvc5 exist.  The `go-z3`
package is unmaintained and incomplete.  Building an SMT integration in Go
would require either writing CGo bindings from scratch or shelling out to a
solver process.  Neither approach meets the tight encoding requirements of
Section 8 (existential quantifiers, timeout control, model extraction).

**Clojure — B+**.  Clojure runs on the JVM and can use Z3's Java bindings
directly via Java interop.  The interop is natural for Clojure, though
building formulas through Java method calls is more verbose than Python's
operator-overloaded syntax.

#### Parsing / Grammar

The BNF grammar (Section 13) is moderately complex: approximately 30
productions with contextual keywords, nested structures, and operator
precedence in selectors.

**Python — A**.  `lark` is a mature parser generator that accepts EBNF grammars
directly.  The Section 13 grammar can be transcribed nearly verbatim into a
lark grammar file.  `lark` produces a parse tree that transforms cleanly to
a typed AST.  Alternatives: `pyparsing`, `parsimonious`, `ply`.

**Rust — A-**.  `pest` (PEG parser generator) and `nom` (parser combinator
library) are both excellent.  `pest` accepts a grammar file similar to EBNF;
`nom` requires hand-coding but produces efficient parsers.  Either can handle
the Section 13 grammar.  Minor friction from Rust's ownership model when
building recursive AST nodes.

**TypeScript — B+**.  `nearley` supports EBNF-like grammars.  `ohm` is a
clean alternative.  Both can handle the grammar, but TypeScript's type system
makes AST modeling less natural than Rust's enums or Python's dataclasses.

**Java/Kotlin — A+**.  ANTLR is the gold standard for parser generators.  It
accepts BNF/EBNF, generates a visitor/listener pattern, and has excellent
tooling (grammar visualization, debugging).  The Section 13 grammar maps
directly to an ANTLR grammar.  Kotlin's `sealed class` hierarchies are a
natural fit for AST node types.

**Go — B**.  `participle` is a struct-tag-based parser generator.  It handles
the grammar but with less elegance than ANTLR or lark.  Error messages
require custom work.

**Clojure — A**.  `instaparse` accepts EBNF grammars as strings, producing
parse trees as nested Clojure vectors.  Transforming these to Clojure's
persistent data structures is idiomatic.  The grammar from Section 13 maps
directly.

#### AST / Type Modeling

The policy algebra has a layered type hierarchy: value sources, atoms,
clauses, policies, selectors, traversals.  The implementation must represent
these as a manipulable AST with pattern-matching over node types.

**Python — B**.  `dataclasses` and `@dataclass` provide structural types.
Python 3.10+ structural pattern matching (`match/case`) handles AST dispatch.
The lack of exhaustiveness checking is a notable gap — the compiler cannot
verify that all atom types are handled in a match statement.

**Rust — A**.  Algebraic data types (`enum` with variants carrying data) are
Rust's native way to model ASTs.  Pattern matching is exhaustive by default.
The type system prevents constructing invalid AST nodes.  This is the
strongest AST modeling story of any candidate.

**TypeScript — B-**.  Discriminated unions provide a functional equivalent to
algebraic types, but the implementation requires manual type guards or
excessive type assertions.  The compiler checks are less rigorous than Rust's.

**Java/Kotlin — B / B+**.  Java 17+ sealed classes with records provide
exhaustive pattern matching.  Kotlin's `sealed class` + `when` expressions
are slightly more ergonomic.  Both are serviceable but verbose compared to
Rust or Clojure.

**Go — B-**.  Go's interface-based polymorphism with type switches handles
AST dispatch, but there is no exhaustiveness checking and the approach is
verbose.

**Clojure — A**.  Clojure models ASTs as plain data (maps and vectors).
`core.match` provides pattern matching.  Clojure's data-oriented approach is
a natural fit for AST transformation — normalization rules (Section 9) become
functions over data with no OOP ceremony.  The trade-off: no compile-time
type checking.

#### PostgreSQL Client

The engine must connect to target PostgreSQL databases for introspection
(Section 11) and application (Section 12).

**Python — A**.  `psycopg` (v3) is mature, async-capable, and supports
connection pooling, COPY, and all PostgreSQL types natively.

**Rust — B+**.  `tokio-postgres` is async and well-maintained.  Slightly more
complex connection setup than Python.

**TypeScript — B+**.  `pg` (node-postgres) is mature and widely used.

**Java/Kotlin — A**.  JDBC is the standard; `HikariCP` for pooling.
Excellent ecosystem.

**Go — A**.  `pgx` is a high-quality, idiomatic PostgreSQL driver with
excellent type support.

**Clojure — B+**.  `next.jdbc` wraps JDBC cleanly.  Works well, though
connection pool configuration requires additional libraries (`HikariCP`).

#### Prototyping Speed

How quickly a single developer can build a working vertical slice (the PoC
defined in Section 7 below).

**Python — A+**.  Dynamic typing, REPL-driven development, extensive standard
library, and the fastest path from spec to working code.  The trade-off is
paid later in maintenance and refactoring confidence.

**Rust — C**.  Rust's compile times and borrow checker impose a significant
upfront cost.  The payoff is correctness and performance, but for a prototype
exploring feasibility, the overhead is disproportionate.

**TypeScript — B+**.  Fast iteration with type safety.  Good middle ground.

**Java/Kotlin — B-**.  Verbose language, heavy build systems (Gradle/Maven).
Kotlin mitigates some boilerplate but the JVM startup and ecosystem weight
slow iteration.

**Go — B**.  Fast compilation, simple language, but limited expressiveness for
AST manipulation leads to more code.

**Clojure — B+**.  REPL-driven development is excellent for exploring
algebraic transformations.  Data-oriented style reduces boilerplate.  The
learning curve for developers unfamiliar with Lisp is a consideration.

#### Packaging / Distribution

Delivering the tool as a single installable artifact.

**Python — C+**.  `pip install` works for developers but is fragile for
non-Python users.  `pyinstaller` and `shiv` produce self-contained bundles
but with large file sizes and platform-specific builds.  The Z3 native
dependency adds complexity.

**Rust — A+**.  `cargo build --release` produces a single statically linked
binary.  Cross-compilation is straightforward with `cross`.

**TypeScript — B+**.  `pkg` or `bun build --compile` produce single binaries.
Node.js runtime is bundled.

**Java/Kotlin — B-**.  The JVM packaging story has improved materially since
Java 16.  **jpackage** (stable since Java 16, refined through Java 25)
produces native platform installers — `.dmg` on macOS, `.deb`/`.rpm` on
Linux, `.msi` on Windows — that bundle a trimmed JVM runtime, requiring no
pre-installed JVM on the target machine.  **z3-turnkey** packages the Z3
native libraries for Linux (x86-64), macOS (x86-64 and ARM64), and Windows
into a single Maven-publishable JAR with automatic extraction at runtime,
eliminating the Z3 distribution problem for JVM languages.  A **Homebrew
tap** is a viable distribution channel for jpackage-produced binaries on
macOS.  **GraalVM native-image** is a potential further optimization: JNI is
supported and enabled by default, and the tracing agent can capture JNI
metadata for Z3 calls, but Z3 compatibility with native-image is unproven —
this path carries risk and should not be assumed without validation.
**Remaining gaps vs. Rust**: jpackage requires per-platform builds (no
cross-compilation), package sizes are ~80 MB due to the bundled JVM, and JVM
startup adds 0.5–2 seconds.  These are real costs but acceptable for a CLI
tool that runs infrequently.

**Go — A+**.  Single static binary.  But Go is disqualified by the SMT gap.

**Clojure — C+**.  Clojure runs on the JVM and inherits the same jpackage +
z3-turnkey path described for Java/Kotlin: native installers with bundled
JVM and auto-extracted Z3 libraries.  An `uberjar` serves JVM-equipped
environments directly.  **GraalVM native-image** works with Clojure but adds
complexity beyond the Java/Kotlin baseline: `--initialize-at-build-time` is
required for Clojure's runtime initialization, `eval` and unrestricted
dynamic class generation are unavailable, and community-maintained tooling
(`clj-easy/graalvm-clojure`) fills gaps that the official GraalVM project
does not cover.  Notably, **instaparse** and **core.match** — both central
to this project's parsing and AST manipulation — are not in the official
GraalVM compatibility matrix; they are likely compatible but unverified.
**Babashka** (a GraalVM-compiled Clojure scripting runtime) is disqualified
for the core engine because it does not support JNI and therefore cannot load
Z3, but it could serve as a companion scripting tool for lightweight
automation tasks.  Net: the jpackage path puts Clojure on par with
Java/Kotlin, but the native-image path carries more unknowns, making the
overall packaging story slightly weaker.

### 2.3 Disqualifications

**TypeScript** is disqualified.  The SMT requirement is central to the spec's
value proposition — Sections 8.1 through 8.5 define five distinct analysis
operations that require an SMT solver.  The WASM-only Z3 available to
TypeScript lacks the performance, incremental solving, and theory completeness
needed for tenant isolation proofs (Section 8.5) and redundancy analysis
(Section 8.3).

**Go** is disqualified.  No maintained SMT solver bindings exist.  The
spec's analysis operations cannot be implemented without fundamental
compromise (e.g., shelling out to a solver binary, which loses the
structured formula API needed for existential quantifier encoding in
traversal atoms — Section 8.1).

### 2.4 Viable Candidates

Four languages remain viable: **Python**, **Rust**, **Java/Kotlin**, and
**Clojure**.

---

## 3. The Role of PL/pgSQL

The user's original conception placed implementation in PL/pgSQL.  This
section evaluates PL/pgSQL's suitability for specific roles along a spectrum,
from "core engine" to "peripheral helper."

### 3.1 What PL/pgSQL Cannot Do

PL/pgSQL is a procedural language embedded in PostgreSQL.  It can manipulate
data, define functions, and execute SQL — but it cannot:

- **Parse a non-SQL grammar**.  The Section 13 BNF grammar defines a custom
  DSL.  PL/pgSQL has no parser generator, no regular expression engine
  sufficient for recursive grammar parsing, and no AST data structure.
  Implementing a parser in PL/pgSQL would require encoding a state machine in
  SQL — possible in theory but impractical.

- **Interface with an SMT solver**.  Z3 and cvc5 are C/C++ libraries.
  PL/pgSQL cannot call native libraries directly.  PostgreSQL extensions
  could wrap Z3, but this creates a dependency on a custom C extension in the
  database server — a significant operational burden and security surface.

- **Perform fixpoint normalization over AST structures**.  The normalization
  algorithm (Section 9.3) requires iterating over a mutable AST, applying
  pattern-matched rewrite rules, and detecting fixpoint termination.
  PL/pgSQL's type system (records, arrays, composite types) can represent
  simple tree structures, but the rewrite loop with nested pattern matching
  would be extremely cumbersome and untestable.

**Conclusion**: PL/pgSQL cannot implement the core engine — parsing (1.1),
normalization (1.2), or SMT analysis (1.3).

### 3.2 What PL/pgSQL Can Do

PL/pgSQL has legitimate roles that do not conflict with the separation
principle.  These are presented in order from most appropriate to most
debatable.

#### Role A: Compilation Target Enhancement (Recommended)

The compiled output is currently pure DDL — `CREATE POLICY`, `ALTER TABLE`,
`GRANT` (Section 10).  PL/pgSQL functions could be *part* of the compiled
output:

- **Predicate helper functions**.  A complex RLS predicate could call a
  PL/pgSQL function for readability.  For example, a traversal atom with
  depth 2 compiles to nested `EXISTS` subqueries.  A `check_tenant_access()`
  function could encapsulate this logic.

- **Type-casting wrappers**.  `current_setting()` returns `text`.  A
  PL/pgSQL function `app_tenant_id() RETURNS uuid` could encapsulate
  `current_setting('app.tenant_id')::uuid`, reducing cast duplication
  across policies.

This role preserves the separation principle: the PL/pgSQL runs on the
*target* database but is generated and managed by the engine, not authored
by hand.  It is part of the compiled artifact, not the engine itself.

#### Role B: Control-Database Logic (Appropriate)

If the engine uses a separate PostgreSQL database for its own state — policy
history, drift audit trail, compilation cache — PL/pgSQL functions could
manage that internal database.  Examples:

- `record_drift_event(table_name, drift_type, details)` — append to an
  audit log table
- `get_policy_version(policy_name)` — retrieve the latest version of a
  policy definition
- `compare_policy_snapshots(v1, v2)` — diff two stored compilations

This preserves the separation principle: PL/pgSQL runs on the *control*
database, not the *target* database.  The control database is infrastructure
owned by the engine, not the user's data.

#### Role C: Introspection Helpers on the Target Database (Debatable)

PL/pgSQL functions on the target database could package the `pg_catalog`
queries from Section 11.4 into a clean interface:

```sql
-- Hypothetical introspection helper
CREATE FUNCTION pgpe_introspect_policies(schema_name text)
RETURNS TABLE(tablename text, policyname text, permissive text,
              cmd text, qual text, with_check text)
AS $$ ... $$ LANGUAGE plpgsql;
```

**Advantages**:
- Reduces round-trips for multi-step introspection
- Encapsulates complex catalog joins
- Could return structured JSON

**Disadvantages**:
- Requires installing functions on the target database, which blurs the
  separation between the engine and the governed system
- Creates a deployment dependency: the engine must manage its own helper
  functions on the target
- The introspection queries (Section 11.4) are straightforward SQL that
  any PostgreSQL client can execute without helper functions

**Assessment**: **Ruled out by the leave-no-trace constraint** (Section 1.6.1).
The engine does not install any functions, schemas, or other objects on the
target database.  The introspection queries from Section 11.4 are straightforward
SQL that the engine executes remotely via the PostgreSQL client library — no
server-side helpers are needed.  In a Phase 2+ multi-database deployment, a
thin monitoring agent could run the same remote queries without requiring
target-side installation.

#### Role D: Runtime Policy Functions (Ruled Out)

A `check_access(table_name, user_id)` function callable from application code
is conceptually interesting but orthogonal to the engine.  It duplicates what
RLS already provides at the database level.  More importantly, it is **ruled
out by the leave-no-trace constraint** (Section 1.6.1): the engine does not
install functions on the target database.  If runtime access checking is
needed for debugging or UI integration, it must be provided by the application
layer or by a separate tool, not by the policy engine.

### 3.3 Summary: PL/pgSQL as a Spectrum

| Role                        | Where it runs | Separation preserved? | Status                           |
|-----------------------------|---------------|-----------------------|----------------------------------|
| A. Compilation target       | Target DB     | Yes (engine-managed)  | Recommended (PoC / Phase 1)     |
| B. Control DB logic         | Control DB    | Yes (engine-owned)    | Appropriate (Phase 1+)          |
| C. Introspection helpers    | Target DB     | Partially             | Ruled out (leave-no-trace, 1.6.1)|
| D. Runtime policy functions | Target DB     | Orthogonal            | Ruled out (leave-no-trace, 1.6.1)|

PL/pgSQL is best understood as a **supplementary component**, not a primary
implementation language.  Its role grows as the deployment model matures,
but the core engine must live outside the database.

---

## 4. Deployment Model

### 4.1 CLI Tool (Recommended Starting Point)

The governance loop's six phases (Section 12.1) map naturally to CLI commands:

```
pgpe define     # Validate and store policy definitions
pgpe analyze    # Run satisfiability, contradiction, isolation proofs
pgpe compile    # Generate SQL artifacts
pgpe apply      # Execute SQL against target database
pgpe monitor    # Introspect and detect drift
pgpe reconcile  # Resolve detected drift
pgpe status     # Show current governance state
```

A CLI tool is the simplest deployment model.  It can be invoked manually, from
CI/CD pipelines, or by cron jobs.  It requires no running server, no state
beyond the filesystem and the target database, and no network listener.

The TrustLogix discussion (Section 16, Phase 0) confirms this: the technical
spike deliverables are operations that map to CLI commands.

### 4.2 Library

Regardless of the CLI surface, the implementation should be structured as
importable modules:

```
pgpe/
  parser/       # DSL → AST
  ast/          # AST types and normalization
  analysis/     # SMT encoding and analysis operations
  compiler/     # AST → SQL generation
  introspect/   # PostgreSQL catalog queries
  drift/        # Diff and reconciliation
  cli/          # CLI entry point wrapping the above
```

This enables embedding the engine in other tools (e.g., a web UI, a
Terraform provider, or language-specific SDKs) without coupling to the CLI.

### 4.3 Sidecar Service (Phase 2+)

For continuous monitoring — running the Monitor phase on a schedule and
alerting on drift — a long-running process is needed.  This could be:

- A systemd service or Docker container that runs `pgpe monitor` on a timer
- A Kubernetes sidecar attached to the database deployment
- A webhook-triggered Lambda/Cloud Function

This is a deployment concern, not an architecture concern.  The same library
code serves all deployment models.

---

## 5. Storage Model

### 5.1 Flat `.policy` Files in Git (Recommended Starting Point)

Policy definitions live as `.policy` files in a git repository, authored in
the DSL defined by Section 13.

```
policies/
  tenant-isolation.policy
  soft-delete.policy
  role-based-access.policy
relationships.yaml           # Declared FK relationships (Section 7.1)
pgpe.yaml                    # Configuration: target DB, governed schemas
```

**Advantages**:
- Policy as code: full version history, branching, pull-request review
- Compilation is reproducible from source (`git checkout` + `pgpe compile`)
- No additional infrastructure beyond git
- Aligns with the spec's version-control requirement (Section 12.1)

**Disadvantages**:
- No query interface over policy history
- Multi-user concurrent editing requires git branching discipline
- No runtime API for listing/searching policies

### 5.2 YAML/JSON (Intermediate Format)

The compiled output or an intermediate representation could be serialized
as YAML or JSON.  This is useful for:

- Machine-readable compilation output (e.g., `pgpe compile --format json`)
- Integration with infrastructure-as-code tools (Terraform, Pulumi)
- Storing compilation snapshots alongside policy definitions

YAML/JSON is not appropriate as the primary authoring format — the DSL
(Section 13) is purpose-built for human readability and is substantially
more concise.

### 5.3 SQLite (Local Cache)

A SQLite database could cache:

- Parsed ASTs (avoiding re-parsing on every invocation)
- Compilation snapshots (for fast drift comparison)
- Analysis results (satisfiability, isolation proof status)

SQLite is an implementation optimization, not a source of truth.  The
canonical policy definitions remain in `.policy` files.

### 5.4 Control PostgreSQL Database (Phase 2+)

For a multi-user service deployment, a dedicated PostgreSQL database could
store:

- Policy definitions (with versioning)
- Drift audit trail
- Analysis history
- User/team ownership

This model becomes relevant when the engine operates as a shared service
rather than a single-user CLI tool.  It aligns with Role B from Section 3.2
(PL/pgSQL on the control database).

---

## 6. Analysis Summary

This section synthesizes the evaluation without making a recommendation.

### 6.1 Viable vs. Disqualified

**Disqualified** (SMT gap):
- **TypeScript** — WASM-only Z3 is insufficient for Sections 8.1–8.5
- **Go** — no maintained SMT solver bindings

**Viable**:
- **Python** — strongest prototyping story, best SMT API, weakest packaging
- **Rust** — strongest type system and packaging, slowest to prototype
- **Java/Kotlin** — best parser tooling (ANTLR), moderate packaging overhead
- **Clojure** — most natural AST manipulation, smallest talent pool

### 6.2 Key Trade-Off Axes

Three axes dominate the decision:

#### Axis 1: SMT Integration Quality vs. Packaging Simplicity

Python has the best SMT integration but the weakest packaging story —
`pyinstaller` bundles are fragile and the Z3 native dependency adds real
complexity.  JVM languages (Java/Kotlin, Clojure) also have strong SMT
integration via Z3's official Java bindings, and their packaging gap has
narrowed: jpackage produces native installers requiring no pre-installed JVM,
and z3-turnkey solves Z3 native library distribution.  The packaging tension
is real but less acute than it was — Python, not JVM, is now the clear
packaging laggard among viable candidates.  Rust has excellent packaging but
more friction with Z3.  This tension reflects a deeper divide: the SMT
solver is a native C library, and languages that embrace native interop
(Python, JVM) handle it more naturally than languages that optimize for
static linking (Rust).

#### Axis 2: Prototyping Speed vs. Long-Term Maintenance

Python is fastest to prototype but hardest to maintain at scale (no type
exhaustiveness, runtime errors).  Rust is slowest to prototype but produces
the most maintainable code.  This axis matters because the project is at
the PoC stage — the priority is validating feasibility, not building for
10-year maintenance.

#### Axis 3: AST Manipulation Ergonomics

The core intellectual work of the engine is AST manipulation: normalization,
rewrite rules, SMT encoding, compilation.  Languages with native algebraic
data types (Rust), pattern matching on data (Clojure), or both (Kotlin
sealed classes + when) will be more productive for this work than languages
requiring workarounds (Python dataclasses without exhaustiveness, Go
interfaces without sum types).

### 6.3 PL/pgSQL Under Each Viable Candidate

PL/pgSQL's role (Section 3) is the same regardless of which language
implements the core engine.  However, the deployment model affects how
PL/pgSQL integrations are managed:

| Candidate       | PL/pgSQL management approach                                                                 |
|-----------------|----------------------------------------------------------------------------------------------|
| **Python**      | Generate `.sql` files from Python; execute via `psycopg`                                     |
| **Rust**        | Generate `.sql` files; execute via `tokio-postgres`; embed SQL in binary via `include_str!`  |
| **Java/Kotlin** | Generate `.sql` files; execute via JDBC; could use Flyway/Liquibase for migration management |
| **Clojure**     | Generate `.sql` strings from Clojure data; execute via `next.jdbc`; SQL as data is idiomatic |

All four candidates can generate and execute PL/pgSQL equally well.  The
choice of core language does not constrain PL/pgSQL's supplementary roles.

### 6.4 Proof-of-Concept Sizing

For each viable candidate, the estimated PoC scope (Section 7) breaks down
roughly as follows:

| Component                      | Python          | Rust            | Java/Kotlin      | Clojure               |
|--------------------------------|-----------------|-----------------|------------------|-----------------------|
| Parser                         | 1–2 days (lark) | 2–3 days (pest) | 2–3 days (ANTLR) | 1–2 days (instaparse) |
| AST + normalization            | 2–3 days        | 3–5 days        | 3–4 days         | 2–3 days              |
| SMT encoding + isolation proof | 2–3 days        | 3–4 days        | 3–4 days         | 2–3 days              |
| SQL compilation                | 1–2 days        | 2–3 days        | 2–3 days         | 1–2 days              |
| Drift detection                | 1–2 days        | 2–3 days        | 2–3 days         | 1–2 days              |
| **Total**                      | **7–12 days**   | **12–18 days**  | **12–17 days**   | **7–12 days**         |

These are single-developer estimates for the PoC scope defined below, not
for a production implementation.

---

## 7. Proof-of-Concept Scope

The PoC is a minimal vertical slice designed to validate the riskiest
assumptions.  It is described in language-agnostic terms so it applies to
whichever candidate is chosen.

### 7.1 Parse Three Policies from Appendix A

Implement a parser for a subset of the Section 13 grammar sufficient to
parse the three policies defined in Appendix A.1:

```
POLICY tenant_isolation
  PERMISSIVE
  FOR SELECT, INSERT, UPDATE, DELETE
  SELECTOR has_column('tenant_id')
  CLAUSE col('tenant_id') = session('app.tenant_id')

POLICY tenant_isolation_via_project
  PERMISSIVE
  FOR SELECT, INSERT, UPDATE, DELETE
  SELECTOR named('tasks') OR named('files')
  CLAUSE
    exists(
      rel(_, project_id, projects, id),
      {col('tenant_id') = session('app.tenant_id')}
    )

POLICY soft_delete
  RESTRICTIVE
  FOR SELECT
  SELECTOR has_column('is_deleted')
  CLAUSE col('is_deleted') = lit(false)
```

**Validates**: parsing, AST construction, selector parsing, traversal atom
parsing.

**Grammar subset required**: All of Section 13 except `fn()` value sources,
`tagged()` selectors, and `NOT` selectors.

### 7.2 Normalize the Section 9.6 Worked Example

Implement the normalization algorithm (Section 9.3) sufficient to reduce the
4-clause example to 2 clauses:

```
Input:
  c1: {col('tenant_id') = session('tid')}
  c2: {col('tenant_id') = session('tid'), col('active') = lit(true)}
  c3: {col('role') = lit('admin'), col('role') = lit('viewer')}
  c4: {col('is_deleted') = lit(false)}

Expected output:
  c1: {col('tenant_id') = session('tid')}
  c4: {col('is_deleted') = lit(false)}
```

**Validates**: atom normalization, contradiction detection (Rule 3),
subsumption elimination (Rule 5), fixpoint loop termination.

### 7.3 Prove Tenant Isolation via SMT

Implement the tenant isolation proof (Section 8.5) for the running example:

- Encode the effective predicate for `users` (direct `tenant_id` check) and
  `tasks` (traversal through `projects`)
- Create two session variable sets with differing `app.tenant_id`
- Submit to Z3 and verify UNSAT

**Validates**: SMT encoding of atoms, traversal encoding with existential
quantifiers, Z3 API integration, timeout handling.

**This is the riskiest component.**  If the SMT encoding of traversal atoms
proves unreliable or too slow, the architecture must be reconsidered.

### 7.4 Compile to SQL Matching Appendix A.5

Implement the compilation function (Section 10.2) and verify that the output
matches the SQL in Appendix A.5:

- `CREATE POLICY tenant_isolation_users ON public.users ...`
- `CREATE POLICY tenant_isolation_via_project_tasks ON public.tasks ...`
  (with nested EXISTS)
- `CREATE POLICY soft_delete_projects ON public.projects ...`

**Validates**: atom compilation, traversal compilation, policy naming
convention, output determinism.

### 7.5 Detect Drift Against a Live PostgreSQL Instance

Connect to a PostgreSQL instance (local Docker container is sufficient),
apply the compiled policies, manually introduce drift (e.g., disable RLS on
a table), and verify that the drift detection algorithm (Section 11.4)
identifies the discrepancy.

**Validates**: PostgreSQL introspection, expression comparison, drift
classification, database connectivity.

### 7.6 PoC Exit Criteria

The PoC is considered successful if:

1. All three Appendix A policies parse without error
2. The 4-clause example normalizes to exactly 2 clauses
3. Tenant isolation is proven (UNSAT) for all governed tables
4. Compiled SQL is character-identical to Appendix A.5
5. Drift detection correctly identifies at least: RLS disabled, missing
   policy, extra policy

---

## 8. Open Questions

These are specific issues identified during ADR development.  Some have been
resolved through analysis of the formal specification; others remain open for
the PoC to validate empirically.  They are ordered by risk.

### 8.1 SQL Expression Comparison for Drift Detection

**Status**: Narrowed — recommended approach identified, PoC must validate.

The drift detection algorithm (Section 11.4) compares USING expressions:
`op.using_expr ≠ ep.using_expr`.  But PostgreSQL's `pg_policies.qual` column
stores a *decompiled* text representation of the expression, which may not
be character-identical to the original `CREATE POLICY` input.

For example, the engine may generate:

```sql
USING (tenant_id = current_setting('app.tenant_id'))
```

But `pg_policies.qual` may return:

```sql
(tenant_id = current_setting('app.tenant_id'::text))
```

**Options considered**:
- **Option 1: Textual normalization** — strip whitespace, normalize quoting,
  and compare strings.  Fragile across PostgreSQL versions.
- **Option 2: AST comparison** — parse both expressions back to an AST and
  compare structurally.  Requires a SQL expression parser (not the DSL parser).
- **Option 3: Recompile-and-compare** — always drop and recreate policies,
  treating the compiled output as the source of truth.  Avoids comparison
  entirely but makes every reconciliation cycle destructive.
- **Option 4: Compile-time round-trip** (recommended) — at compile time, the
  engine creates each policy in a transaction, reads back the decompiled form
  from `pg_policies`, then rolls back.  The decompiled form is stored as part
  of the expected state alongside the original SQL.  At monitor time, the
  engine compares the stored decompiled form against the observed decompiled
  form — both have passed through the same PostgreSQL decompiler, so they
  should match.  This sidesteps the normalization problem entirely by letting
  PostgreSQL be the normalizer.

**Why Option 4 is viable**: the compile step already requires a database
connection for introspection (selector evaluation needs table metadata from
Section 6.2).  The round-trip adds one transaction per policy at compile time
— a one-time cost, not a per-monitor cost.

**Residual risk**: if the PostgreSQL major version changes between compile and
monitor, the decompiled forms could diverge.  Mitigation: re-compile when the
target PostgreSQL version changes.

**Scope note**: regardless of comparison strategy, expression comparison is
only attempted for **managed** policies — those whose names match the compiled
expected state (Section 1.6.2).  Foreign policies (names not in the expected
state) are reported as `extra_policy` without expression comparison.

The PoC should validate Option 4 against a real PostgreSQL instance to confirm
that the round-trip produces stable, comparable decompiled forms.

### 8.2 Session Variable Type Casting

**Status**: Narrowed — recommended approach identified, PoC must validate.

`current_setting()` returns `text` (Section 2.1).  When comparing to a
`uuid` column like `tenant_id`, an explicit cast is needed:

```sql
tenant_id = current_setting('app.tenant_id')::uuid
```

The spec's compilation function (Section 10.2) compiles `session(k)` to
`current_setting('k')` without explicit casts, but the spec also states that
type compatibility is enforced at policy definition time (Definition 2.2).
This implies the compiler has access to type information and can act on it.

**Resolution**: the compiler should emit explicit casts during compilation,
using column type metadata from the table metadata context (Section 6.2).
The compilation rule for session comparisons becomes:

```
(col(x), =, session(k))  →  "x = current_setting('k')::<type_of(x)>"
```

where `type_of(x)` is the column's PostgreSQL type as reported by the
system catalog.  For `text` columns, the cast is omitted (it is the native
return type of `current_setting()`).

This approach is preferred over PL/pgSQL helper functions (Role A) because:
- It requires no additional objects on the target database (consistent with
  the leave-no-trace constraint, Section 1.6.1)
- It makes the cast visible in the compiled SQL, aiding debugging
- It avoids a function-call overhead per row evaluation

The PoC should validate that explicit casts produce correct results for the
common column types: `uuid`, `integer`, `bigint`, `boolean`, `timestamp`.

### 8.3 Z3 Performance on Realistic Policy Sets

**Status**: Open — deferred to PoC benchmarks.

The spec's analysis operations (Section 8) assume the SMT solver is "fast
enough" with a 5-second timeout.  For the PoC's small example, this is
trivially satisfied.  But realistic deployments may have:

- Dozens of policies with multiple clauses each
- Complex selectors matching hundreds of tables
- Traversal atoms creating large existential formulas

**Theoretical risk assessment**: the formulas belong to decidable fragments
(QF-LIA ∪ QF-EUF) for which Z3 has well-optimized decision procedures.
Simple atom comparisons and clause conjunctions produce small formulas.  The
primary risk is traversal atom encoding: each `exists(rel(...), clause)`
introduces existentially quantified variables for the target table row
(Section 8.1), and nested traversals (depth 2+) compound this.  However,
the spec bounds traversal depth globally (Section 7.3, default D=2), which
limits formula growth.

The PoC should measure Z3's wall-clock time for:
- Single-clause satisfiability
- Pairwise clause subsumption
- Full tenant isolation proof with N policies × M tables

If Z3 is too slow, mitigation options include: caching results, incremental
solving, restricting analysis to changed policies only, or decomposing
per-table isolation proofs to avoid combinatorial blowup.

### 8.4 Grammar Disambiguation

**Status**: Resolved — no grammar change needed.

The `AND` keyword appears in two contexts:
- **Clause-level**: `<clause> ::= <atom> ("AND" <atom>)*` — conjunction
  within a clause
- **Selector-level**: `<selector> ::= <selector> "AND" <selector>` —
  intersection of table sets

These belong to **distinct non-terminals** in the BNF grammar (Section 13).
Clause-level `AND` appears only within `<clause>` productions (inside
`CLAUSE` blocks), while selector-level `AND` appears only within `<selector>`
productions (inside `SELECTOR` blocks).  Any context-free parser generator
— ANTLR, lark, instaparse, pest, nearley — handles this disambiguation
automatically because the parser's state (which production it is currently
matching) determines which `AND` interpretation applies.  No grammar
refactoring or distinct keywords are required.

### 8.5 Relationship Declaration Format

**Status**: Resolved — inline in the DSL.

Section 7.1 defines relationships as 4-tuples and states they are "declared
explicitly in the policy configuration, not inferred from database
constraints."  The `rel()` syntax inside `exists()` traversal atoms is both
the declaration and the use — the relationship is specified inline where it
is needed:

```
exists(
  rel(_, project_id, projects, id),
  {col('tenant_id') = session('app.tenant_id')}
)
```

A separate `relationships.yaml` file is unnecessary indirection: the
relationship is meaningful only in the context of the traversal atom that
uses it, and the DSL syntax already captures the full 4-tuple.  The compiler
can extract all declared relationships from the AST if a global relationship
registry is needed (e.g., for validation against database foreign keys as a
convenience check).

### 8.6 The `_` Wildcard in Traversal Atoms

**Status**: Resolved — syntactic placeholder for the source table.

Appendix A.1 uses `rel(_, project_id, projects, id)` where `_` represents
the source table.  The spec's worked example (Section 7.5) confirms this:
when the policy's selector matches `tasks`, the `_` resolves to `tasks` and
the compiled SQL produces `projects.id = tasks.project_id`.

`_` is a **syntactic placeholder resolved at compile time**.  The source
table is the table matched by the policy's selector during compilation.
This means:

- The parser treats `_` as a reserved token in the first position of `rel()`
- The compiler substitutes the actual table name when generating SQL for
  each selector-matched table
- The same policy can apply to multiple tables (e.g., `tasks` and `files`)
  with `_` resolving differently for each

---

## Appendix: Traceability Matrix

Every requirement in Section 1 traces to the spec:

| Requirement              | Spec sections                       |
|--------------------------|-------------------------------------|
| DSL parsing              | 2.1–2.3, 3.1, 4.1, 6.1, 7.1–7.2, 13 |
| AST normalization        | 2.3–2.4, 3.2, 9.1–9.5               |
| SMT integration          | 2.5, 8.1–8.5                        |
| SQL generation           | 10.1–10.7                           |
| PostgreSQL introspection | 6.2, 11.1, 11.4                     |
| Diff / reconciliation    | 11.2–11.5                           |
| Policy storage           | 12.1                                |
| Governance loop          | 12.1–12.5                           |

Every evaluation dimension in Section 2 ties to a specific requirement:

| Dimension           | Requirement          | Critical spec sections |
|---------------------|----------------------|------------------------|
| SMT integration     | 1.3                  | 8.1–8.5                |
| Parsing / grammar   | 1.1                  | 13                     |
| AST / type modeling | 1.2                  | 2–3, 9                 |
| PostgreSQL client   | 1.5, 1.6             | 11                     |
| Prototyping speed   | PoC (Section 7)      | All                    |
| Packaging           | 1.8 (CLI deployment) | 12                     |

---

*End of Architecture Decision Record.*

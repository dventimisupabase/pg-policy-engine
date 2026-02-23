# Architecture Decision Record: pg-policy-engine

**Version**: 1.0
**Date**: 2026-02-22
**Status**: Accepted — Kotlin (JVM) recommended

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
   Question 9.1 for comparison strategies).

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
defined in Section 8 below).

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

This section synthesizes the evaluation.  The recommendation follows in Section 7.

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

For each viable candidate, the estimated PoC scope (Section 8) breaks down
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

## 7. Implementation Language Recommendation

The evaluation in Section 2 identifies four viable candidates; the analysis
in Section 6 maps the key trade-off axes.  This section resolves the
decision.

**Recommendation: Kotlin on the JVM.**

Kotlin is not the top-graded language in any single evaluation dimension,
but it is the only candidate that scores B or above in *every* dimension —
no critical weakness, no disqualifying gap.  For a production-targeted
implementation by a JVM-familiar developer, this balanced profile dominates
the alternatives.

### 7.1 Why JVM over Python

Python leads in prototyping speed (A+) and SMT integration (A), but two
structural liabilities make it a poor fit for a production build:

- **Packaging (C+)**.  `pyinstaller` and `shiv` bundles are fragile,
  platform-specific, and large.  The Z3 native dependency compounds the
  problem — distributing a CLI tool that embeds a C library via pip is a
  well-known pain point.  JVM languages avoid this entirely: jpackage
  produces native installers and z3-turnkey bundles the Z3 shared libraries
  into a single JAR.

- **No exhaustiveness checking**.  The policy algebra's layered AST (value
  sources, atoms, clauses, selectors) demands exhaustive dispatch — every
  match over atom types must handle every variant.  Python's `match/case`
  (3.10+) is syntactically capable but the compiler does not verify
  exhaustiveness.  Kotlin's `when` expressions over `sealed class`
  hierarchies are checked at compile time.

Python remains the fastest path to a throwaway prototype, but the decision
constraint is *build for production*, not *validate feasibility*.

### 7.2 Why JVM over Rust

Rust leads in AST modeling (A), packaging (A+), and long-term correctness
guarantees.  Two factors tip the balance toward JVM:

- **SMT integration friction (B vs. B+)**.  The `z3` crate requires the Z3
  shared library at build time and complicates cross-compilation.  On the
  JVM, Z3's official Java bindings are a Maven dependency, and z3-turnkey
  eliminates native library distribution entirely.  The difference is not
  capability but friction — Rust *can* use Z3, but every build and CI
  pipeline must solve the native dependency.

- **Prototyping speed (C vs. B-)**.  Rust's borrow checker and compile times
  impose a significant upfront cost.  For a project that must traverse
  parsing → normalization → SMT encoding → compilation → introspection →
  drift detection, the cumulative overhead is substantial.  The developer's
  JVM familiarity further widens this gap.

Rust would be the strongest choice if packaging and long-term correctness
were the only concerns.  The production build constraint favors Rust, but
the breadth of the engine (six major subsystems) and the developer's
existing JVM expertise favor a language with faster iteration.

### 7.3 Why Kotlin over Clojure

Both run on the JVM and share the same SMT integration (B+), PostgreSQL
client (JDBC), and packaging story (jpackage + z3-turnkey).  Four factors
differentiate them:

- **Compile-time type safety**.  Kotlin's `sealed class` hierarchies provide
  exhaustive pattern matching verified by the compiler.  Clojure's
  `core.match` operates on dynamic data — elegant for exploration, but
  errors surface at runtime.  For a production engine applying rewrite rules
  to a multi-layered AST, compile-time verification catches missed cases
  before they reach users.

- **ANTLR ecosystem (A+ vs. A)**.  ANTLR is the gold standard for parser
  generators and targets Java/Kotlin natively.  Clojure's `instaparse` is
  excellent but ANTLR's tooling — grammar visualization, debugging,
  ambiguity detection — is unmatched.  The Section 13 grammar maps directly
  to an ANTLR grammar.

- **Talent pool and onboarding**.  Kotlin is a mainstream JVM language with
  broad adoption (Android, server-side, multiplatform).  Clojure's
  community is smaller and the Lisp syntax presents a learning curve for
  contributors unfamiliar with the language.

- **GraalVM native-image predictability**.  Both languages can use GraalVM,
  but Kotlin's compatibility is more straightforward.  Clojure requires
  `--initialize-at-build-time`, cannot use `eval` or unrestricted dynamic
  class generation, and relies on community-maintained tooling for GraalVM
  support.  Key libraries (instaparse, core.match) are not in the official
  GraalVM compatibility matrix.

Clojure's data-oriented programming model is a natural fit for AST
manipulation, and REPL-driven development is excellent for exploring
algebraic transformations.  But for a production build, Kotlin's compile-time
guarantees and ecosystem breadth outweigh Clojure's ergonomic advantages.

### 7.4 Why Kotlin over Java

Kotlin and Java share the JVM ecosystem — same ANTLR, same JDBC, same
jpackage, same z3-turnkey.  Kotlin is preferred for four language-level
advantages:

- **Sealed classes + `when` expressions**.  Kotlin's `sealed class`
  hierarchies with exhaustive `when` are more concise and ergonomic than
  Java's sealed classes with `switch` (available since Java 17 but still
  more verbose).

- **Data classes**.  AST nodes modeled as `data class` get `equals`,
  `hashCode`, `copy`, and destructuring for free — essential for an engine
  that constantly compares and transforms AST nodes.

- **Null safety**.  Kotlin's type system distinguishes nullable and
  non-nullable types, eliminating a class of runtime errors common in
  Java code that handles optional metadata and nullable database results.

- **Conciseness**.  Kotlin typically requires 30–40% fewer lines than
  equivalent Java for the same logic.  For a project with six major
  subsystems, this compounds into a meaningful difference in development
  velocity and code navigability.

### 7.5 Acknowledged Trade-Offs

Kotlin is not the top-graded candidate in any single dimension:

| Dimension                    | Kotlin grade | Leader        | Leader grade |
|------------------------------|-------------|---------------|--------------|
| **SMT integration**          | B+          | Python        | A            |
| **Parsing / grammar**        | A+          | (tied leader) | A+           |
| **AST / type modeling**      | B+          | Rust          | A            |
| **PostgreSQL client**        | A           | (tied leader) | A            |
| **Prototyping speed**        | B-          | Python        | A+           |
| **Packaging / distribution** | B-          | Rust / Go     | A+           |

The critical observation: Kotlin has no grade below B-.  Every other viable
candidate has at least one dimension graded C or below:

| Candidate  | Weakest grade | Dimension                 |
|------------|--------------|---------------------------|
| Python     | C+           | Packaging                 |
| Rust       | C            | Prototyping speed         |
| Clojure    | C+           | Packaging                 |
| **Kotlin** | **B-**       | **Prototyping / Packaging** |

For a production build that must succeed across all six dimensions, the
candidate with the highest floor wins over candidates with higher ceilings
but lower floors.

### 7.6 Supporting Evidence from Resolved Open Questions

The resolved open questions (Section 9) independently reinforce the Kotlin
recommendation:

- **JDBC enables compile-time round-trip** (Question 9.1).  The expression
  comparison strategy requires creating policies in a transaction, reading
  back the decompiled form, and rolling back.  JDBC's transaction control
  makes this straightforward.

- **`when` expressions handle casting rules** (Question 9.2).  The compiler
  must emit explicit casts based on column type.  Kotlin's `when` over the
  column type enum produces a concise, exhaustive dispatch table.

- **Z3 performance is language-agnostic** (Question 9.3).  Z3 solving time
  is dominated by the formula, not the binding language.  The JVM's JNI
  overhead for Z3 calls is negligible for formula-level operations.

- **ANTLR handles grammar disambiguation natively** (Question 9.4).  The
  `AND` overloading between clause-level and selector-level contexts is
  resolved automatically by ANTLR's parser state — no grammar refactoring
  needed.

---

## 8. Proof-of-Concept Scope

The PoC is a minimal vertical slice designed to validate the riskiest
assumptions.  It is described in language-agnostic terms so it applies to
whichever candidate is chosen.

### 8.1 Parse Three Policies from Appendix A

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

### 8.2 Normalize the Section 9.6 Worked Example

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

### 8.3 Prove Tenant Isolation via SMT

Implement the tenant isolation proof (Section 8.5) for the running example:

- Encode the effective predicate for `users` (direct `tenant_id` check) and
  `tasks` (traversal through `projects`)
- Create two session variable sets with differing `app.tenant_id`
- Submit to Z3 and verify UNSAT

**Validates**: SMT encoding of atoms, traversal encoding with existential
quantifiers, Z3 API integration, timeout handling.

**This is the riskiest component.**  If the SMT encoding of traversal atoms
proves unreliable or too slow, the architecture must be reconsidered.

### 8.4 Compile to SQL Matching Appendix A.5

Implement the compilation function (Section 10.2) and verify that the output
matches the SQL in Appendix A.5:

- `CREATE POLICY tenant_isolation_users ON public.users ...`
- `CREATE POLICY tenant_isolation_via_project_tasks ON public.tasks ...`
  (with nested EXISTS)
- `CREATE POLICY soft_delete_projects ON public.projects ...`

**Validates**: atom compilation, traversal compilation, policy naming
convention, output determinism.

### 8.5 Detect Drift Against a Live PostgreSQL Instance

Connect to a PostgreSQL instance (local Docker container is sufficient),
apply the compiled policies, manually introduce drift (e.g., disable RLS on
a table), and verify that the drift detection algorithm (Section 11.4)
identifies the discrepancy.

**Validates**: PostgreSQL introspection, expression comparison, drift
classification, database connectivity.

### 8.6 PoC Exit Criteria

The PoC is considered successful if:

1. All three Appendix A policies parse without error
2. The 4-clause example normalizes to exactly 2 clauses
3. Tenant isolation is proven (UNSAT) for all governed tables
4. Compiled SQL is character-identical to Appendix A.5
5. Drift detection correctly identifies at least: RLS disabled, missing
   policy, extra policy

Section 10 defines the complete testing strategy and evaluation rubric that
subsumes and extends these exit criteria.

---

## 9. Open Questions

These are specific issues identified during ADR development.  All six have
been resolved through analysis of the formal specification, PostgreSQL
documentation, and SMT solver research.  They are presented in their original
risk order, with resolutions inline.

### 9.1 SQL Expression Comparison for Drift Detection

**Status**: Resolved — compile-time round-trip (Option 4).

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

This happens because `pg_policies` uses `pg_get_expr()` (implemented in
`ruleutils.c`) to decompile the stored `pg_node_tree` representation back
to SQL text.  The decompiler applies canonical whitespace, conservative
parenthesization, fully qualified column references, and may surface explicit
casts for coercions that the parser inserted implicitly.

**Options considered**:
- **Option 1: Textual normalization** — strip whitespace, normalize quoting,
  and compare strings.  Fragile across PostgreSQL versions.
- **Option 2: AST comparison** — parse both expressions back to an AST and
  compare structurally.  Requires a SQL expression parser (not the DSL parser).
- **Option 3: Recompile-and-compare** — always drop and recreate policies,
  treating the compiled output as the source of truth.  Avoids comparison
  entirely but makes every reconciliation cycle destructive.

**Resolution: Option 4 — compile-time round-trip.**  At compile time, the
engine creates each policy in a transaction, reads back the decompiled form
from `pg_policies`, then rolls back.  The decompiled form is stored as part
of the expected state alongside the original SQL.  At monitor time, the
engine compares the stored decompiled form against the observed decompiled
form — both have passed through the same PostgreSQL decompiler, so they
match.

This approach is validated by the following properties of `pg_get_expr()`:

- **Deterministic**: given the same `pg_node_tree` and the same catalog state
  (relation columns, types), the function always produces identical output.
  The deparse logic is a deterministic tree walk with no randomness.
- **Transaction-safe**: PostgreSQL DDL is fully transactional.  A `CREATE
  POLICY` within a transaction inserts catalog rows visible to subsequent
  queries in the same transaction (via `CommandCounterIncrement`).  The
  decompiled form observed before rollback is identical to what would be
  observed after commit, because the stored `pg_node_tree` and catalog state
  are the same.
- **Compile step already requires a database connection**: selector evaluation
  needs table metadata from Section 6.2.  The round-trip adds one transaction
  per policy at compile time — a one-time cost, not a per-monitor cost.

**Version caveat**: `pg_get_expr()` output can change across PostgreSQL major
versions as `ruleutils.c` is actively maintained (parenthesization, quoting,
and deparse format changes appear in release notes for PostgreSQL 15–17).
Mitigation: the engine should record the target PostgreSQL major version at
compile time and require recompilation when the version changes.

**Scope note**: expression comparison is only attempted for **managed**
policies — those whose names match the compiled expected state (Section
1.6.2).  Foreign policies are reported as `extra_policy` without expression
comparison.

### 9.2 Session Variable Type Casting

**Status**: Resolved — compiler must emit explicit casts.

`current_setting()` returns `text` (Section 2.1).  When comparing to a
non-text column like `tenant_id uuid`, an explicit cast is **mandatory** —
without it, `CREATE POLICY` fails at definition time with `operator does not
exist: uuid = text`.

This is not a PostgreSQL quirk but a consequence of its type resolution
rules (Section 10.2 of the PostgreSQL documentation).  `current_setting()`
is a function declared to return `text` — a concrete, resolved type.
PostgreSQL's operator resolution cannot implicitly cast `text` to non-string
types because the built-in catalog defines casts from `text` to `uuid`,
`integer`, `bigint`, `boolean`, `timestamp`, etc. as **explicit-only**
(`castcontext = 'e'` in `pg_cast`).  This differs from string *literals*,
which have type `unknown` and are convertible to anything.

**Resolution**: the compiler must emit explicit casts during compilation,
using column type metadata from the table metadata context (Section 6.2).
The compilation rule for session comparisons becomes:

```
(col(x), =, session(k))  →  "x = current_setting('k')::<type_of(x)>"
```

where `type_of(x)` is the column's PostgreSQL type as reported by the
system catalog.  For `text` and `varchar` columns, the cast is omitted
(they are binary-coercible with `current_setting()`'s return type).

The complete casting requirements:

| Column type     | Cast required? | Compiled form                              |
|-----------------|----------------|--------------------------------------------|
| `text`          | No             | `x = current_setting('k')`                 |
| `varchar`       | No             | `x = current_setting('k')`                 |
| `uuid`          | **Yes**        | `x = current_setting('k')::uuid`           |
| `integer`       | **Yes**        | `x = current_setting('k')::integer`        |
| `bigint`        | **Yes**        | `x = current_setting('k')::bigint`         |
| `boolean`       | **Yes**        | `x = current_setting('k')::boolean`        |
| `timestamp`     | **Yes**        | `x = current_setting('k')::timestamp`      |
| `timestamptz`   | **Yes**        | `x = current_setting('k')::timestamptz`    |

This approach is preferred over PL/pgSQL helper functions (Role A) because:
- It requires no additional objects on the target database (consistent with
  the leave-no-trace constraint, Section 1.6.1)
- It makes the cast visible in the compiled SQL, aiding debugging
- It avoids a function-call overhead per row evaluation
- Incorrect casts fail immediately at `CREATE POLICY` time, providing
  early error detection

**Note**: the formal specification's compilation function (Section 10.2)
should be updated to reflect this casting requirement.

### 9.3 Z3 Performance on Realistic Policy Sets

**Status**: Resolved — risk is negligible for realistic policy sets.

The spec's analysis operations (Section 8) assume the SMT solver is "fast
enough" with a 5-second timeout.  Both theoretical analysis and industrial
precedent confirm this assumption is safe.

**Why the formulas are easy for Z3**:

- The formulas belong to decidable fragments (QF-LIA ∪ QF-EUF) for which Z3
  has well-optimized decision procedures.  QF-EUF is solved by the congruence
  closure algorithm in O(n log² n); QF-LIA is solved by a Simplex-based
  procedure.
- Existential quantifiers from traversal atoms are eliminated by
  **Skolemization**: Z3 replaces `∃x. φ(x)` with a fresh constant `c` and
  checks `φ(c)`.  With traversal depth bounded at 2 (Section 7.3), each
  traversal introduces ~3–8 Skolem constants (one per relevant column in the
  target table).  A worst-case tenant isolation proof with nested traversals
  produces ~50–150 total variables and ~20–80 constraints — trivial for Z3.
- There is no quantifier alternation (no ∀∃ patterns).  After Skolemization,
  all formulas are quantifier-free.

**Industrial precedent**: Amazon's Zelkova system uses SMT solvers (Z3, CVC4,
cvc5) to verify access control policies, processing over 1 billion queries
per day with typical solving times of 10 ms to a few seconds.  Zelkova's
workload is *harder* than this engine's — IAM policies involve string/regex
constraints and complex principal hierarchies.  This engine uses only integer
arithmetic and equality, which are simpler theories.

**Expected performance**: sub-millisecond to low-millisecond solving times
for typical policy formulas.  The 5-second timeout in the spec (Section 8.1)
is very generous and should never be reached for legitimate policy formulas.

**Residual risks** (all low probability):
- Large disjunctions (100+ clauses per policy) could slow tenant isolation
  proofs due to O(n²) clause-pair combinations — mitigated by decomposing
  per-table proofs
- Large IN-lists create disjunctions in the encoding — mitigated by the
  spec's atom merging rewrite rule (Rule 6, Section 9)
- SMT solving is sensitive to formula formulation — mitigated by normalizing
  policies to canonical form before encoding (Section 9.3)

The PoC should include a sanity-check benchmark confirming sub-second solving
for the Appendix A examples, but Z3 performance is no longer considered a
project risk.

### 9.4 Grammar Disambiguation

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

### 9.5 Relationship Declaration Format

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

### 9.6 The `_` Wildcard in Traversal Atoms

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

## 10. Testing Strategy and Evaluation Rubric

Section 8 defines the PoC scope — what to build.  Section 8.6 lists five
binary pass/fail exit criteria — the minimal gates.  This section defines
how the PoC is tested (10.1) and how it is evaluated holistically (10.2).
The testing strategy and evaluation rubric subsume the Section 8.6 exit
criteria: every exit criterion maps to one or more test cases below, and
the rubric adds dimensions (performance, architectural fitness, code
quality, production-readiness) that binary pass/fail cannot capture.

### 10.1 Testing Strategy

Tests are organized by test type, not by subsystem.  Each test traces to
its specification source.

#### 10.1.1 Unit Tests

Unit tests validate individual subsystem behavior in isolation, with no
external dependencies (no PostgreSQL, no Z3 for parser/normalization/
compiler tests).

**Parser tests**:

| Test case | Input | Expected output | Spec reference |
|-----------|-------|-----------------|----------------|
| Parse tenant_isolation policy | Appendix A.1, policy 1 | AST with permissive type, 4 commands, has_column selector, single equality clause | 4.1, 13 |
| Parse tenant_isolation_via_project policy | Appendix A.1, policy 2 | AST with traversal atom, rel() with wildcard source | 7.1–7.3, 13 |
| Parse soft_delete policy | Appendix A.1, policy 3 | AST with restrictive type, SELECT-only command, literal boolean atom | 4.1, 13 |
| Parse compound selector | `has_column('x') AND named('y')` | AND selector node with two children | 6.1, 13 |
| Parse disjunctive selector | `named('tasks') OR named('files')` | OR selector node with two children | 6.1, 13 |
| Parse multi-clause policy | Two clauses joined by OR CLAUSE | Policy with two clause nodes | 3.1, 13 |
| Parse all binary operators | Each of `=`, `!=`, `<`, `>`, `<=`, `>=`, `IN`, `NOT IN`, `LIKE`, `NOT LIKE` | Correct operator AST node | 2.1–2.2, 13 |
| Parse unary operators | `IS NULL`, `IS NOT NULL` | Unary atom AST node | 2.2, 13 |
| Parse literal types | String, integer, boolean, null, list | Correct literal AST node per type | 13 |
| Reject malformed input | Missing CLAUSE keyword | Parse error with source location | 13 |
| Reject unterminated traversal | `exists(rel(_, a, b, c), {col('x') = lit(1)}` — missing `)` | Parse error | 13 |

**Normalization tests**:

| Test case | Input | Expected output | Spec reference |
|-----------|-------|-----------------|----------------|
| Idempotence — duplicate atom removal | `{col('a') = lit(1), col('a') = lit(1)}` | `{col('a') = lit(1)}` | Rule 1, Section 9.1 |
| Absorption | `c1: {A}` OR `c2: {A, B}` | `c1: {A}` | Rule 2, Section 9.1 |
| Contradiction elimination | `{col('role') = lit('admin'), col('role') = lit('viewer')}` | Clause removed (⊥) | Rule 3, Section 9.2 |
| Tautology detection | `{col('x') = col('x')}` | Clause becomes ⊤ | Rule 4, Section 9.2 |
| Subsumption elimination | Section 9.6 worked example (4 clauses → 2) | `c1, c4` only | Rule 5, Section 9.3 |
| Atom merging — IN-list intersection | `{col('x') IN [1,2,3], col('x') IN [2,3,4]}` | `{col('x') IN [2,3]}` | Rule 6, Section 9.4 |
| Section 9.6 full worked example | 4-clause input from Section 9.6 | 2-clause output per Section 9.6 | Section 9.6 |
| Fixpoint convergence | Input requiring multiple passes | Stable output after ≤ N passes | Section 9.5, Property 9.1 |
| Atom normalization — column-left ordering | `session('k') = col('x')` | `col('x') = session('k')` | Section 2.3 |
| Atom normalization — operator canonicalization | `col('x') > lit(5)` with non-canonical form | Canonical operator form | Section 2.3 |

**SMT analysis tests** (unit tests use Z3):

| Test case | Input | Expected output | Spec reference |
|-----------|-------|-----------------|----------------|
| Satisfiable clause | `{col('x') = lit(1)}` | SAT | Section 8.1 |
| Contradictory clause | `{col('x') = lit(1), col('x') = lit(2)}` | UNSAT | Section 8.1, Property 2.1 |
| Tenant isolation — direct column | tenant_id equality policy on single table | UNSAT (isolation holds) | Section 8.5, Theorem 8.1 |
| Tenant isolation — traversal | tenant_isolation_via_project on tasks | UNSAT (isolation holds) | Section 8.5, Theorem 8.1 |
| Subsumption check | Clause A vs. clause A ∧ B | A subsumes A ∧ B | Section 8.2 |
| Contradiction — effective predicate | Table with contradictory permissive and restrictive policies | Effective predicate UNSAT reported | Section 8.4 |
| Timeout handling | Formula designed to exceed timeout | UNKNOWN result within timeout bound | Section 8.1 |

**SQL compiler tests**:

| Test case | Input | Expected output | Spec reference |
|-----------|-------|-----------------|----------------|
| Compile equality atom | `col('tenant_id') = session('app.tenant_id')` | `tenant_id = current_setting('app.tenant_id')` | Section 10.2 |
| Compile traversal atom | `exists(rel(_, project_id, projects, id), ...)` | `EXISTS (SELECT 1 FROM projects WHERE projects.id = _.project_id AND ...)` | Section 10.2 |
| Compile literal boolean | `col('is_deleted') = lit(false)` | `is_deleted = false` | Section 10.2 |
| Session variable casting — uuid column | `col('tenant_id') = session('app.tenant_id')` on uuid column | `tenant_id = current_setting('app.tenant_id')::uuid` | ADR 9.2 |
| Session variable casting — text column | `col('name') = session('app.user')` on text column | `name = current_setting('app.user')` (no cast) | ADR 9.2 |
| Session variable casting — integer column | `col('org_id') = session('app.org_id')` on integer column | `org_id = current_setting('app.org_id')::integer` | ADR 9.2 |
| Appendix A.5 golden output | All three Appendix A policies | Character-identical match to Appendix A.5 SQL | Appendix A.5, Property 10.2 |
| Determinism | Compile same input twice | Identical output both times | Property 10.2 |
| Policy naming convention | tenant_isolation on users table | `tenant_isolation_users` | Section 10.6 |

**Introspection tests** (snapshot-based for unit tests):

| Test case | Input | Expected output | Spec reference |
|-----------|-------|-----------------|----------------|
| RLS enabled detection | Snapshot with relrowsecurity = true | RLS status: enabled | Section 11.1 |
| RLS disabled detection | Snapshot with relrowsecurity = false | RLS status: disabled | Section 11.1 |
| RLS forced detection | Snapshot with relforcerowsecurity = true | RLS status: forced | Section 11.1 |
| Policy extraction | Snapshot with pg_policies rows | Structured policy list | Section 11.1 |
| Expression read-back | Snapshot with pg_policies.qual | Decompiled expression string | Section 11.1, ADR 9.1 |

**Drift detection tests**:

| Test case | Input (observed vs. expected) | Expected drift type | Spec reference |
|-----------|-------------------------------|---------------------|----------------|
| Missing policy | Expected has policy, observed does not | `missing_policy` | Section 11.3 |
| Extra policy | Observed has policy not in expected | `extra_policy` | Section 11.3 |
| Modified policy | USING expression differs | `modified_policy` | Section 11.3 |
| Missing GRANT | Expected GRANT absent | `missing_grant` | Section 11.3 |
| Extra GRANT | Observed GRANT not in expected | `extra_grant` | Section 11.3 |
| RLS disabled | Expected enabled, observed disabled | `rls_disabled` | Section 11.3 |
| RLS not forced | Expected forced, observed not forced | `rls_not_forced` | Section 11.3 |
| Foreign policy classification | Unrecognized policy name on governed table | `extra_policy` (Warning) | ADR 1.6.2 |

#### 10.1.2 Property-Based Tests

Property-based tests use random input generators with algebraic property
assertions.  These are directly executable versions of the specification's
proved properties.

**Generator bounds**: policies with max 5 clauses, max 5 atoms per clause,
traversal depth 0–2, value sources drawn from `col`, `session`, `lit`.

**Normalization properties**:

| Property | Assertion | Generator | Spec reference |
|----------|-----------|-----------|----------------|
| Idempotence | `normalize(normalize(p)) = normalize(p)` | Random policy | Property 9.1 |
| Denotation preservation | `denote(p) = denote(normalize(p))` (via SMT equivalence check) | Random policy | Property 9.2 |
| Termination | `normalize(p)` completes within bounded iterations | Random policy | Property 9.1 (termination proof) |
| Clause count non-increase | `clauseCount(normalize(p)) ≤ clauseCount(p)` | Random policy | Section 9.3 |
| Normal form stability | If `p` is already in normal form, `normalize(p) = p` | Pre-normalized policy | Section 9.5 |

**Compilation properties**:

| Property | Assertion | Generator | Spec reference |
|----------|-----------|-----------|----------------|
| Determinism | `compile(p) = compile(p)` across invocations | Random normalized policy | Property 10.2 |

**Composition properties**:

| Property | Assertion | Generator | Spec reference |
|----------|-----------|-----------|----------------|
| Permissive monotonicity | Adding a permissive policy does not reduce the accessible row set | Random policy pair | Property 5.1 |
| Restrictive anti-monotonicity | Adding a restrictive policy does not increase the accessible row set | Random policy pair | Property 5.2 |

#### 10.1.3 Integration Tests

Integration tests require live PostgreSQL (via Testcontainers) and/or Z3.

**Compile-time round-trip** (ADR 9.1 strategy):

| Test case | Steps | Expected outcome | Spec reference |
|-----------|-------|------------------|----------------|
| Round-trip expression fidelity | Create policy in transaction → read back `pg_policies.qual` → rollback → compare | Decompiled form matches stored expected form | ADR 9.1 |
| Round-trip with explicit casts | Create policy with `::uuid` cast → read back → compare | Cast preserved in decompiled form | ADR 9.1, 9.2 |

**End-to-end governance loop** (Appendix A lifecycle):

| Test case | Steps | Expected outcome | Spec reference |
|-----------|-------|------------------|----------------|
| Full lifecycle | Parse (A.1) → normalize → compile (A.5) → apply → introspect → verify zero drift | All stages succeed, drift report empty | Appendix A.1–A.9, Section 12.1 |
| Drift introduction and detection | Apply policies → manually disable RLS → run monitor | `rls_disabled` drift detected | Appendix A.7, Section 11.3 |
| Drift reconciliation | Detect drift → run reconcile → re-monitor | Drift resolved, zero drift on re-check | Appendix A.9, Section 11.5 |

**SMT solver integration**:

| Test case | Steps | Expected outcome | Spec reference |
|-----------|-------|------------------|----------------|
| JNI connectivity | Load Z3 via z3-turnkey → create solver → check trivial formula | SAT result returned | Section 8.1 |
| SAT/UNSAT verification | Submit known-SAT and known-UNSAT formulas | Correct results for both | Section 8.1 |
| Timeout handling | Submit expensive formula with short timeout | UNKNOWN returned within timeout | Section 8.1 |
| Appendix A benchmark | Run full analysis on Appendix A policy set | All operations complete in < 1 s | Section 8.1, ADR 9.3 |

**PostgreSQL introspection**:

| Test case | Steps | Expected outcome | Spec reference |
|-----------|-------|------------------|----------------|
| Apply and introspect | Apply compiled policies → introspect → diff | Zero drift detected | Section 11.1–11.4 |
| Introspect RLS status | Enable/force RLS → query pg_class | Correct status reported | Section 11.1 |
| Introspect policy expressions | Create policy → read pg_policies.qual | Expression matches decompiled form | Section 11.1, ADR 9.1 |

#### 10.1.4 Spec Compliance Tests

Spec compliance tests are golden-file tests using the specification's own
worked examples as fixed test vectors.  These tests ensure the implementation
produces output identical to the specification's examples.

| Golden file | Source | Content verified | Spec reference |
|-------------|--------|------------------|----------------|
| Appendix A.1 — policy definitions | Appendix A.1 | Parse without error, AST structure matches | Appendix A.1, Section 13 |
| Appendix A.3 — selector resolution | Appendix A.3 | Correct table-to-policy mapping | Appendix A.3, Section 6.2 |
| Appendix A.5 — compiled SQL | Appendix A.5 | Character-identical SQL output | Appendix A.5, Section 10.2 |
| Appendix A.7 — drift report | Appendix A.7 | Correct drift types and details | Appendix A.7, Section 11.3 |
| Appendix A.9 — reconciliation | Appendix A.9 | Correct reconciliation actions | Appendix A.9, Section 11.5 |
| Section 9.6 — normalization example | Section 9.6 | 4 clauses → 2 clauses, exact output match | Section 9.6 |
| Section 10.7 — compilation example | Section 10.7 | SQL output matches worked example | Section 10.7 |
| Section 8.1 — contradiction examples | Section 8.1 | SMT correctly identifies contradictions | Section 8.1 |

#### 10.1.5 Testing Infrastructure

Practical tooling decisions for the Kotlin/JVM implementation:

- **JUnit 5** for unit and integration tests.  JUnit 5's `@Tag` annotation
  separates fast unit tests from slow integration tests.
- **Kotest property testing module** (or **jqwik** as an alternative) for
  property-based tests.  Kotest integrates with JUnit 5 and provides
  generators, shrinking, and seed replay.
- **Testcontainers** for disposable PostgreSQL instances.  Each integration
  test gets a fresh PostgreSQL container, ensuring test isolation and
  eliminating shared-state failures.
- **z3-turnkey** for Z3 native library resolution.  The same dependency
  used in production resolves Z3 in the test environment with no additional
  configuration.
- **Golden files** stored as test resources under `src/test/resources/golden/`.
  Each golden file is a plain text file containing the expected output for
  a spec compliance test.  Tests compare actual output against the golden
  file and fail on any difference.

### 10.2 Evaluation Rubric

Five dimensions, each with concrete criteria and grade thresholds.  The
rubric evaluates the PoC holistically — not just whether it passes, but
how well it passes.

#### 10.2.1 Correctness

Correctness is all-or-nothing: the implementation either satisfies the
specification's theorems and properties or it does not.  There is no
partial credit.

| Criterion | Measurement method | Spec reference |
|-----------|--------------------|----------------|
| Normalization preserves denotation | Property-based test: SMT equivalence of input and output for 10,000+ random inputs | Property 9.2 |
| Normalization is idempotent | Property-based test: `normalize(normalize(p)) = normalize(p)` for 10,000+ random inputs | Property 9.1 |
| Compilation is deterministic | Property-based test: same input produces identical SQL across invocations | Property 10.2 |
| Tenant isolation holds | SMT proof returns UNSAT for all governed tables in Appendix A | Theorem 8.1 |
| Contradiction detection is sound | SMT correctly identifies known-contradictory and known-satisfiable formulas | Section 8.1, 8.4 |
| Subsumption is correct | SMT implication checks agree with manual analysis for all Appendix A policy pairs | Section 8.2 |
| Compiled SQL matches specification | Golden-file comparison against Appendix A.5 | Appendix A.5, Theorem 10.1 |
| Drift detection identifies all seven types | Unit tests cover all drift types from Section 11.3 | Section 11.3 |
| Rewrite rules are faithfully implemented | Unit tests for each of the six rules (Rules 1–6) | Sections 9.1–9.4 |

**Grade**: Pass (all criteria met) or Fail (any criterion not met).

#### 10.2.2 Performance

Performance targets are calibrated for a CLI tool that runs infrequently
(e.g., during CI/CD or manual governance cycles).  These targets are
informational at the PoC stage — they validate that the architecture is
not fundamentally flawed, but they are not gating.

| Subsystem | Target | Measurement |
|-----------|--------|-------------|
| Parse (3 policies) | < 100 ms | Wall-clock time, cold parser |
| Normalize (10-clause policy) | < 50 ms | Wall-clock time, fixpoint loop |
| SMT isolation (1 table) | < 1 s | Z3 solving time |
| SMT full analysis (6 tables) | < 5 s | Total Z3 time across all tables |
| Compile (all policies) | < 100 ms | Wall-clock time |
| Introspect (6 tables) | < 500 ms | Wall-clock time including network |
| Drift detect (6 tables) | < 200 ms | Wall-clock time, comparison only |
| End-to-end | < 10 s | Full governance loop |
| JVM cold start | < 3 s | Time to first useful output |

**Grade**:
- **Pass**: all targets met.
- **Acceptable**: all targets within 2× (e.g., parse < 200 ms).
- **Fail**: any target exceeded by >10×.

Performance is informational at PoC stage, not gating.  A Fail grade
triggers investigation but does not block the PoC exit criteria.

#### 10.2.3 Architectural Fitness

Architectural fitness evaluates whether the implementation's structure
supports the specification's composability requirements and enables
Phase 1+ extensions.

| Criterion | What it means |
|-----------|---------------|
| Subsystem independence | Parser, normalizer, SMT analyzer, compiler, introspector, and drift detector are independently testable modules with no circular dependencies |
| Data-boundary contracts | Subsystems communicate through the sealed class AST — no shared mutable state, no side channels |
| Testability without external deps | Parser, normalization, and compiler unit tests run without PostgreSQL or Z3 |
| Exhaustive `when` expressions | Every `when` over a sealed class hierarchy covers all subtypes without an `else` branch |
| Composable governance loop | The six governance phases (Section 12.1) compose as a pipeline: each phase's output is the next phase's input |

#### 10.2.4 Code Quality

Code quality evaluates Kotlin idiom utilization and engineering
discipline.  These criteria are specific to the Kotlin/JVM implementation
recommended in Section 7.

| Criterion | What it means |
|-----------|---------------|
| Sealed class hierarchies for all AST sum types | Value sources, atoms, clauses, selectors, and policies are modeled as sealed class hierarchies |
| Exhaustive `when` without `else` | All pattern matches over sealed types use exhaustive `when` — the compiler verifies completeness |
| Data classes for structural equality | AST nodes use `data class` so that `equals`, `hashCode`, and `copy` are derived from structure |
| Null safety — no `!!` outside tests | Production code uses nullable types and safe calls; the non-null assertion operator `!!` is restricted to test code |
| Immutable AST | AST nodes are immutable `data class` instances; transformations produce new trees |
| Pipeline-style function composition | The governance loop reads as a pipeline: `parse → normalize → analyze → compile → apply → monitor` |
| Meaningful error reporting with source locations | Parse errors and validation failures include the source file, line, and column |

#### 10.2.5 Production-Readiness Gates

Production-readiness gates are preconditions for the PoC → Phase 1
transition.  They are distinct from the PoC exit criteria (Section 8.6):
exit criteria validate that the PoC *works*, while production-readiness
gates validate that the PoC *can evolve*.

| Gate | Description | Category |
|------|-------------|----------|
| G1 | SMT encoding of traversal atoms validated — Z3 correctly proves isolation through foreign-key joins | Architecture |
| G2 | Compile-time round-trip viable — `pg_get_expr()` decompiled forms are stable within a PostgreSQL major version | Architecture |
| G3 | ANTLR grammar handles PoC subset without ambiguity — no parser conflicts, no backtracking | Tooling |
| G4 | z3-turnkey operational — Z3 native libraries load on macOS (ARM64), Linux (x86-64), and in CI | Tooling |
| G5 | Testcontainers operational in CI — disposable PostgreSQL instances start and stop reliably | Tooling |
| G6 | Normalization properties hold under 10,000+ random inputs — no counterexamples found | Correctness |
| G7 | Performance within targets — all subsystems meet the targets in Section 10.2.2 | Performance |
| G8 | Architecture supports Phase 1 extensions — adding `fn()`, `tagged()`, `NOT`, and GRANTs does not require structural changes to the AST or governance loop | Extensibility |

**Decision rule**:
- **G1–G6 must pass.**  G1 or G2 failure indicates a fundamental
  architectural assumption is wrong — reconsider the SMT encoding strategy
  or the expression comparison approach.  G3–G6 failure indicates a
  tooling or testing issue that is likely resolvable without architectural
  changes.
- **G7–G8 must be acceptable.**  G7 (performance) within 2× of targets
  is acceptable at PoC stage.  G8 (extensibility) requires a qualitative
  assessment that the sealed class hierarchy and governance pipeline can
  accommodate Phase 1 additions without pervasive refactoring.

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
| Testing & evaluation     | 8.1–8.5, 9.1–9.6, 10.1–10.7, 11.1–11.5, 14, A.1–A.9 |

Every evaluation dimension in Section 2 ties to a specific requirement:

| Dimension           | Requirement          | Critical spec sections |
|---------------------|----------------------|------------------------|
| SMT integration     | 1.3                  | 8.1–8.5                |
| Parsing / grammar   | 1.1                  | 13                     |
| AST / type modeling | 1.2                  | 2–3, 9                 |
| PostgreSQL client   | 1.5, 1.6             | 11                     |
| Prototyping speed   | PoC (Section 8)      | All                    |
| Packaging           | 1.8 (CLI deployment) | 12                     |
| Testing strategy    | All (1.1–1.6)        | 8–11, 13, 14, Appendix A |
| Evaluation rubric   | PoC (Section 8)      | 9.1–9.2, 10.1–10.2    |

---

*End of Architecture Decision Record.*

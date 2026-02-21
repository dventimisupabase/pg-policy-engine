
# Zanzibar, Datalog, and PostgreSQL RLS — Design Notes

## 1. Zanzibar Basics

Google Zanzibar models authorization using two main constructs:

- **Namespace configurations (schema)** define:
  - Object types
  - Relations between objects and subjects
  - Allowed subject types per relation
  - Relation rewrites/inheritance rules

- **Relation tuples** represent facts:
  - `(object, relation, subject)` assertions
  - The current state of the authorization graph

Conceptually:
- Namespace configuration = vocabulary + grammar
- Relation tuples = well‑formed sentences (facts)

The authorization engine answers queries by evaluating these facts under schema-defined rules.

Zanzibar is closely related to **Datalog**, not Prolog:
- Facts = tuples
- Rules = relation rewrites
- Queries = authorization checks
- Monotonic semantics
- Restricted expressiveness for performance and predictability

## 2. Can Zanzibar Be Modeled in Datalog?

Yes.

Two approaches:

### A. Compile schema to Datalog rules
- Tuples → facts
- Namespace config → generated rules
- Engine computes permissions

### B. Embed a Zanzibar DSL inside Datalog
- Schema stored as data
- Fixed templates interpret schema
- Enforces restrictions by construction

However, Zanzibar is intentionally **less expressive** than general Datalog to ensure:
- Decidable queries
- Efficient evaluation
- Predictable cost

## 3. Concern: Cycles and Depth

Pure Datalog terminates on finite domains but cycles can:

- Produce surprising semantics
- Cause large intermediate closures

Practical strategies:

1. **Schema validation**
   - Reject pathological cycles.

2. **Bounded-depth evaluation**
   - Track recursion depth.
   - Stop at max depth.
   - Accept truncated semantics.

3. **Materialized closure tables**
   - Precompute hierarchy membership.
   - Keep RLS simple and fast.

In real authorization systems, recursion is often limited or flattened.

## 4. Goal: Higher-Level Governance → PostgreSQL RLS

You want:

- A high-level governance language easier to reason about than raw SQL.
- Compile policies into PostgreSQL Row Level Security.
- Runtime enforcement done by PostgreSQL.
- No distributed-system concerns.
- Policies that are:
  - Graph-shaped but shallow
  - Monotonic
  - Expressible as joins/EXISTS with indexes

This is feasible.

Datalog is useful as an **authoring intermediate representation**, even if PostgreSQL is the runtime engine.

## 5. PostgreSQL RLS Constraints

RLS policies are boolean predicates attached to tables.

They must compile to:

- SQL expressions referencing the current row
- EXISTS / JOIN logic
- Possibly stable functions

Therefore, the policy language must compile into SQL of this shape.

Recursive logic is dangerous or expensive inside RLS.

## 6. Recommended Architecture

Use Datalog‑like DSL as an **IR**, not runtime engine.

Pipeline:

Policy DSL → Validation → SQL compilation → PostgreSQL RLS enforcement

Key rule: restrict DSL so compilation is predictable.

## 7. Minimal Policy DSL

### Global config

Defines:

- Current subject SQL expression
- Edge tables (grants, memberships, etc.)

Example edge tables:

- `authz_grants(subject_id, object_type, object_id, permission)`
- `authz_group_membership(subject_id, group_id)`
- `authz_group_grants(group_id, object_type, object_id, permission)`

### Resource policies

Each governed table declares actions.

Example:

- select
- insert
- update
- delete

Each action is an OR of clauses.

## 8. Allowed Clause Templates

Only allow three clause types:

### 1. Direct grant
User has permission directly on object.

SQL pattern:
EXISTS over grants table.

### 2. Group grant
User ∈ group AND group has permission.

SQL pattern:
JOIN membership + group_grants.

### 3. Container grant
Permission on container object (e.g., project).

SQL pattern:
EXISTS over grants with FK.

No recursion. No negation. No intersection.

This is equivalent to **non-recursive Datalog (unions of conjunctive queries).**

## 9. Compilation to SQL

Each clause compiles to a deterministic EXISTS query.

Policies become ORs of clauses.

Example SELECT policy for `documents`:

- direct document.read
- group-based document.read
- project.read via documents.project_id

Generated SQL uses only equality predicates.

## 10. Indexing Strategy

Indexes must match predicates:

- grants(subject_id, object_type, permission, object_id)
- group_membership(subject_id, group_id)
- group_grants(group_id, object_type, permission, object_id)

This keeps RLS fast.

## 11. Validation Rules

Compiler should enforce:

- Only allowed clause templates
- Max join depth ≤ 2
- Equality-only predicates
- Constant object types
- No negation
- No cross-row dependencies
- No policy recursion loops

These constraints preserve:

- Predictability
- Monotonicity
- Performance
- Reasonability

## 12. Where to Handle Cycles

Do NOT handle cycles inside RLS.

Instead:

- Enforce hierarchy constraints on writes
- Materialize closure tables if needed
- Limit nesting depth operationally

RLS should remain simple EXISTS checks.

## 13. Do You Need a Datalog Engine?

Not at runtime.

PostgreSQL evaluates compiled SQL.

You may want a development-time evaluator to:

- Test policies
- Explain access
- Detect redundant clauses
- Validate equivalence

But it can be simple.

## 14. When This Approach Works Best

Good fit if policies are:

- Tenant/project membership
- Direct grants
- Group membership (1 hop)
- Container inheritance
- Monotone

Poor fit if policies need:

- Deep recursion
- Complex negation
- Heavy attribute logic
- Temporal rules

Those require materialization or different systems.

## 15. Core Insight

Zanzibar‑style thinking gives a disciplined policy language.

Datalog gives semantic clarity.

PostgreSQL RLS gives enforcement.

By restricting expressiveness, you gain:

- Precision
- Predictability
- Auditability
- Performance
- Reasonability

This aligns with your stated principle: success comes from restraint.

---

End of summary.

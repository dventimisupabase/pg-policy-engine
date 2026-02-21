# Zanzibar-Inspired Authorization for PostgreSQL (Design Notes)

## 1. Context

We discussed how ideas from the Google Zanzibar paper apply to a
single-node PostgreSQL system, especially for a managed platform like
Supabase where customers have different threat models:

-   Trusted application server
-   Semi-trusted SQL clients
-   Fully untrusted direct SQL clients

The system must therefore be: - Correct under arbitrary SQL - Flexible
enough for many models - Small in its conceptual core - Predictable in
performance

The Zanzibar paper mixes several layers: 1. Authorization data model 2.
Evaluation semantics 3. Distributed implementation details

For PostgreSQL, only (1) and (2) are essential.

------------------------------------------------------------------------

## 2. Zanzibar Concepts That Transfer Well

### Treat authorization as data

Instead of embedding logic in application code, store authorization
relationships explicitly as relational tuples.

Tuple model:

(object_type, object_id, relation, subject_type, subject_id,
subject_relation?)

This maps naturally to PostgreSQL tables.

### Set-theoretic semantics

Permissions are defined by set operations: - union - intersection -
difference

PostgreSQL already supports this algebra.

### Separation of policy from enforcement

Policy definitions live as data. Enforcement happens through RLS, views,
or functions.

This separation makes systems analyzable and testable.

### Relationship-based access control (ReBAC)

Access often depends on graph relationships:

user → team → project → document

PostgreSQL can support this via recursive queries.

------------------------------------------------------------------------

## 3. Zanzibar Concepts That Do NOT Transfer

These are solutions to global-scale distributed problems:

-   distributed caching
-   watch APIs
-   consistency tokens
-   multi-region snapshot semantics

PostgreSQL already provides strong consistency on a single node.

Trying to replicate Zanzibar's distributed machinery inside Postgres is
unnecessary.

------------------------------------------------------------------------

## 4. PostgreSQL Enforcement Primitives

### Core primitives

1.  GRANT/REVOKE Controls which objects a role can access.

2.  Row-Level Security (RLS) Enforces per-row authorization
    automatically. Must use FORCE RLS for strong guarantees.

3.  Functions Used for policy evaluation. SECURITY DEFINER only with
    strict guardrails.

4.  Views Used to present safe surfaces. Combine with privilege
    restrictions.

5.  Triggers Enforce invariants on writes.

6.  Column-level privileges Useful for attribute-level governance.

7.  Schema and ownership design Prevent bypass via search_path or object
    access.

### Important principle

For correctness under untrusted SQL: **RLS on base tables must be the
final enforcement boundary.**

Everything else is optional.

------------------------------------------------------------------------

## 5. Minimal Authorization Core

### Core data model

Relationship tuples:

(object_type, object_id, relation, subject_type, subject_id,
subject_relation)

### Permission definitions

Small declarative DSL that composes relations into permissions.

Example:

document.read = owner OR editor OR viewer OR from(parent, read)

### Hard bounds

To guarantee performance:

-   Maximum graph depth D
-   Maximum fanout per hop
-   Maximum edges scanned
-   Maximum expression size

Fail closed if limits exceeded.

------------------------------------------------------------------------

## 6. Small Policy DSL

A deliberately limited language:

Allowed constructs: - relation reference - permission reference - union
(OR) - optional intersection/difference - one-hop traversal: from(rel,
perm) - userset edges via subject_relation

Example:

type document { relation owner; relation editor; relation viewer;
relation parent;

permission read = owner OR editor OR viewer OR from(parent, read); }

Constraints: - Traversals must be declared. - Depth is bounded. -
Expression size is limited.

------------------------------------------------------------------------

## 7. Static Validation

Validator checks:

1.  Name resolution
2.  Traverse targets valid
3.  Cycle detection
4.  Depth bounds
5.  Expression size limits

Example limits:

-   Max depth: 3
-   Max clauses: 16
-   Max AST nodes: 64
-   Max from nesting: 2

Reject policies that exceed limits.

------------------------------------------------------------------------

## 8. Deterministic Cost Model

We defined two cost types:

### Structural cost

Based on AST size.

Example formula:

S = nodes + 2 \* from_count + perms_count + 2 \* and_count + 3 \*
not_count

Reject if S \> threshold.

### Expansion cost

Based on platform limits:

-   D: depth
-   F_from: max related objects
-   F_direct: max direct edges
-   F_userset: max userset edges
-   B_edges: total edge budget

Example recurrence:

U(rel) = F_direct + F_userset

U(from(rel, expr), d) = F_from \* U(expr, d-1)

Reject policies whose worst-case bound exceeds budget.

This gives predictable performance guarantees.

------------------------------------------------------------------------

## 9. Compilation Strategy

Compile permissions into bounded SQL templates.

Two patterns:

### Pattern A: Per-row check

RLS calls:

authz.check(actor, type, id, perm)

Implemented with bounded recursive CTE.

### Pattern B: Precomputed projection

Table:

(actor, object, permission)

RLS becomes indexed lookup.

Better for read-heavy workloads.

Offer as optional performance tier.

------------------------------------------------------------------------

## 10. Guardrails for a Managed Platform

To keep system safe and predictable:

### Policy guardrails

-   Depth limits
-   Clause limits
-   Traversal whitelist
-   Cycle rules
-   Cost validation

### Schema guardrails

-   Required indexes on tuple table
-   Canonical schema template

### Runtime guardrails

-   Statement timeouts
-   Edge scan budgets
-   Query metrics

### Security guardrails

-   RLS on base tables
-   Minimal privileges
-   Trusted identity plumbing
-   Harden SECURITY DEFINER functions

------------------------------------------------------------------------

## 11. Identity Plumbing

Authorization depends on trustworthy identity.

In Supabase-like environments:

-   Database role = application role
-   End-user identity from trusted JWT claim
-   RLS predicates read only trusted session context

Never trust user-set variables.

------------------------------------------------------------------------

## 12. Why This Works

The design is small because:

-   One tuple model
-   Tiny DSL
-   Single evaluation strategy
-   RLS-first enforcement
-   Deterministic guardrails

It is flexible because:

-   Graph relationships stored in data
-   Hierarchies supported
-   Customers model teams, orgs, projects, etc.

------------------------------------------------------------------------

## 13. Practical Next Steps

1.  Formal grammar or JSON schema for policies.
2.  Static validator + cost estimator.
3.  SQL compiler for authz.check().
4.  Explain tool for debugging policy performance.
5.  Platform defaults enforcing safe patterns.

------------------------------------------------------------------------

## 14. Key Insight

Zanzibar's core idea is:

Authorization = graph query + set algebra.

This idea fits PostgreSQL extremely well.

Distributed scaling concerns do not.

For a managed platform, the most important design goal is not
expressiveness, but predictability and correctness across threat models.

A small, analyzable DSL with strong RLS enforcement provides that.

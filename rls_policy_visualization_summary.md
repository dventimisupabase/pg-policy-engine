# Conversation Summary: Visualizing PostgreSQL RLS and Designing a Higher-Level Authorization System

## 1. Initial Concern: Limits of an RLS Visualization Tool

The discussion began with skepticism about a UI tool intended to
visualize PostgreSQL Row Level Security (RLS) policies. The concern was
that such tools often give an illusion of correctness while
oversimplifying real semantics.

### Key technical reasons for skepticism

**RLS semantics depend on runtime context** - Policies depend on role,
command type, session settings, `row_security`, `SET ROLE`, JWT claims,
GUC variables, etc. - A visualization that models only "role → table →
condition" is incomplete.

**Policy composition is subtle** - Multiple policies combine with OR
semantics within a command. - Permissive vs restrictive policies combine
differently. - `USING` vs `WITH CHECK` behave asymmetrically. - Many
engineers misunderstand these rules even reading SQL.

**Policies are not the whole authorization story** Effective access also
depends on: - Table and column privileges - Views (especially
security-barrier views) - Functions inside policies - Triggers - Session
state

**Policies are data-dependent** Even perfect expression visualization
cannot show which rows are reachable without evaluating real data.

**Risk of epistemic overconfidence** A polished UI may be mistaken for
an authoritative security model.

### Legitimate uses of visualization tools

-   Documentation and onboarding
-   Diffing policy changes
-   Detecting obvious footguns
-   Showing topology of rules

But not determining actual reachability.

------------------------------------------------------------------------

## 2. Fundamental Issue: Arbitrary Computation in Policies

The user pointed out that RLS predicates can call arbitrary SQL
functions. This is the core limitation.

### Why this matters

RLS policies are embedded in a Turing-complete environment: - Functions
can run arbitrary SQL - Functions can read session state - SECURITY
DEFINER functions can bypass RLS - VOLATILE/STABLE/IMMUTABLE
declarations are not reliable

Therefore, a fully general static analyzer for RLS is impossible in
principle.

This reduces to undecidable problems like program reachability and the
halting problem.

### Realistic categories of tools

1.  **Syntactic viewers**
    -   Show policies as written
    -   No semantic claims
2.  **Restricted-subset analyzers**
    -   Only simple expressions
    -   No function calls or subqueries
    -   Treat complex cases as opaque
3.  **Dynamic explorers**
    -   Run queries as different roles on real data
    -   Testing, not static reasoning

Any tool claiming full semantic understanding is misleading.

------------------------------------------------------------------------

## 3. Proposed Alternative: Authorization DSL Compiled to RLS

The user proposed a better approach:

Create a higher-level authorization language that: - Models objects,
roles, permissions - Restricts expressiveness to decidable rules -
Compiles to a safe subset of RLS - Enables visualization, proofs, and
reasoning

Unmanaged policies would be marked as unparsable.

This is analogous to systems like Zanzibar, Cedar, or OPA with
constraints.

### Core idea

RLS becomes a backend target, not the authoring language.

------------------------------------------------------------------------

## 4. Design Goals for Such a System

**1. Small decidable core** The DSL must have formal semantics that can
be reasoned about without executing arbitrary SQL.

**2. Safe compilation** Generated RLS should avoid: - Arbitrary function
calls - Dynamic SQL - Volatile behavior

**3. Closed-world boundary** Policies must be tool-owned. Handwritten
policies should be rejected or marked unmanaged.

**4. Verification capability** The system should answer: - Why does
principal P have access? - Can anyone cross tenant boundaries? - What
changed between policy versions?

------------------------------------------------------------------------

## 5. What "Decidable Authorization" Looks Like

### A. Attribute-Based Access with Bounded Joins

Rules based on: - Row attributes - Principal attributes - Membership
tables

No recursion or aggregation.

### B. Relationship-Based Models (Zanzibar-like)

Permissions as relation tuples with bounded expansion.

### C. RBAC with Scopes

Roles combined with tenant/org/project scope.

Most real systems are hybrids.

------------------------------------------------------------------------

## 6. Example DSL → RLS Compilation

Human DSL:

    resource projects(scope org_id) {
      action select allow if principal.is_member(org_id);
      action update allow if principal.has_org_role(org_id, "admin")
                           or row.owner_id = principal.user_id;
    }

Compiled RLS:

    USING EXISTS (
      SELECT 1 FROM org_member m
      WHERE m.user_id = auth.uid()
        AND m.org_id = projects.org_id
    )

The compiler enforces canonical templates.

### Important compilation rules

-   Normalize predicates for diffability.
-   Separate read (`USING`) vs write (`WITH CHECK`) logic explicitly.

------------------------------------------------------------------------

## 7. Reasoning and Verification

Possible automated checks:

-   Rule satisfiability
-   Rule subsumption
-   Tenant isolation invariants
-   Reachability analysis
-   Impact analysis of policy changes

Because the DSL is constrained, reasoning occurs on the AST, not SQL.

------------------------------------------------------------------------

## 8. Handling Non-DSL Policies

Strategies:

-   Dedicated schemas
-   CI validation of `pg_policies`
-   Metadata tagging
-   Quarantine unmanaged tables

Otherwise the system loses guarantees.

------------------------------------------------------------------------

## 9. UI Implications

A GUI becomes practical and honest:

-   Builder for common patterns
-   Prevent invalid rules
-   Show derived SQL
-   Explain decisions

Visualization now reflects DSL semantics rather than arbitrary SQL.

------------------------------------------------------------------------

## 10. Risks to Watch

-   Performance of generated EXISTS joins
-   Trust boundaries for JWT/session claims
-   Function creep reintroducing undecidability
-   Policy drift outside the system

------------------------------------------------------------------------

## 11. Key Conceptual Takeaway

A visualization tool for arbitrary RLS cannot be authoritative because
RLS allows arbitrary computation.

But a constrained authorization DSL that compiles to RLS can be
analyzable, provable, and visualizable.

In short:

**Do not try to understand arbitrary RLS.\
Define an analyzable authorization language and treat RLS as a
backend.**

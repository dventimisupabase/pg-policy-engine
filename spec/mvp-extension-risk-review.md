# MVP Extension Risk Review — Avoiding Architectural Lock-In

> **Status**: Draft review memo
> **Date**: 2026-02-23
> **Audience**: Product + Architecture

---

## 1. Purpose

This memo re-evaluates the current MVP documents against the long-term goal of becoming a TrustLogix-like governance control plane. The intent is **not** to expand MVP scope, but to identify where the current direction could make later expansion materially harder.

Primary motivator reference: `trust_logix_style_system_mvp_discussion (1).md`.

---

## 2. Compatibility Snapshot

The current plan is strong on the control-kernel loop (policy definition → compile → apply → monitor → reconcile) and aligns with the TrustLogix MVP notion of native enforcement plus continuous metadata reconciliation.

However, several assumptions are PostgreSQL-specific in ways that risk coupling core architecture to one backend and one policy style.

---

## 3. Risk Register (Corners We Might Paint Ourselves Into)

### R1. No Intermediate Representation (IR) Between Algebra and SQL Compiler

**Current pattern**
- Pipeline runs directly from normalized AST to PostgreSQL SQL compilation.
- Data structures such as `CompiledState` are described in terms of PostgreSQL artifacts and names.

**Why this is a risk**
A TrustLogix-like system eventually needs multiple backends (Snowflake, SQL Server, etc.). A direct AST→Postgres path creates hidden coupling in naming, expression semantics, and drift assumptions. Porting later becomes rewrite-heavy instead of adapter-heavy.

**Guardrail (MVP-safe)**
- Introduce a backend-neutral `PolicyIR` layer now (even if only one backend implementation exists).
- Keep Postgres compiler as `PolicyIR -> PostgresArtifactSet`.
- Store expected state in IR + backend projection, not backend projection alone.

---

### R2. Drift Detection Model Is Too SQL-String-Centric

**Current pattern**
- Drift detection and ownership lean on SQL text comparison and policy name matching.

**Why this is a risk**
This is workable for PostgreSQL MVP, but larger control planes need richer provenance and stable structural comparison independent of target deparser quirks. Name-only ownership will struggle with heterogeneous naming constraints and imported policies.

**Guardrail (MVP-safe)**
- Keep string comparison for MVP execution.
- Persist a normalized structural fingerprint in expected state (e.g., hash over normalized AST/IR clause form).
- Add explicit `management_scope` metadata in control-plane state for future managed/unmanaged reasoning.

---

### R3. Identity Is Assumed as Session Variables, Not Modeled as a First-Class Module

**Current pattern**
- Session variables are embedded as policy value sources (`session('...')`).
- No explicit identity normalization/mapping component in architecture.

**Why this is a risk**
TrustLogix-style systems rely on identity mapping (IdP users/groups/attributes → data-plane principals/context). If identity remains ad hoc string keys inside policies, migration to multi-IdP and group semantics becomes brittle.

**Guardrail (MVP-safe)**
- Add an `IdentityContextProvider` contract now (initial impl: static map + session-setting conventions).
- Treat session key names as resolved outputs of identity mapping, not author-chosen free strings.
- Reserve config namespace for identity source/mapping strategy.

---

### R4. Artifact Surface Is Narrow (RLS + GRANT) With Limited Abstraction

**Current pattern**
- MVP artifact set emphasizes `CREATE POLICY`, `ALTER TABLE ... RLS`, and GRANTs.

**Why this is a risk**
The motivating architecture anticipates object access + ABAC patterns that in many systems require views/masking/functions or backend equivalents. If compiler contracts assume only RLS policy objects, extension can become invasive.

**Guardrail (MVP-safe)**
- Extend internal artifact model to an algebraic union now:
  - `RowPolicyArtifact`
  - `PrivilegeArtifact`
  - `ProjectionArtifact` (view/masking placeholder)
- Implement only first two in MVP; keep third as no-op for PostgreSQL MVP.

---

### R5. Selector/Metadata Model Is Table-Centric, Not Governance-Metadata-Centric

**Current pattern**
- Selectors rely on schema/table/column predicates.
- `tagged()` is deferred.

**Why this is a risk**
TrustLogix-like scaling depends heavily on tags/attributes/domains as the selection substrate. If selector APIs are shaped around direct table metadata only, introducing external metadata providers later creates major refactors.

**Guardrail (MVP-safe)**
- Define `ResourceCatalog` interface now with current Postgres introspection as one provider.
- Keep selector evaluation over `ResourceAttributes` instead of raw catalog rows.
- Add explicit extension point for tag/domain providers.

---

### R6. CLI-First Integration Contract Could Become a Bottleneck

**Current pattern**
- Product and implementation plan center on CLI commands and file-based policy input.

**Why this is a risk**
A mature control plane needs API/service orchestration, event-driven reconciliation, and possibly multi-target workflows. If module boundaries are shaped around command execution paths, service extraction later is harder.

**Guardrail (MVP-safe)**
- Enforce use-case/service layer now (`AnalyzeUseCase`, `CompileUseCase`, etc.) with CLI as thin adapter.
- Keep all outputs serializable and stable (JSON schemas as contracts).

---

### R7. Reconciliation Semantics Are Geared Toward Single-Database Immediate Apply

**Current pattern**
- Reconcile is defined largely as SQL remediation generation/execution against one target.

**Why this is a risk**
TrustLogix-style operation eventually needs staged approvals, policy rollout windows, and partial-failure handling across environments. If reconcile is only an immediate imperative step, later governance controls are bolt-ons.

**Guardrail (MVP-safe)**
- Add `ReconcilePlan` object separate from `ReconcileExecution`.
- Include operation idempotency keys and dry-run/audit trail identifiers now.

---

### R8. Config Model Is Under-Specified for Future Control-Plane Inputs

**Current pattern**
- `pgpe.yaml` is planned but not defined for identity providers, metadata providers, target profiles, or feature flags.

**Why this is a risk**
Future integrations often fail due to config shape lock-in. Early schema decisions around one target and one credential style can be painful to unwind.

**Guardrail (MVP-safe)**
- Define versioned config schema now (`version: 1`).
- Include namespaced sections even if mostly unused in MVP:
  - `targets[]`
  - `identity`
  - `metadata`
  - `reconcile`
  - `features`

---

## 4. Recommended Minimal Edits to Existing Docs

### PRD
1. Add a non-functional requirement: **forward-compatible architecture** with explicit “single backend now, multi-backend-ready boundaries.”
2. Add acceptance criterion for compile output persistence: expected state must include structural fingerprint + backend projection.
3. Add scope note: identity is externalized for authn, but identity-to-principal mapping is in-scope for architecture contracts (even if minimally implemented).

### Design Document
1. Insert `PolicyIR` between Normalizer and Compiler.
2. Add interfaces:
   - `IdentityContextProvider`
   - `ResourceCatalog`
   - `ArtifactBackend` (Postgres implementation in MVP)
3. Model compilation output as polymorphic artifacts, not only SQL policy statements.
4. Refactor CLI to use case services to preserve service extraction path.

### Implementation Plan
1. M1/M2: no scope change.
2. M3/M4: add lightweight IR and structural fingerprint generation.
3. M5: drift detector consumes fingerprint where available, falls back to SQL normalization.
4. M6: expose stable JSON schemas as contract outputs.
5. Add a cross-cutting checkpoint: “no module imports backend-specific types above backend adapter boundary.”

---

## 5. What Not to Change (To Protect MVP Velocity)

- Do **not** add second backend in MVP.
- Do **not** implement full tag/domain UI or governance workflow engine.
- Do **not** replace SQL-based drift logic before baseline functionality exists.

The goal is simply to set seams now so these future capabilities can attach without architectural surgery.

---

## 6. Suggested Phase-1+ Expansion Path

1. Introduce external metadata provider for tags/domains behind `ResourceCatalog`.
2. Add identity connectors (Okta/Auth0/Entra) behind `IdentityContextProvider`.
3. Add second backend adapter to validate `PolicyIR` portability.
4. Add policy lifecycle governance features (approval gates, staged rollout).
5. Add richer assurance/monitoring overlays.

---

## 7. Bottom Line

The current MVP plan is directionally correct for a PostgreSQL control kernel. The main risk is **interface shape**, not missing features. If we establish backend-neutral IR, first-class identity/catalog contracts, and reconciliation planning seams now, we preserve MVP speed while avoiding structural lock-in against the broader TrustLogix-style trajectory.

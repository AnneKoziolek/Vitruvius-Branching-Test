# Formalization Review: Gaps Between formalization.md and the Prototype

This document compares formalization.md (the formal specification of directed merge in Vitruv) against the actual prototype implementation in the `branch/merge/` package.

---

## Overall Assessment

The formalization captures the **high-level architecture** well: directed merge, user vs derived distinction, per-transaction replay, three conflict types, and the core insight that only user changes are recorded while derived changes are recomputed. However, there are several places where the formalization diverges from the implementation or leaves out critical details.

---

## 1. The App(m', empty) Problem (Section 8.3 / Section 9)

**This is the most significant issue.**

The formalization writes the merge loop as:

```
m' := tau(m)          -- apply user change WITHOUT CPRs
m'' := App(m', empty) -- then run CPRs with no input change
```

But this does not match what happens in the implementation, nor does it make sense within Klare's own framework.

### Why it's wrong

In Klare's formalization, CPRs are **change-triggered**: they react to a specific delta, not to a state. `App(m', empty)` applies CPRs to an empty change, which would produce **no derived changes**. This makes the subsequent indirect conflict check vacuous -- there would never be any derived changes to conflict with.

### What actually happens (implementation)

The implementation does it in **one step**:

```
m'' := App(m, tau_A^u)
```

`replayChanges()` applies the user change via a `ChangeRecordingView`. When `commitChanges()` is called, it invokes `propagateChange()` with the user change as input. Reactions fire **in response to that specific change**, producing derived changes. Both user and derived changes are applied together.

### Correct formalization

```
(m'', delta_d) := App(m, tau_i^u)    -- apply user change, CPRs fire, producing derived delta_d
if fp(delta_d) conflicts with fp(H_B^u):
    IndirectConflict
m := m''
```

The key insight: `App` takes the **current state m** and the **user change tau**, and returns the new state plus the derived changes. The indirect conflict check examines those derived changes against B's user history.

---

## 2. Direct Conflict Detection: Per-Transaction vs Batch (Section 9)

### Formalization says

Direct conflicts are checked **per-transaction inside the loop**:

```
for tau in H_A^u:
    if DirectConflict(tau, H_B^u):
        resolve conflict
    ...
```

### Implementation does

All direct conflicts are detected **upfront, before the replay loop**, by `UuidConflictDetector.detectConflicts()` which compares ALL of A's DTOs against ALL of B's DTOs in one pass. If unresolved conflicts exist, the merge aborts before any replay occurs.

### Does it matter?

Functionally, the two approaches are equivalent because `DirectConflict(tau, H_B^u)` checks against B's **complete user history**, which doesn't change during replay. So checking everything upfront gives the same result as checking per-transaction. However, the formalization should note this equivalence or match the implementation, since "batch upfront" allows presenting all conflicts to the user at once (better UX).

---

## 3. Missing: UUID-Based Element Identity (Section 8.1)

### Formalization says

```
fp(delta) = set of affected elements/features
```

This is abstract -- it doesn't say how elements are identified across branches.

### Implementation does

Footprints use **UUID#featureName** as the identity key. UUIDs are:
- Random (`EcoreUtil.generateUUID()`)
- Persisted in `vsum/uuid.uuid` (Git-tracked)
- **Stable across branches** for elements from the common ancestor
- **Unique per branch** for elements created independently after divergence

This UUID stability is what makes cross-branch conflict detection possible. Two branches adding different components never conflict because the new elements have different UUIDs.

### Why this matters for the formalization

The formalization should define an **identity function** `id: Element -> UUID` and specify that:
- `id` is stable across branches for shared-ancestor elements
- `id` is unique for independently-created elements
- `fp(delta)` = `{ (id(e), f) | e.f is affected by delta }`

Without this, the formalization doesn't explain how cross-branch element matching works, which is the foundation of the entire conflict detection scheme.

---

## 4. Missing: Conflict Resolution Mechanism (Section 9)

### Formalization says

```
if DirectConflict(tau, H_B^u):
    resolve conflict
```

No detail on *how* resolution works.

### Implementation does

Conflict resolution has a concrete mechanism:
1. `ConflictResolutionProvider` returns OURS or THEIRS per conflict
2. **OURS**: The conflicting DTO from A is filtered out (not replayed)
3. **THEIRS**: The DTO from A is kept and replayed (overwrites B's value)

The formalization could capture this as:

```
resolve(conflict) -> Choice in {OURS, THEIRS}
if Choice = OURS:   skip tau_i^u (don't replay this change)
if Choice = THEIRS: keep tau_i^u (replay it, overwriting B's value)
```

---

## 5. User-vs-Derived Warnings (Section 8.4)

### Formalization says

```
user(A) overwrites derived(B)  -->  allowed, warning only
```

### Implementation matches

`detectUserVsDerivedWarnings()` checks if a theirs (A) DTO's footprint exists in ours (B) but is NOT in B's user footprints (meaning it was derived by reactions). If so, a non-blocking `USER_VS_DERIVED_WARNING` is recorded. Merge proceeds.

### Gap

The formalization mentions this only briefly. It should clarify that this check happens **per-transaction** during replay (matching the implementation), and that the "allowed" semantics mean: source user intent always wins over target derived state.

---

## 6. Transaction = Git Commit (Section 5)

### Formalization says

A history is a sequence of transactions:

```
H^u = <tau_1^u, ..., tau_n^u>
```

### Implementation maps this to

One Git commit = one transaction = one `.changelog.json` file. The `SemanticChangeLog` class persists each commit's captured EChanges as a JSON DTO file under `.vitruvius/semantic-changelogs/<sha>.changelog.json`.

### What's missing from the formalization

The formalization doesn't say what a "transaction" corresponds to concretely. It should note that each transaction maps to a Git commit, and that changelogs are the serialized form of user histories.

---

## 7. The ChangeRecordingTrait Requirement (implicit in Section 4)

### Formalization says

Only delta^u is recorded; delta^d is recomputed.

### Critical implementation detail not captured

The implementation requires `withChangeRecordingTrait()` (not `withChangeDerivingTrait()`) for rename/delete operations. Why?

- **ChangeRecordingTrait** captures fine-grained EMF notifications (e.g., a single `ReplaceSingleValuedEAttribute`). It **preserves the element's existing UUID**.
- **ChangeDerivingTrait** uses state-based comparison (EMFCompare), which produces CREATE+INSERT+REPLACE sequences and assigns **new UUIDs** to "modified" elements.

If the deriving trait is used for renames, each branch gets a **different UUID** for the same element, making UUID-based conflict detection impossible.

### Why the formalization should capture this

The formalization's entire conflict detection scheme rests on the assumption that elements modified on different branches retain the same identity. This requires a specific change capture strategy. The formalization should at least note that the recording mechanism must preserve element identity, or conflict detection breaks down.

---

## 8. Replay Mechanism: Reflective Apply + ChangeRecordingView (Section 9)

### Formalization says

```
m' := tau(m)       -- abstract application
m'' := App(m', ∅)  -- abstract CPR execution
```

### Implementation does something specific

The replay uses the **Copy ResourceSet pattern** (same as `IdentityMappingViewType.commitViewChanges()`):

1. Create a `ChangeRecordingView` on all model objects in the target VSUM
2. Apply each `EChange<HierarchicalId>` via **EMF reflective API** (`eSet`, `eGet`, `list.add`)
3. Reflective calls trigger EMF notifications, captured by the ChangeRecorder
4. `view.commitChanges()` → `propagateChange(uuidChange)` → reactions fire
5. `DerivedChangeCapture` (a separate listener) records consequential changes from reactions

This is not just an implementation detail -- it explains WHY reactions fire (step 4) and HOW derived changes are captured separately (step 5). The formalization's two-step `tau(m)` then `App(m', ∅)` doesn't model this correctly.

---

## 9. Missing: Base State Usage

### Formalization defines

```
merge_{A->B}(m_base, H_A^u, H_B^u)
```

m_base appears in the signature but the algorithm only uses:

```
m := m_B
for tau in H_A^u: ...
```

### In the implementation

The base state (found via `GitStateLoader.findMergeBase()`) is used to:
1. Extract base model state into a temp directory
2. Determine which changelogs belong to each branch (commits between base..head)
3. Provide context for the merge, but **replay starts from m_B** (ours)

### Gap

The formalization is correct that replay starts from m_B and applies H_A^u. But m_base is implicit in the definition of H_A^u and H_B^u (they are relative to the base). The formalization could be clearer: "H_A^u and H_B^u are the user histories from m_base to m_A and m_B respectively."

---

## 10. What the Formalization Gets Right

These aspects align well with the implementation:

| Formalization Concept | Implementation Match |
|---|---|
| Directed merge (A->B not symmetric) | `mergeCmd.execute(dir, "feature", "main", ...)` -- source and target are distinct |
| Start from m_B | VSUM loaded from ours (target branch) state |
| Replay H_A^u per-transaction | Per-transaction loop over theirs' changelog files |
| Only user changes recorded | ChangeLogCapture captures primary changes; reactions regenerated during replay |
| delta^d recomputed via CPRs | Reactions fire during replay via `propagateChange()` |
| Direct conflict = user(A) vs user(B) | UuidConflictDetector: same UUID+feature, different values |
| Indirect conflict = derived(A) vs user(B) | DerivedChangeCapture + footprint matching after replay |
| User(A) vs derived(B) = warning only | `detectUserVsDerivedWarnings()` produces non-blocking warnings |
| Consistency of final result | Reactions fire during every replay step, maintaining CR invariant |

---

## 11. Anne's Comments (from annes-thoughts-on-formalization.md)

Anne raised three concerns:

### (a) "We should stay closer to the implementation"
**Verdict**: Agreed. The formalization's `App(m', ∅)` pattern doesn't match the implementation's single-step `App(m, tau)`. The two-step decomposition is misleading.

### (b) "m_base should be taken into account"
**Verdict**: m_base IS in the merge signature, but the formalization should clarify its role more explicitly: it defines the boundary of H_A^u and H_B^u, and it's used to extract the changelogs that constitute those histories.

### (c) "Distinguish user-intended vs derived changes"
**Verdict**: The formalization DOES distinguish them (Section 4: delta^u vs delta^d). But the distinction needs more emphasis in the merge algorithm itself. Specifically: the algorithm should make clear that (1) only delta^u is replayed, (2) delta^d is regenerated by CPRs during replay, and (3) the three conflict types arise from the four possible (user/derived) x (A/B) combinations.

---

## Summary of Required Changes

| # | Issue | Severity | Section |
|---|-------|----------|---------|
| 1 | `App(m', ∅)` doesn't model change-triggered CPRs correctly | **High** | 8.3, 9 |
| 2 | Missing UUID-based element identity | **High** | 8.1 |
| 3 | Direct conflict detection is batch, not per-transaction | Medium | 9 |
| 4 | Missing conflict resolution mechanism (OURS/THEIRS) | Medium | 9 |
| 5 | Change recording trait requirement for UUID preservation | Medium | 4 |
| 6 | Transaction = Git commit not specified | Low | 5 |
| 7 | User-vs-derived warning semantics need more detail | Low | 8.4 |
| 8 | Replay mechanism (reflective + ChangeRecordingView) not captured | Low | 9 |
| 9 | Role of m_base needs clarification | Low | 7 |

### The two high-severity issues

**Issue 1** means the core of the merge algorithm (Section 9) doesn't correctly formalize how derived changes arise. The fix is straightforward: replace the two-step `tau(m)` / `App(m', ∅)` with `(m'', delta_d) := App(m, tau)`, which correctly models change-triggered CPR execution.

**Issue 2** means the conflict detection scheme lacks its theoretical foundation. UUID identity is what makes `fp(delta)` work across branches. Without formalizing it, the reader cannot understand why conflict detection is correct.

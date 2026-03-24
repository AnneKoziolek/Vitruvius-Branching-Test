# Formalization of Directed Merge in Vitruv (Revised)

## 1. Model Space and Consistency

Let

* ( M = {M_1, \dots, M_n} ) be a set of models,
* ( \mathcal{I}_{\langle M \rangle} ) be the set of model tuples.

A model tuple is:

```
⟨m⟩ = (m1, …, mn)
```

Let:

```
CR = {CR1, …, CRk}
```

be the set of consistency relations.

A tuple is **consistent** iff:

```
⟨m⟩ ⊨ CR
```

---

## 2. Changes and CPRs (Klare)

A change is:

```
δ : I⟨M⟩ → I⟨M⟩
```

A CPR is:

```
CprCR : I⟨M⟩ × Δ → I⟨M⟩ × Δ
```

It transforms a change such that applying it preserves consistency.

---

## 3. Application Function (Vitruv Execution)

We define an **extended application function**:

```
App* : I⟨M⟩ × Δᵘ → I⟨M⟩ × Δᵈ
```

where:

* Δᵘ = user-intended changes
* Δᵈ = derived changes produced by CPRs

Semantics:

```
App*(⟨m⟩, δᵘ) = (⟨m'⟩, δᵈ)
```

Meaning:

* replay δᵘ on ⟨m⟩
* execute CPRs
* obtain consistent ⟨m'⟩
* record derived changes δᵈ

---

## 4. Transactions and Provenance

A **user transaction**:

```
τᵘ ∈ Δᵘ
```

Derived changes are not stored in histories but computed via `App*`.

---

## 5. Histories

A history is a sequence:

```
Hᵘ = ⟨τ₁ᵘ, …, τₙᵘ⟩
```

Execution:

```
Exec(⟨m⟩, ε) = ⟨m⟩

Exec(⟨m⟩, τᵘ :: H) =
    Exec(⟨m'⟩, H)
    where (⟨m'⟩, δᵈ) = App*(⟨m⟩, τᵘ)
```

---

## 6. Branching

Let:

```
⟨m_base⟩
```

be the common ancestor.

Histories:

```
H_Aᵘ, H_Bᵘ
```

Branch heads:

```
⟨m_A⟩ = Exec(⟨m_base⟩, H_Aᵘ)
⟨m_B⟩ = Exec(⟨m_base⟩, H_Bᵘ)
```

---

## 7. Directed Merge

We define:

```
merge_{A→B}(⟨m_base⟩, H_Aᵘ, H_Bᵘ)
```

### Semantics

1. Initialize:

```
⟨m₀⟩ := ⟨m_B⟩
```

2. Replay transactions of A:

For each τᵢᵘ in H_Aᵘ:

```
(1) check direct conflict
(2) check user-vs-derived warning
(3) (⟨mᵢ₊₁⟩, δᵈᵢ) := App*(⟨mᵢ⟩, τᵢᵘ)
(4) check indirect conflict
```

---

## 8. Conflict Detection

### 8.1 Footprints

```
fp(δ) = set of affected elements/features
```

---

### 8.2 Direct Conflict (User vs User)

```
DirectConflict(τ_Aᵘ, H_Bᵘ)
```

iff:

```
∃ τ_Bᵘ ∈ H_Bᵘ :
    fp(τ_Aᵘ) conflicts with fp(τ_Bᵘ)
```

---

### 8.3 User(A) vs Derived(B) — Warning

If τᵢᵘ overwrites derived state in ⟨mᵢ⟩:

```
WarnUD(τᵢᵘ, ⟨mᵢ⟩)
```

→ merge continues

---

### 8.4 Indirect Conflict (Derived vs User)

Let:

```
(⟨mᵢ₊₁⟩, δᵈᵢ) = App*(⟨mᵢ⟩, τᵢᵘ)
```

Then:

```
IndirectConflict(τᵢᵘ, H_Bᵘ, ⟨mᵢ⟩)
```

iff:

```
∃ τ_Bᵘ ∈ H_Bᵘ :
    fp(δᵈᵢ) conflicts with fp(τ_Bᵘ)
```

---

## 9. Merge Algorithm

```
function merge(A → B):

    m := m_B

    for τ in H_Aᵘ:

        if DirectConflict(τ, H_Bᵘ):
            resolve conflict

        if overwrites derived state:
            warn

        (m', δ_d) := App*(m, τ)

        if IndirectConflict(δ_d, H_Bᵘ):
            resolve conflict

        m := m'

    return m
```

---

## 10. Bidirectional Merge

### Motivation

In a directed merge A→B, if derived(A) overwrites user(B) (IndirectConflict), B's user intent wins but the reaction that produced derived(A) is not fully applied—the resulting model may be inconsistent.

### Definition

```
merge_bi(⟨m_base⟩, H_Aᵘ, H_Bᵘ) =
    let result_fwd = merge_{A→B}(⟨m_base⟩, H_Aᵘ, H_Bᵘ)
    if no IndirectConflict in result_fwd:
        return result_fwd                           (direction = FORWARD)

    let result_rev = merge_{B→A}(⟨m_base⟩, H_Bᵘ, H_Aᵘ)
    if no IndirectConflict in result_rev:
        return result_rev                           (direction = REVERSED)

    return BidirectionalIndirectConflict             (true conflict)
```

### Rationale

In B→A, user(B)'s changes are replayed onto A's state. Reactions fire naturally for B's changes, producing consistent derived state. If no indirect conflicts arise, the merged model is consistent.

If both directions produce indirect conflicts, the reactions in both directions interfere with user intent on the other branch—a true semantic conflict requiring user resolution.

### Conflict Resolution Provider Inversion

Direct conflicts (user vs user) are symmetric. When attempting the reverse direction, the conflict resolution provider must invert OURS↔THEIRS since the roles are swapped.

---

## 11. Properties

### Consistency

Final state is consistent:

```
⟨m_M⟩ ⊨ CR
```

### Intent Preservation

User changes on B are never silently overwritten.

### Directedness (Directed Merge)

Directed merge is asymmetric (A → B).

### Symmetry (Bidirectional Merge)

Bidirectional merge considers both directions and selects the one that avoids indirect conflicts. The result indicates which direction was used (FORWARD or REVERSED). If neither direction is clean, a BIDIRECTIONAL_INDIRECT_CONFLICT is reported.


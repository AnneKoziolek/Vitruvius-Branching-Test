Yes — that makes the semantics cleaner and stronger.

Treating

[
\text{derived}(\text{replay}(A)) ;\text{vs}; \text{user}(B)
]

as a **conflict** is the safer and more principled choice, because otherwise the merge could silently invalidate explicit user intent on the target branch through propagation.

Below is an updated formalization that reflects your actual intended workflow:

* **history-based**, not diff-based
* **directed** merge of (A) into (B)
* **transaction replay** of user changes from (A)
* **reactions after each replayed user transaction**
* **conflicts** for both

  * direct user-vs-user contradictions
  * indirect derived-vs-user contradictions caused by replay and propagation
* **warnings** only for user(A)-vs-derived(B)

---

# 1. Global State and Consistency

Let

* ( \mathcal{M} = {M_1,\dots,M_n} ) be the participating models,
* ( \Sigma_i ) the state space of model (M_i),
* ( \Sigma = \prod_{i=1}^n \Sigma_i ) the global state space.

A global state is

[
S = (s_1,\dots,s_n)\in\Sigma.
]

Let ( \mathcal{R} ) be the set of consistency relations over the models.
A state (S) is **consistent** iff all relations hold:

[
\mathsf{Cons}(S).
]

We assume a deterministic consistency restoration operator

[
\mathsf{restore} : \Sigma \to \Sigma
]

such that:

1. (\mathsf{Cons}(\mathsf{restore}(S)))
2. if (\mathsf{Cons}(S)), then (\mathsf{restore}(S)=S)

This is the abstract counterpart of Vitruv’s reaction-based consistency restoration.

---

# 2. User Transactions and Replay

## 2.1 User Transactions

Instead of state differences, we use the recorded history of **user-authored transactions**.

A **user transaction** ( \tau ) is a finite ordered sequence of primary EChanges authored by the user and applied as one logical step.

Each ( \tau ) induces a partial state transformer

[
\tau : \Sigma \rightharpoonup \Sigma.
]

After a transaction is applied, consistency is re-established by reactions:

[
\mathsf{step}(S,\tau) = \mathsf{restore}(\tau(S)).
]

---

## 2.2 Histories

A **branch history** is a finite sequence of user transactions:

[
H = \langle \tau_1,\dots,\tau_k\rangle.
]

Execution of a history is defined inductively:

[
\mathsf{exec}(S,\langle\rangle)=S
]

and

[
\mathsf{exec}(S,\langle\tau_1,\dots,\tau_k\rangle)
==================================================

\mathsf{exec}(\mathsf{step}(S,\tau_1),\langle\tau_2,\dots,\tau_k\rangle).
]

Thus, reactions are executed after every replayed user transaction.

---

# 3. Branching

Let (S_0) be the common ancestor state, with (\mathsf{Cons}(S_0)).

Let

* (H_A = \langle \tau^A_1,\dots,\tau^A_m\rangle) be the recorded user history on branch (A),
* (H_B = \langle \tau^B_1,\dots,\tau^B_n\rangle) be the recorded user history on branch (B),

both since the common ancestor.

The branch heads are:

[
S_A = \mathsf{exec}(S_0,H_A)
\qquad\text{and}\qquad
S_B = \mathsf{exec}(S_0,H_B).
]

---

# 4. Directed Merge Semantics

We formalize merge as a **directed replay merge** of branch (A) onto branch (B).

The intuition is:

* start from (S_B),
* replay the user-authored transactions from (H_A) in order,
* after each replayed transaction, execute reactions,
* detect conflicts and warnings during this process.

We write:

[
\mathsf{merge}_{A\to B}(S_0,H_A,H_B).
]

This operator is intentionally **not symmetric**.

---

# 5. Change Provenance

To define conflicts precisely, we distinguish the provenance of changes.

For any state transition during replay, each applied EChange is tagged as either:

* **user**: directly originating from a replayed transaction from (A), or
* **derived**: produced by (\mathsf{restore}) during consistency restoration.

Likewise, branch (B)'s history (H_B) contains **user-authored** transactions, while the state (S_B) may also contain effects that are derived from them by earlier reaction executions.

This provenance distinction is essential.

---

# 6. Footprints

Let ( \mathsf{fp}(\tau) ) denote the **write footprint** of a user transaction ( \tau ): the set of semantic locations it writes.

A semantic location may be modeled, for example, as a tuple consisting of:

[
(\text{model}, \text{element}, \text{feature}, \text{operation kind})
]

possibly refined with index or identifier information.

Similarly, let:

* ( \mathsf{fp}_u(\tau) ) be the footprint of the replayed user changes in ( \tau ),
* ( \mathsf{fp}_d(S,S') ) be the footprint of derived changes introduced by (\mathsf{restore}) when transforming (S) into (S').

Two writes are **incompatible** if they target overlapping semantic locations with contradictory effects.

---

# 7. Conflict and Warning Kinds

## 7.1 Direct User-User Conflict

A replayed transaction ( \tau^A_i ) is in **direct conflict** with branch (B) iff its user-authored writes are incompatible with user-authored writes in (H_B) since the common ancestor.

Formally, using an abstract incompatibility predicate ( # ):

[
\mathsf{DirectConflict}(\tau^A_i,H_B)
]

iff there exists ( \tau^B_j \in H_B ) such that

[
\mathsf{fp}_u(\tau^A_i) ;#; \mathsf{fp}_u(\tau^B_j).
]

These are the usual “mine vs theirs” conflicts.

---

## 7.2 User-vs-Derived Warning

If the replayed user transaction from (A) overwrites state on (B) that is only derived, we raise a **warning** but do not block the merge.

Formally:

[
\mathsf{WarnUD}(\tau^A_i,S)
]

iff the user-authored writes of ( \tau^A_i ) are incompatible with derived state currently present in (S), but not with user-authored writes from (H_B).

This captures:

[
\text{user}(A) ;\text{vs}; \text{derived}(B).
]

Policy: replay proceeds, source user intent wins, warning is recorded.

---

## 7.3 Indirect Derived-vs-User Conflict

This is the updated rule.

Suppose replaying ( \tau^A_i ) on current state (S) yields an intermediate state

[
S' = \tau^A_i(S),
]

and consistency restoration then yields

[
S'' = \mathsf{restore}(S').
]

If the **derived** changes in the transition (S' \to S'') are incompatible with user-authored changes from (H_B), then we raise a **conflict**.

Formally:

[
\mathsf{IndirectConflict}(\tau^A_i,H_B,S)
]

iff there exists ( \tau^B_j \in H_B ) such that

[
\mathsf{fp}_d(S',S'') ;#; \mathsf{fp}_u(\tau^B_j).
]

This captures:

[
\text{derived}(\text{replay}(A)) ;\text{vs}; \text{user}(B).
]

Policy: merge pauses and requires explicit user resolution.

This is the crucial refinement.

---

# 8. Replay with Conflict Handling

We now define merge operationally.

## 8.1 Replay State

A replay configuration is a triple

[
(S, K, W)
]

where:

* (S) is the current global state,
* (K) is the set of unresolved conflicts,
* (W) is the set of warnings.

Initial configuration:

[
(S_B,\emptyset,\emptyset).
]

---

## 8.2 Replay Rule Without Conflict

For each transaction ( \tau^A_i ), if neither direct conflict nor indirect conflict occurs, replay proceeds:

1. apply ( \tau^A_i ),
2. record any user-vs-derived warnings,
3. execute ( \mathsf{restore} ),
4. continue with the next transaction.

Formally, if

[
\neg \mathsf{DirectConflict}(\tau^A_i,H_B)
\quad\text{and}\quad
\neg \mathsf{IndirectConflict}(\tau^A_i,H_B,S),
]

then

[
S' = \tau^A_i(S), \qquad S'' = \mathsf{restore}(S')
]

and replay continues from (S'').

Warnings from (\mathsf{WarnUD}) are added to (W).

---

## 8.3 Replay Rule With Conflict

If either

[
\mathsf{DirectConflict}(\tau^A_i,H_B)
]

or

[
\mathsf{IndirectConflict}(\tau^A_i,H_B,S),
]

then replay stops and records the conflict in (K).
The user must choose a resolution policy, such as:

* **mine**: prefer the change from (B),
* **theirs**: prefer the replayed effect from (A),
* or a custom manual resolution.

After resolution, replay may continue.

---

# 9. Merge Result

The directed merge of (A) into (B) succeeds iff replay terminates with no unresolved conflicts.

If replay of all transactions in (H_A) onto (S_B) completes and all conflicts are resolved, the final state is

[
S_M = \mathsf{merge}_{A\to B}(S_0,H_A,H_B).
]

By construction and by the properties of (\mathsf{restore}),

[
\mathsf{Cons}(S_M).
]

---

# 10. Correctness Properties

## 10.1 Consistency Preservation

If merge terminates successfully, then the result is consistent:

[
\mathsf{Cons}(\mathsf{merge}_{A\to B}(S_0,H_A,H_B)).
]

This follows because replay performs (\mathsf{restore}) after every transaction.

---

## 10.2 Preservation of Target Context

The merge is target-biased:

* it starts from (S_B),
* replays source-branch user intent from (A),
* and only changes (B) where replay or resulting consistency restoration requires it.

This is intentional and distinguishes the operator from a symmetric merge.

---

## 10.3 Explicit Protection of Target-Branch User Intent

User-authored intent on the target branch is protected in two ways:

1. direct contradictions with replayed user changes are conflicts,
2. indirect overwrites caused by derived changes during restoration are also conflicts.

Thus, target-branch user intent can never be silently invalidated by propagation.

---

## 10.4 Non-Blocking Overwrite of Derived Target State

If replayed user intent from (A) only contradicts derived state on (B), merge proceeds with a warning.

This reflects the lower authority of derived state relative to explicit user intent.

---

# 11. Relation to Existing Vitruv Formalizations

This updated semantics is a conservative extension of the existing Vitruv view of evolution:

* Existing Vitruv formalizations model **sequential user change followed by consistency restoration**.
* The present extension adds:

  * explicit branch histories,
  * directed replay of user transactions,
  * provenance-sensitive conflict detection,
  * distinction between direct and indirect conflicts.

The key new insight is that in a branch merge for coupled models, conflict must be checked not only on replayed user changes themselves, but also on the **derived changes induced by restoring consistency after replay**.

That is exactly the point where ordinary model merge semantics is too weak.

---

# 12. Compact Version for the Paper

A concise formulation could be:

> We model branch merge as a directed replay of user-authored transactions from a source branch onto the head state of a target branch. After each replayed transaction, Vitruv’s consistency restoration mechanism is executed. Conflicts are raised not only when replayed user transactions directly contradict user-authored transactions of the target branch, but also when derived changes produced during restoration would overwrite target-branch user changes. In contrast, overwriting derived target-branch state by replayed user changes only yields a warning.

---

# 13. Most Important Conceptual Change

The main refinement from the earlier version is:

* before:
  derived(replay(A)) vs user(B) was only a warning
* now:
  derived(replay(A)) vs user(B) is a **semantic conflict**

That is the right choice if you want to preserve user intent on the target branch robustly.

If you want, I can now turn this into paste-ready LaTeX with definitions, theorem-style environments, and notation consistent with the earlier Vitruv formalization.

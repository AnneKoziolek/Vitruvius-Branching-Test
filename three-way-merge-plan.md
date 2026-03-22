Here is a structured plan you can hand off for implementation.

# Plan for Git-Integrated Branching and Semantic Three-Way Merge in Vitruv

## 1. Goal

We want to enable **branching and branch integration for Vitruv-managed projects** using Git as the version-control backend, while preserving **Vitruv semantics** during branch integration.

The core idea is:

* a project is stored in a Git repository
* each Git branch represents one branch of the Vitruv-managed system
* users work on a branch through a Vitruv server instance attached to that branch
* when branches are integrated, the integration should not be a plain text-level Git merge
* instead, a **Vitruv-aware three-way merge** should be executed that understands semantic model changes and propagation behavior

The intended result is a merge process that is closer to “replay semantic changes from one branch onto another branch head” than to a conventional file merge, while still fitting into normal Git workflows.

---

## 2. What we aim to achieve

We aim to build a system with the following properties:

### 2.1 Branches are managed by Git

Git should remain the external source of truth for:

* repository history
* branch creation
* branch switching
* merge commits
* collaboration with existing Git tooling

Users should be able to use normal Git branches.

### 2.2 Vitruv is branch-aware

A Vitruv server instance should operate against a selected Git branch and read/write the branch state from that branch.

At minimum, the Vitruv server must be able to:

* open a project from a given Git branch
* detect or be told when the branch changes
* persist its managed artifacts back into the checked-out branch

### 2.3 Merge is semantic, not textual

When integrating two branches, Git’s normal textual merge must be replaced or augmented by a **semantic merge procedure** that is aware of Vitruv concepts.

This merge should use:

* a common ancestor branch state
* one target branch head (“ours” / branch A)
* one source branch head (“theirs” / branch B)
* the semantic changes that happened in branch B since the common ancestor

The merge procedure should attempt to apply branch-B semantic changes onto branch-A head.

### 2.4 Change history is explicitly stored

We currently assume that reconstructing semantic changes reliably from persisted branch snapshots is not sufficient.

Therefore, the system should maintain a **separate serialized semantic change log** in the repository.

This log should be versioned in Git alongside the persisted model artifacts.

### 2.5 Merge remains compatible with Git workflows

Even though the semantic algorithm may internally work like replay/rebase, the external user-facing workflow should still support normal Git integration patterns, especially merge.

The end result should be usable in workflows like:

* feature branch creation
* parallel development
* merge into main
* conflict reporting if semantic integration fails

---

## 3. Conceptual model of the solution

## 3.1 High-level architecture

The architecture should consist of at least these parts:

### A. Git repository

Stores:

* persisted Vitruv-managed artifacts
* possibly VSUM-related persisted state
* serialized semantic change logs
* metadata required for merge

### B. Vitruv server/runtime

Responsible for:

* loading the current project state from the checked-out branch
* recording semantic changes during editing
* persisting artifacts and change-log entries
* invoking propagation as required

### C. Semantic merge engine

Responsible for:

* receiving merge inputs
* loading ancestor / ours / theirs context as needed
* reading change logs
* replaying changes
* detecting semantic conflicts
* producing merged output

### D. Git integration layer

Responsible for:

* hooking the semantic merge into Git
* invoking the merge engine during Git merge
* returning a merged working tree state to Git

---

## 4. Proposed merge semantics

## 4.1 Core strategy

The preferred initial strategy is:

1. determine the Git merge base
2. take branch A head as the merge target
3. obtain the ordered semantic changes performed on branch B since the merge base
4. replay those semantic changes onto branch A head
5. let Vitruv propagation/update mechanisms bring the merged state to a consistent state
6. detect and report conflicts where replay is impossible or semantically ambiguous
7. persist the merged artifacts and commit them through Git

This should be treated as a **semantic three-way merge** because the common ancestor is still important for choosing the change range and for conflict reasoning.

## 4.2 Merge vs rebase terminology

Architecturally:

* the internal algorithm is replay-based and resembles rebase/cherry-pick
* the external Git operation can still be a merge producing a merge commit

So the implementation should not get blocked by terminology. The important point is:

* **Git-level operation:** merge-compatible
* **Vitruv-level algorithm:** semantic replay based on a common ancestor

---

## 5. Assumptions

The current plan should proceed with these assumptions unless later investigation disproves them.

### 5.1 Separate change log is required

We assume that semantic changes cannot be reconstructed robustly enough from persisted branch snapshots alone.

Therefore, the merge must rely on an explicit serialized semantic change log.

### 5.2 Plain file-level Git merge is insufficient

We assume that textual merging of persisted artifacts is not sufficient because:

* models may be distributed across multiple resources
* one user action may propagate to multiple resources
* textual conflicts do not capture semantic conflicts
* textual non-conflicts may still violate consistency

### 5.3 Some persisted state exists, but that is not enough

We assume persisted artifacts exist in the repository, but the persisted branch state alone should not be treated as the authoritative source for semantic replay.

### 5.4 Replay should focus on intended changes

We assume the merge should primarily replay **user-intended / primary semantic changes**, not every low-level or derived propagated update.

---

## 6. Work packages

## WP1: Define the repository layout

We need a clear repository structure that can be used by both Vitruv and Git tooling.

### Objectives

Define what is stored in the repo and where.

### Deliverables

A repository layout specification covering:

* persisted model/resources location
* VSUM-related persisted artifacts, if any
* semantic change-log storage location
* metadata files needed by the merge engine
* branch-specific and branch-independent contents

### Initial direction

A likely structure is:

* model/resource files in their normal persisted form
* one directory for change-log artifacts
* one metadata file or set of files for merge bookkeeping

### Open questions

* What exact artifacts does Vitruv already persist?
* Is there one VSUM artifact, many resource files, or both?
* Should the change log be global, per branch, per transaction, or per commit?
* Should change logs be committed as standalone files or embedded in a structured storage format?

---

## WP2: Define the semantic change-log format

This is the most important technical foundation.

### Objectives

Specify how semantic changes are represented, serialized, stored, and read back.

### Deliverables

A precise schema for one change-log entry and one change-log sequence.

### Minimum contents of each log entry

Each semantic change or change transaction should include at least:

* unique identifier
* ordering information
* timestamp
* author/session information if useful
* associated Git commit or predecessor reference if useful
* operation type
* affected element identifiers
* operation parameters
* whether the change is primary or derived
* possibly context needed for replay

### Preferred logging granularity

A transaction-oriented log is likely better than individual raw low-level changes, so that one user action can be replayed as one semantic unit.

### Open questions

* What is the right abstraction level for logged changes?

  * raw EMF notifications
  * EChanges
  * Vitruv-specific higher-level operations
* How do we distinguish primary from derived changes?
* How much context must be stored to replay changes robustly?
* Should the log be append-only?
* Do we store one log file per transaction, per commit, or per branch history?

---

## WP3: Define stable identity for model elements

Semantic replay and conflict detection require robust element identity.

### Objectives

Ensure that changes can reliably refer to the same conceptual objects across branches.

### Deliverables

An identity strategy for all relevant model elements.

### Possible strategies

Potential candidates include:

* stable UUIDs persisted with elements
* resource URI + fragment, if stable enough
* Vitruv-managed global identifiers
* hybrid identity with fallback resolution

### Requirements

The chosen identity strategy must support:

* replay on another branch state
* conflict detection
* element lookup after renaming/moving/restructuring
* persistence across sessions

### Open questions

* What stable identity mechanisms already exist in Vitruv?
* Which identifiers survive branch divergence and restructuring?
* How should identity be handled for newly created elements that exist only on one branch?
* How should deleted elements be represented in replay and conflict detection?

---

## WP4: Integrate change recording into Vitruv runtime

The runtime must emit semantic logs as users work.

### Objectives

Capture semantic changes during normal branch work and persist them in a form usable for merge.

### Deliverables

A change-recording component integrated into the Vitruv server.

### Responsibilities

This component should:

* observe user/model changes
* group them into transactions
* classify primary vs derived changes
* serialize them
* persist them in the repository
* keep log ordering deterministic

### Open questions

* Where in Vitruv’s architecture should recording be attached?
* Can existing change-recording infrastructure be reused directly?
* How are transactions delimited?
* Should a transaction correspond to a user command, a propagation cycle, or a server-side unit of work?

---

## WP5: Define branch switching behavior in the Vitruv server

We need a clean operational model for how the server relates to Git branches.

### Objectives

Specify what “switching the Vitruv server to a Git branch” means operationally.

### Deliverables

A branch-switching protocol.

### Minimum behavior

When switching branches, the system must define:

* whether the same server instance can switch branches safely
* whether a fresh runtime/server instance is required
* how in-memory state is invalidated/reloaded
* how uncommitted changes are handled
* whether caches and propagated states must be recomputed

### Preferred conservative approach

Initially, it may be safer to treat branch switching as requiring a clean reload of the project state rather than a live in-memory branch swap.

### Open questions

* Is hot branch switching feasible without corrupting runtime state?
* Which parts of the in-memory state are branch-dependent?
* Must propagation be rerun after branch switch?
* Can multiple servers safely operate on different branches of the same repository clone, or should separate clones/workspaces be used?

---

## WP6: Design the semantic merge engine

This is the core integration logic.

### Objectives

Implement the engine that performs the semantic three-way merge.

### Deliverables

A merge algorithm specification and prototype implementation.

### Input

The engine should receive at least:

* merge base commit or state
* target branch head state
* source branch head state
* source-branch semantic change sequence since merge base

### Output

The engine should produce either:

* merged state + updated change log, or
* explicit semantic conflict report

### Proposed first algorithm

1. load target state
2. load or identify source change sequence since common ancestor
3. iterate over source semantic transactions in order
4. for each transaction:

   * resolve referenced elements in target state
   * test applicability
   * apply semantic operation
   * run required propagation/update logic
   * check invariants/consistency
5. if a transaction cannot be applied cleanly:

   * mark semantic conflict
   * stop or enter conflict-collection mode
6. persist merged result

### Conflict classes to support

At minimum, the engine should recognize:

* target element deleted on one branch but changed on the other
* incompatible concurrent changes to same semantic property
* moves/renames causing identifier mismatch
* replay operation precondition not satisfied
* replay causes consistency violation or propagation failure

### Open questions

* Should propagation run after every transaction or after batches?
* Should the engine stop at first conflict or collect multiple conflicts?
* Can replay be made partially tolerant with fallback matching?
* Should the merge engine ever attempt automatic conflict resolution heuristics?

---

## WP7: Define conflict detection and conflict representation

Conflict handling needs to be first-class, not an afterthought.

### Objectives

Specify what constitutes a semantic conflict and how it is exposed.

### Deliverables

A conflict model and reporting format.

### Conflict report should include

* conflict type
* involved branch changes
* involved elements
* reason replay failed
* possible resolution options if known

### Resolution modes

At least two future modes should be anticipated:

* fail merge and ask user to resolve manually
* allow explicit branch preference or semantic resolution strategy

### Open questions

* How should conflicts be surfaced to users?
* As text in Git merge output?
* As a structured report file in the repository?
* As a dedicated UI in a Vitruv tool?
* Can some conflicts be transformed into postponed manual decisions?

---

## WP8: Hook the merge into Git

The semantic merge must integrate into real Git workflows.

### Objectives

Connect Git merge invocation to the Vitruv merge engine.

### Deliverables

A Git integration design and prototype.

### Preferred direction

Use a **custom Git merge driver** assigned to Vitruv-managed content.

This driver should:

* receive Git’s merge inputs
* invoke the semantic merge engine
* materialize merged output into the working tree
* return success/failure to Git

### Alternative or supplementary direction

Provide an explicit wrapper command, e.g. a dedicated Vitruv merge command, that can be used manually or by the merge driver.

### Open questions

* What exact repository paths should be associated with the merge driver?
* Should the driver own the whole project tree or only change-log files / central artifacts?
* How do we ensure Git does not also perform conflicting textual merges on subordinate files?
* How should merge-driver failure be mapped to Git conflict behavior?

---

## WP9: Define commit-time consistency between artifacts and change log

The repo must not end up in a state where snapshots and logs disagree.

### Objectives

Ensure that persisted artifacts and semantic logs are always synchronized.

### Deliverables

A consistency protocol for writes and commits.

### Requirements

When a user change is committed, the repository should contain:

* updated persisted artifacts
* corresponding semantic log entries
* any required metadata updates

### Open questions

* Should synchronization be enforced at Vitruv save time, commit time, or both?
* Do we need a validation command that checks correspondence between snapshot state and change log?
* What happens if a user manually edits files outside Vitruv?
* How do we detect and handle branch states with missing or corrupt logs?

---

## WP10: Decide how much of the VSUM is persisted and used during merge

This is still partly open and should be investigated explicitly.

### Objectives

Clarify what “persisted VSUM” means in the implementation.

### Deliverables

A persistence model clarifying what artifacts are authoritative for runtime reload and merge.

### Questions to answer

* Is there a single persisted VSUM artifact?
* Is the VSUM only an in-memory abstraction over persisted resources?
* What exact data must be loaded to create a usable branch state for replay?
* Is there branch-specific runtime metadata that must also be persisted?

### Why this matters

The merge engine needs a well-defined way to instantiate the target branch state before replaying semantic changes.

---

## WP11: Define handling of derived and propagated changes

This is critical for keeping the merge meaningful and manageable.

### Objectives

Separate user intent from automatically propagated consequences.

### Deliverables

A policy for which changes are logged, merged, and regenerated.

### Preferred initial policy

* log primary user-intended semantic changes explicitly
* either do not log derived changes, or mark them as derived
* after replay, rerun propagation to regenerate derived consequences

### Open questions

* Can all derived changes safely be regenerated?
* Are there propagated updates that must still be explicitly logged to preserve intent?
* How do we identify the boundary between primary and derived changes in Vitruv?

---

## WP12: Define supported merge scenarios and test cases

A good test suite should shape the design early.

### Objectives

Create canonical scenarios the implementation must handle.

### Deliverables

A merge scenario catalogue and acceptance tests.

### Mandatory scenarios

1. **Independent non-overlapping edits**
   Two branches edit different conceptual elements; merge should succeed.

2. **Concurrent identical edit**
   Same semantic change happens on both branches; merge should recognize equivalence if possible.

3. **Delete vs modify conflict**
   One branch deletes an element, the other modifies it; merge should report conflict.

4. **Rename vs modify**
   One branch renames/moves, the other edits; merge should test identity resolution.

5. **Parallel edits with propagation**
   Edits propagate into multiple resources; merge should avoid noisy textual conflicts.

6. **Create on both branches**
   Similar or same-named new elements created on both branches; merge should determine whether they are distinct or conflicting.

7. **Merge after long divergence**
   Larger source-branch change sequence replayed onto updated target branch.

8. **Broken or missing log**
   Merge should fail safely with explicit diagnostics.

### Open questions

* Which existing Vitruv examples are best suited as benchmark cases?
* Can these scenarios be encoded as automated integration tests from the start?

---

## 7. Suggested implementation phases

## Phase 1: Investigation and foundations

Focus on understanding current Vitruv persistence and change mechanisms.

Tasks:

* inspect existing persistence behavior
* inspect available change-recording infrastructure
* identify usable semantic change abstraction
* identify stable identity mechanism candidates
* decide minimal repository layout

Output:

* technical design document
* logging format draft
* feasibility assessment

## Phase 2: Change-log infrastructure

Build the ability to record and persist semantic changes.

Tasks:

* implement transaction capture
* serialize log entries
* persist log files in repo
* support reading log sequences for a commit range

Output:

* working recorder
* working log reader
* deterministic replay input

## Phase 3: Replay prototype

Build a standalone semantic replay engine.

Tasks:

* load branch target state
* replay source transactions
* rerun propagation
* detect simple conflicts

Output:

* prototype merge engine on controlled examples

## Phase 4: Git integration

Wire the merge engine into Git workflows.

Tasks:

* add custom merge driver or wrapper command
* connect merge-base detection and revision range extraction
* write merged artifacts back into working tree

Output:

* end-to-end merge from Git command to semantic result

## Phase 5: Conflict reporting and hardening

Improve usability and robustness.

Tasks:

* add structured conflict reports
* improve diagnostics
* handle corrupted or missing logs
* improve test coverage
* measure performance on larger histories

Output:

* robust prototype suitable for broader experimentation

---

## 8. Key design decisions currently recommended

The implementation should proceed with these as the current working decisions:

### Decision A

Use **Git for branch management and history**.

### Decision B

Use a **Vitruv-aware semantic three-way merge**, not a plain textual merge.

### Decision C

Treat the semantic merge as **replaying source-branch semantic changes onto target-branch head**, using the common ancestor to determine the relevant change range and conflicts.

### Decision D

Introduce a **separate serialized semantic change log** as a first-class artifact in the repository.

### Decision E

Prefer replaying **primary semantic changes** and regenerating derived/propagated consequences afterward.

### Decision F

Initially expose this as a **merge-compatible workflow**, even if the internals resemble rebase.

### Decision G

Prefer a **clean-reload branch switch model** over live in-memory switching unless later investigation proves hot switching safe.

---

## 9. Important open questions that need further investigation

These are the main unresolved points that the implementing agent should investigate first.

### Persistence and runtime

* What exactly of the VSUM/project state is persisted today?
* What is needed to reconstruct a usable runtime state from a branch checkout?
* Is there a difference between persisted resources and the in-memory VSUM abstraction that matters for merge?

### Change abstraction

* What is the best semantic unit for logging and replay?
* Are existing Vitruv change objects sufficient, or is a higher-level operation layer needed?

### Identity

* Which stable identifier scheme is robust enough for cross-branch replay?
* How do identifiers behave under rename/move/delete?

### Primary vs derived changes

* How can primary user intent be distinguished from propagated consequences?
* Can derived changes always be regenerated instead of logged and merged?

### Git integration

* Which exact Git integration mechanism should be used first: merge driver, wrapper command, or both?
* Which files should Git delegate to the custom merge driver?

### Conflict handling

* What set of semantic conflicts should be recognized in the first implementation?
* How should conflicts be represented to users and tools?

### Reliability

* How to detect missing, stale, or inconsistent change logs?
* What safety behavior should happen when logs cannot be trusted?

### Performance

* How expensive is replay for long-running branches?
* Do we need log compaction, snapshots, or checkpointing?

---

## 10. Minimal viable prototype

A first prototype does not need to solve everything.

A realistic MVP would do the following:

* one Git repository with one sample Vitruv project
* explicit branch checkout before starting Vitruv runtime
* semantic logging of a restricted class of operations
* replay of source-branch operations onto target branch
* simple semantic conflict detection
* execution through a dedicated command or Git merge driver
* merged artifacts written back to the working tree

The MVP can initially ignore or simplify:

* advanced conflict resolution
* hot branch switching
* full support for all change kinds
* sophisticated UI
* performance optimization for large histories

---

## 11. Expected final outcome

If successful, this work should yield:

* a Git-based branching model for Vitruv projects
* a semantic three-way merge process for branch integration
* a persistent semantic change-log mechanism
* a conflict model at the semantic level
* a prototype showing that Vitruv-managed multi-model projects can be merged more meaningfully than with plain textual Git merge

---

## 12. One-sentence summary for the implementing agent

Implement a Git-integrated, Vitruv-aware semantic three-way merge that replays serialized branch-specific semantic changes from one branch onto another branch head, using Git only for branch/history management and a separate persisted change log as the authoritative merge input.

---

## 13. Implementation Status (Current Prototype)

The following has been implemented and tested:

### What Works

| Feature | Status | Test |
|---------|--------|------|
| **Additive merge** (both branches add elements) | Working | `nonConflictingMerge` — 3 components merged from 2 branches |
| **Changelog capture** (fine-grained EChanges per commit) | Working | `changelogCaptureAndPersistence` — JSON DTO round-trip verified |
| **UUID-based conflict detection** (same element renamed by both) | Working | `renameSameComponent_conflict` — MODIFY_MODIFY on "name" detected |
| **Conflict resolution** (ours/theirs choice) | Working | `renameConflict_resolveWithTheirs` — theirs' value applied |
| **Empty branch merge** (no-op) | Working | `emptyBranchMerge` — succeeds with 0 changes |
| **Reactions fire during merge** | Working | model2 Entities created/updated during merge replay |

### Architecture Decisions Made

1. **Two view modes**: `withChangeRecordingTrait()` for modifications (preserves UUIDs) and `withChangeDerivingTrait()` for additions (state diff OK since new elements get fresh UUIDs)
2. **JSON DTO serialization**: XMI serialization of `EChange<HierarchicalId>` fails (generic `ETypeParameter` is `EJavaObject`, not `EReference`). JSON DTOs capture all essential fields.
3. **UUID identity**: UUIDs are random (`EcoreUtil.generateUUID()`), Git-tracked in `vsum/uuid.uuid`. Common ancestor elements have the same UUID on both branches.
4. **View-based replay**: Merge applies changes through `ChangeDerivingView` (additions) or `ChangeRecordingView` (conflict resolutions), so reactions fire automatically.
5. **Primary models only**: Derived models (e.g., `.model2`) are regenerated by reactions during replay, not merged directly.

### Open Work

- EChange deserialization from JSON DTOs (reconstruct live `EChange<HierarchicalId>` for direct replay)
- DELETE_MODIFY conflict handling (deletion + modification)
- Rename propagation verification (rename Component → Entity also renamed)
- Multi-commit branches (multiple changelogs per merge range)
- Git merge driver integration

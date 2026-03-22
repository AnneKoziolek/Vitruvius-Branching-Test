Here is a compact summary you can hand to the coding agent.

## Main contributions of the paper

The paper’s core contribution is **not just “model merge”**, but **Git-style branching and three-way merge for a VSUM / transformation network with consistency preservation**. In your current architecture, that means: branch states are plain Git states, branch-local user changes are recorded semantically as change logs, and merge is defined as replaying branch deltas on the other branch head through Vitruv’s propagation engine so that reactions re-establish consistency. The prototype is explicitly built around that idea, with `BranchManager`, Git hooks/watchers, semantic changelog capture, conflict detection, and a semantic replay merge engine.   

Conceptually, the paper contributes five things.

First, it gives a **formal extension of Vitruv from sequential consistency preservation to branching and merge**. Existing Vitruv formalizations define global states, consistency relations, and sequential consistency-preserving application via CPRs or transformation application functions. Your paper adds divergence triples, branch deltas, structural conflicts, semantic conflicts, and a merge operator that is a conservative extension of `APP`: merge = combine branch deltas, then apply Vitruv-style consistency-preserving propagation. That fits directly to the formal line from Klare’s dissertation, the JSS paper, ISoLA 2024, and CoCoPath.   

Second, it contributes a **Vitruv-aware merge semantics**. In your formulation, merge is not “take Git’s merged files and validate later,” but “merge branch effects semantically, then let the propagation engine rebuild a consistent VSUM.” That is the key novelty against ordinary model merge. The implementation plan already reflects this by replaying serialized `EChange`s via `propagateChange()`, so that derived changes, correspondences, and consistency restoration are regenerated rather than textually merged.  

Third, it introduces a **conflict model tailored to coupled models**. Klare’s dissertation identifies synchronization, contradiction, and orchestration as the central correctness challenges in transformation networks. Your paper lifts that into the branching setting: structural conflicts cover overlapping user edits; semantic conflicts cover jointly unrealizable branch effects; and reaction-induced incompatibilities are handled through replay plus consistency restoration or surfaced as merge conflicts. This is a natural but genuinely new extension of Klare’s transformation-network correctness notion.   

Fourth, it contributes a **prototype architecture that integrates Git and Vitruv without embedding Git into the VSUM itself**. Git remains the branch store, while Vitruv reacts to working-tree changes through `reload()` and semantic change capture. This layering is important as an engineering contribution: it shows how branch switching, pre-commit validation, post-commit semantic changelog generation, and semantic merge can be realized on top of existing Vitruv infrastructure with JGit, hooks, and watchers.  

Fifth, the paper sharpens the **scope distinction between user-driven branching and parameterized repair branching**. CoCoPath explores alternative execution paths of a parameterized CPR under user decisions; your merge work explores the combination of two independently evolved, already materialized branch histories. That is related, but different: CoCoPath branches inside one repair space, while your work branches in repository history and must reconcile two deltas plus their propagated consequences.  

## Distinction to related work

### 1. Single-model versioning and merge

The closest practical baseline is **EMF Compare**. It supports model-level comparison and merge for EMF models, including three-way comparison with explicit origin/left/right matching and conflict handling, and it integrates with Eclipse Team / Git workflows. But it still fundamentally treats the problem as **merge of EMF logical models**, not merge of a network of coupled models with propagation semantics. It can detect and merge model differences and stop on conflicts, but it does not define merge as “apply branch deltas through a consistency-preserving transformation network.” ([eclipse.dev][1])

So the distinction is: **EMF Compare merges models; your paper merges branch evolution in a VSUM and regenerates consistency via CPR execution**.

### 2. Bidirectional transformations and concurrent update synchronization

This is the most important theoretical neighbor. Klare explicitly places his work next to **bidirectional synchronization with reconciliation**, including work by **Xiong et al.** on executing transformations in both directions and merging generated changes, as well as TGG-based approaches for concurrent modifications and conflict handling. He also discusses **EVL+trace** and TGG conflict-resolution work.  

Your distinction here is very clear:

* BX synchronization literature usually focuses on **concurrent updates of related artifacts**, often in the two-model case.
* Your work focuses on **Git branches over a multi-model VSUM / transformation network**, with explicit common ancestor, branch histories, branch deltas, and three-way merge.
* Klare’s synchronization work assumes sequential orchestration in a transformation network and explicitly excludes conflicting user changes from the main synchronization problem; your paper puts such divergence and conflict at the center because branching creates exactly that situation.  

So the distinction is: **BX work studies reconciliation of concurrent edits; your paper studies repository-style three-way merge for transformation-network evolution**.

### 3. Transformation networks / megamodel-style consistency management

Klare’s dissertation is the strongest direct predecessor. It formalizes **transformation networks** and their correctness in terms of **compatibility, synchronization, and orchestration**, proves that orchestration is undecidable in general, and analyzes quality/topology trade-offs in such networks.   

Your paper builds directly on that line, but adds what Klare does not cover:

* no branch semantics,
* no divergence triples,
* no common-ancestor-based merge,
* no semantic changelog / replay merge,
* no explicit merge conflict notion for branch histories.

So the distinction is: **Klare gives the formal basis for correct sequential evolution in transformation networks; your paper extends that basis with branching and join semantics**. 

### 4. Vitruv itself

The **Vitruv / Vitruvius JSS paper** gives the framework-level semantics: VSUM, CPRs, and consistency-preserving application over modular metamodels. CoCoPath then extends this with **parameterized CPRs** and path exploration for temporary inconsistency.  

Your distinction to prior Vitruv work is:

* JSS: sequential consistency preservation in a VSUM.
* CoCoPath: exploration of user-decision paths inside one CPR execution.
* Your paper: branching and three-way merge between two independently evolved, consistent VSUM states.

So the distinction is: **previous Vitruv work handles one evolution path at a time; your paper handles divergence and re-joining of evolution paths**.

### 5. Consistency-preservation tools and repair-space exploration

CoCoPath already surveys tools like **DesignSpace**, **OpenFlexo**, **MetaEdit+**, **Comprehensive Systems**, and **openCAESAR**, as well as approaches like **Eramo et al.** on underspecified relational transformations, **Egyed et al.** on smart transformation assistance, **Kretschmer et al.** on repair exploration, **Constraint-Driven Modeling**, **Laghouaouta & Laforcade**, and **Famelis et al.** on uncertainty-aware transformations. 

These are useful to mention briefly, but the distinction is straightforward: they address **repair assistance, uncertainty, or exploration of possible repairs**, whereas your paper addresses **branch merge of already committed histories**.

## One-paragraph positioning you can reuse

A good summary sentence for the paper would be:

> Existing work either addresses model-level three-way merge (e.g., EMF Compare), bidirectional synchronization of concurrent updates (e.g., Xiong et al., TGG-based reconciliation, EVL+trace), or correctness of sequential transformation networks (Klare’s dissertation, Vitruv JSS, ISoLA 2024). In contrast, our contribution is a conservative extension of Vitruv’s consistency-preserving application semantics with explicit branch and merge semantics for VSUMs: we represent branch evolution as semantic deltas, detect structural and semantic conflicts relative to a common ancestor, and compute merges by replaying branch changes through the Vitruv propagation engine. ([eclipse.dev][1])   

## The shortest “main contributions” list

For the coding agent, I would reduce it to this:

1. **Formal contribution:** extend Vitruv’s sequential semantics to branching and three-way merge over VSUM states. 
2. **Semantic contribution:** define merge as branch-delta composition plus Vitruv consistency restoration, not textual or plain model merge. 
3. **Conflict contribution:** define structural and semantic conflicts for coupled model networks. 
4. **Systems contribution:** realize Git-aware branch management, semantic changelog capture, and replay-based merge in a Vitruv prototype.  
5. **Empirical contribution:** demonstrate that replay through `propagateChange()` preserves consistency and regenerates derived artifacts/correspondences during merge. 

If you want, I’ll turn this into a paper-ready “Contributions and Related Work” subsection in polished prose.

[1]: https://eclipse.dev/emf/compare/overview.html?utm_source=chatgpt.com "EMF Compare | Home"

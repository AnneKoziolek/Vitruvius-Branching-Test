# Semantic Three-Way Merge — Test Plan

## Test Domain

Two coupled EMF metamodels with bidirectional consistency via Vitruv Reactions:

- **model.ecore**: System → Components (Device/Server/Router), Protocols, Links
- **model2.ecore**: Root → Entities, CommunicationStandards, Links

Reactions propagate model → model2:
- Component inserted → Entity created (with same name)
- Component renamed → Entity renamed
- Component deleted → Entity deleted + correspondence removed
- Protocol inserted → CommunicationStandard created
- Link inserted → Link created in model2
- Component added to Link → Entity added to model2 Link
- Protocol set on Link → CommunicationStandard set on model2 Link

---

## Test Categories

### 1. Reaction-Triggered Consistency During Merge

**1a. Component merge triggers Entity creation** *(high priority)*
- Base: System with ComponentA
- Feature branch: adds ComponentB
- Main branch: adds ComponentC
- Merge feature → main
- **Verify**: 3 Components + 3 corresponding Entities in model2
- **Why**: Proves reactions fire during merge replay, not just during normal commits

**1b. Protocol merge triggers CommunicationStandard creation**
- Base: System with one Protocol
- Feature branch: adds ProtocolB
- Main branch: adds ProtocolC
- Merge feature → main
- **Verify**: 3 Protocols + 3 CommunicationStandards in model2

**1c. Link merge with cross-model propagation**
- Base: System with 2 Components + 1 Protocol
- Feature branch: adds Link connecting both components with the protocol
- Main: no changes
- Merge feature → main
- **Verify**: Link in model2, Link.entities references the correct Entities, Link.standard set

**1d. Complex multi-element merge**
- Base: System with ComponentA
- Feature branch: adds ComponentB + ProtocolX + Link(A,B,X)
- Main branch: adds ComponentC
- Merge feature → main
- **Verify**: All 3 Components, 3 Entities, 1 Protocol, 1 CommunicationStandard, 1 Link with correct references

### 2. True Conflict Detection and Handling

**2a. Rename conflict (MODIFY_MODIFY)**
- Base: System with Component "Shared"
- Feature branch: renames "Shared" → "ServiceA"
- Main branch: renames "Shared" → "ServiceB"
- Merge feature → main
- **Verify**: Merge result has status CONFLICT, conflict identifies the element and both rename changes
- **Note**: Requires changelog-based identity (UUID) for reliable detection; state-based diff matches by position and may not detect this correctly

**2b. Delete-vs-modify conflict (DELETE_MODIFY)**
- Base: System with Component "Target"
- Feature branch: deletes "Target"
- Main branch: renames "Target" → "UpdatedTarget"
- Merge feature → main
- **Verify**: Merge result has status CONFLICT with type DELETE_MODIFY

**2c. Modify-vs-delete conflict (MODIFY_DELETE)**
- Same as 2b but reversed (main deletes, feature modifies)
- **Verify**: Merge result has status CONFLICT with type MODIFY_DELETE

**2d. Non-conflicting attribute changes on different elements**
- Base: System with ComponentA and ComponentB
- Feature branch: renames ComponentA → "Alpha"
- Main branch: renames ComponentB → "Beta"
- Merge feature → main
- **Verify**: Merge succeeds, both renames applied

### 3. Multi-Model Consistency After Merge

**3a. Correspondence model integrity**
- Base: System with ComponentA (correspondence to EntityA)
- Feature branch: adds ComponentB (correspondence to EntityB created)
- Main branch: adds ComponentC (correspondence to EntityC created)
- Merge feature → main
- **Verify**: After merge, correspondence model has entries for all 3 pairs

**3b. Derived model regeneration (model2 consistency)**
- After any merge, verify that model2 is a correct projection of model:
  - Every Component has exactly one corresponding Entity
  - Every Protocol has exactly one CommunicationStandard
  - Entity names match Component names
  - No orphaned Entities (entities without corresponding components)

**3c. UUID resolver integrity after merge**
- After merge, verify that UuidResolver can resolve all model elements
- No dangling UUID references

### 4. Subtype Handling

**4a. Device/Server/Router merge**
- Base: System with one Device
- Feature branch: adds a Server
- Main branch: adds a Router
- Merge feature → main
- **Verify**: All 3 subtypes present, all have corresponding Entities

### 5. Edge Cases

**5a. Empty branch merge (no-op)**
- Base: System with ComponentA
- Feature branch: no changes
- Main branch: adds ComponentB
- Merge feature → main
- **Verify**: Merge succeeds with 0 applied changes, model unchanged

**5b. Identical changes on both branches**
- Base: System with ComponentA
- Feature branch: adds ComponentB with name "Same"
- Main branch: adds ComponentC with name "Same" (different element, same name)
- Merge feature → main
- **Verify**: Merge succeeds, both components present (different objects, same name is OK)

**5c. Merge with long divergence (multiple commits)**
- Base: System with ComponentA
- Feature branch: commit1 adds B, commit2 adds C, commit3 renames A→"Alpha"
- Main branch: commit1 adds D, commit2 adds E
- Merge feature → main
- **Verify**: All 5 components present with correct names (Alpha, B, C, D, E)

**5d. Large model merge**
- Base: System with 10 components
- Feature branch: adds 5 more components
- Main branch: adds 5 different components
- Merge feature → main
- **Verify**: 20 components present, 20 corresponding entities

### 6. Failure Cases

**6a. Replay failure produces meaningful error**
- Construct a scenario where replay fails (e.g., structural incompatibility)
- **Verify**: SemanticMergeResult contains meaningful error info, VSUM is not corrupted

**6b. Missing merge base**
- Two branches with no common ancestor
- **Verify**: Merge fails with clear error message about missing merge base

---

## Implementation Priority

### Phase 1 (immediate — prototype paper)
- **1a**: Component merge + Entity verification ← extends current test
- **2a**: Rename conflict detection
- **2b**: Delete-vs-modify conflict
- **5a**: Empty branch merge

### Phase 2 (near-term)
- **1b**: Protocol merge
- **1c**: Link merge
- **3a**: Correspondence integrity
- **3b**: Derived model consistency check
- **4a**: Subtype handling

### Phase 3 (later)
- **1d**: Complex multi-element merge
- **2d**: Non-conflicting attribute changes
- **5c**: Long divergence
- **5d**: Large model
- **6a/6b**: Failure cases

---

## Testing Infrastructure Notes

- All tests use `@TempDir` for isolated Git repos
- EMF resource factory must be registered globally (`@BeforeAll`)
- After `vsum.reload()`, `ChangeLogCapture` must be re-registered
- View-based merge approach handles reactions automatically
- For conflict tests with state-based derivation, position-based matching
  causes false positives — need UUID-based changelogs for reliable conflict detection

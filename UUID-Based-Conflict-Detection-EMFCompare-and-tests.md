# Plan: UUID-Based Conflict Detection + Comprehensive Merge Tests

## Context

The semantic three-way merge prototype works for additive merges (both branches add new elements). However:
1. **Conflict detection is skipped** — EMFCompare's state-based diff matches by position, causing false positives when two branches independently add elements
2. **Renames/deletions aren't handled** — only additive changes (new elements) are merged
3. **Reactions aren't verified** — tests don't check that model2 Entities are created during merge

The user wants: "If two branches rename the same component, report a conflict and let the user choose ours vs theirs. But adding different components is not a conflict."

**Key insight**: UUIDs are random (`EcoreUtil.generateUUID()`), so independent creations get different UUIDs. Elements from the common ancestor have the SAME UUID on both branches (Git-tracked `vsum/uuid.uuid`). This makes UUID the right identity for conflict detection.

## Approach: UUID-Based Three-Way Diff

Replace EMFCompare position-matching with UUID-based identity matching:

1. Parse `vsum/uuid.uuid` from base, ours, theirs (simple text: `uuid|hierarchicalId` per line)
2. Classify each UUID: added/deleted/modified/unchanged across the three states
3. For elements present in all three: compare attribute values to detect real conflicts
4. Apply non-conflicting changes via the existing view-based mechanism
5. Report conflicts with enough detail for user to choose ours/theirs

## Files to Create

### In `/workspace/Vitruv/vsum/src/main/java/tools/vitruv/framework/vsum/branch/merge/`:

**`UuidMappingLoader.java`** — Parse uuid.uuid without needing a full VSUM
```java
// Reads vsum/uuid.uuid → Map<String, String> (uuid → hierarchicalId)
public static Map<String, String> loadUuidMappings(Path uuidFile)
```
Format: split each line on `|` (constant from `UuidResolverImpl.SERIALIZATION_SEPARATOR`).

**`UuidThreeWayDiff.java`** — Core three-way diff using UUID identity
```java
public record ElementDiff(String uuid, DiffKind kind, /* attribute diffs */)
public enum DiffKind { UNCHANGED, ADDED_BY_OURS, ADDED_BY_THEIRS,
                       DELETED_BY_OURS, DELETED_BY_THEIRS,
                       MODIFIED_BY_OURS, MODIFIED_BY_THEIRS,
                       MODIFIED_BY_BOTH_SAME, MODIFIED_BY_BOTH_CONFLICT }

public List<ElementDiff> computeDiff(
    Map<String,String> baseMappings, Map<String,String> oursMappings,
    Map<String,String> theirsMappings,
    Path baseDir, Path oursDir, Path theirsDir,
    List<String> primaryModelFiles)
```
Algorithm:
- UUID in base+ours+theirs → load EObjects, compare each EAttribute
- UUID in theirs only → ADDED_BY_THEIRS
- UUID in base+ours, not theirs → DELETED_BY_THEIRS
- For attribute comparison: if both changed from base AND values differ → CONFLICT

**`ConflictResolution.java`** — User's choice per conflict
```java
public record ConflictResolution(String uuid, Choice choice) {
    public enum Choice { OURS, THEIRS }
}
```

**`ConflictResolutionProvider.java`** — Functional interface for resolution strategy
```java
@FunctionalInterface
public interface ConflictResolutionProvider {
    List<ConflictResolution> resolve(List<MergeConflict> conflicts);
}
// Static helpers: chooseAllOurs(), chooseAllTheirs()
```

## Files to Modify

**`MergeConflict.java`** — Add UUID-based fields
- `uuid`, `conflictingAttribute`, `baseValue`, `oursValue`, `theirsValue`
- Second constructor for UUID-based conflicts

**`SemanticMergeResult.java`** — Add `SUCCESS_WITH_RESOLUTIONS` status + `appliedResolutions` field

**`ConflictDetector.java`** — Add `detectConflictsFromDiff(List<ElementDiff>)` using UUID diffs

**`SemanticMergeEngine.java`** — Rewrite `merge()`:
1. Extract states (unchanged)
2. Load UUID mappings from `vsum/uuid.uuid` in each temp dir
3. Compute `UuidThreeWayDiff`
4. Detect conflicts via `ConflictDetector.detectConflictsFromDiff()`
5. If conflicts + no resolver → return CONFLICT
6. If conflicts + resolver → get resolutions, partition changes
7. Apply via enhanced `replayChangesViaView()`:
   - **Additions**: find elements by UUID in theirs, copy to view (current logic but UUID-based)
   - **Deletions**: find elements by UUID in ours, remove from view
   - **Modifications (non-conflicting)**: find element in view, update changed attributes
   - **Resolved conflicts**: apply chosen value (ours or theirs)
8. `view.commitChanges()` → reactions fire

**`SemanticMergeCommand.java`** — Add overload accepting `ConflictResolutionProvider`

## Test Cases

### In `SemanticMergeIntegrationTest.java`:

**Test 1: `reactionsFireDuringMerge_model2EntitiesCreated`**
- Extends existing test: after merge, load model2 and verify 3 Entities exist with correct names matching the 3 Components
- Key assertion: reactions fired during view.commitChanges()

**Test 2: `bothBranchesRenameSameComponent_detectsConflict`**
- Base: Component("Shared")
- Ours: rename → "ServiceA"
- Theirs: rename → "ServiceB"
- Assert: CONFLICT with type MODIFY_MODIFY, identifies "name" attribute

**Test 3: `conflictResolution_choosingTheirs`**
- Same as Test 2, but provide `ConflictResolutionProvider.chooseAllTheirs()`
- Assert: SUCCESS_WITH_RESOLUTIONS, Component name is "ServiceB"

**Test 4: `conflictResolution_choosingOurs`**
- Same setup, `chooseAllOurs()`
- Assert: Component name is "ServiceA"

**Test 5: `oneBranchDeletesOtherModifies_detectsDeleteModifyConflict`**
- Base: Component("Target")
- Ours: deletes "Target"
- Theirs: renames "Target" → "Modified"
- Assert: DELETE_MODIFY conflict

**Test 6: `nonConflictingRenamesOnDifferentElements_autoMerge`**
- Base: Component("A"), Component("B")
- Ours: rename A → "Alpha"
- Theirs: rename B → "Beta"
- Assert: SUCCESS, both renames applied

**Test 7: `theirsDeletion_removedFromOursAndModel2`**
- Base: Component("Keep"), Component("Remove")
- Theirs: deletes "Remove"
- Ours: no changes
- Assert: merged has only "Keep", model2 has only 1 Entity (deletion reaction fired)

**Test 8: `emptyBranchMerge_noOp`**
- Theirs: no changes since base
- Assert: SUCCESS with 0 changes

### Test helpers to add:
```java
private void renameComponent(VirtualModel vsum, String oldName, String newName)
private void deleteComponent(VirtualModel vsum, String componentName)
```

## Implementation Order

```
Step 1: UuidMappingLoader                          (standalone, no deps)
Step 2: UuidThreeWayDiff + ElementDiff              (depends on Step 1)
Step 3: ConflictResolution + ConflictResolutionProvider  (standalone)
Step 4: Enhance MergeConflict + SemanticMergeResult (standalone)
Step 5: Enhance ConflictDetector                    (depends on Steps 2, 4)
Step 6: Rewrite SemanticMergeEngine.merge()         (depends on all above)
Step 7: Enhance SemanticMergeCommand                (depends on Step 6)
Step 8: Write all test cases                        (depends on Step 7)
Step 9: Build + run all tests                       (verification)
```

## Verification

```bash
# Build framework:
cd /workspace/Vitruv && ./mvnw clean install -Dmaven.test.skip=true

# Build and run all merge tests:
cd /workspace/Vitruvius-Branching-Test && ./mvnw clean test -pl vsum \
    -Dtest="SemanticMergeIntegrationTest"

# Run alongside existing tests to verify no regression:
cd /workspace/Vitruvius-Branching-Test && ./mvnw clean test -pl vsum \
    -Dtest="VSUMExampleTest,BranchSwitchingIntegrationTest,SemanticMergeIntegrationTest"
```

## Key Existing Code to Reuse

| Component | File | How reused |
|-----------|------|------------|
| UUID file format | `UuidResolverImpl.java:39,187,209` | `SERIALIZATION_SEPARATOR = "\|"`, `split("\\|")` |
| JGit TreeWalk extraction | `GitStateLoader.checkoutStateAtCommit()` | Extracts all files including `vsum/uuid.uuid` |
| View-based merge | `SemanticMergeEngine.replayChangesViaView()` | Enhanced with delete/modify support |
| HierarchicalIdResolver | `HierarchicalIdResolver.create(resourceSet)` | Resolve hid→EObject in loaded models |
| `withGlobalFactories()` | `ResourceSetUtil` | Create ResourceSets with proper factory registration |

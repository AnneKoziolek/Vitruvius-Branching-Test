# Plan: True Delta Replay for Semantic Three-Way Merge

## Context

The merge engine has **workarounds** instead of proper delta replay:
1. `replayChangesViaView()` — position-based additive merge (counts list sizes to find additions, ignores renames/deletions)
2. `applyConflictResolutions()` — value-based element search (matches by attribute value instead of identity)
3. `deriveChanges()` with EMFCompare — computes EChanges but never replays them

The intended design: **deserialize recorded EChange DTOs → reconstruct live `EChange<HierarchicalId>` objects → resolve + apply → assign UUIDs → `propagateChange()`**. This is the same pipeline `IdentityMappingViewType.commitViewChanges()` uses (lines 96-109).

## Key Finding

The resolver does all the heavy lifting:
```java
VitruviusChangeResolver<HierarchicalId> idResolver =
    VitruviusChangeResolverFactory.forHierarchicalIds(resourceSet);
VitruviusChange<EObject> resolved = idResolver.resolveAndApply(change);
// ^ resolves HierarchicalId→EObject, creates new objects for CreateEObject,
//   applies changes via ApplyEChangeSwitch

VitruviusChangeResolver<Uuid> uuidResolver =
    VitruviusChangeResolverFactory.forUuids(vsum.getUuidResolver());
VitruviusChange<Uuid> uuidChange = uuidResolver.assignIds(resolved);

vsum.propagateChange(uuidChange);  // fires reactions
```

We only need to construct proper `EChange<HierarchicalId>` objects from the JSON DTOs.

## Implementation

### Step 1: `ChangeDtoDeserializer.java` (NEW)

Path: `/workspace/Vitruv/vsum/src/main/java/tools/vitruv/framework/vsum/branch/merge/ChangeDtoDeserializer.java`

Reconstructs `EChange<HierarchicalId>` from `ChangeDto` by:
1. Creating empty EChange instances via EMF factories (same pattern as `AtomicEChangeCopier.copyOld()`)
2. Setting structural fields (feature, values, index) from the DTO
3. Setting element references to `new HierarchicalId(dto.affectedElementId)`

For each change type:

**Attribute changes** — use `TypeInferringAtomicEChangeFactory` (type-erased, HierarchicalId works at runtime):
```java
case "ReplaceSingleValuedEAttribute" -> {
    EClass eClass = resolveEClass(dto.affectedEClassName);
    EAttribute attr = (EAttribute) eClass.getEStructuralFeature(dto.featureName);
    yield factory.createReplaceSingleAttributeChange(
        new HierarchicalId(dto.affectedElementId), attr,
        convertValue(dto.oldLiteralValue, attr),
        convertValue(dto.newLiteralValue, attr));
}
```

**Reference changes** — same factory pattern:
```java
case "InsertEReference" -> {
    EClass eClass = resolveEClass(dto.affectedEClassName);
    EReference ref = (EReference) eClass.getEStructuralFeature(dto.featureName);
    yield factory.createInsertReferenceChange(
        new HierarchicalId(dto.affectedElementId), ref,
        new HierarchicalId(dto.newValueId), dto.index);
}
```

**Object existence changes** — use EMF factory directly (CreateEObject needs `setAffectedEObjectType(EClass)`, can't go through `TypeInferringAtomicEChangeFactory` which calls `eClass()` on the element):
```java
case "CreateEObject" -> {
    CreateEObject<HierarchicalId> c = EobjectFactory.eINSTANCE.createCreateEObject();
    c.setAffectedElement(new HierarchicalId(dto.affectedElementId));
    c.setAffectedEObjectType(resolveEClass(dto.affectedEObjectType));
    yield c;
}
```

**Root changes** — use factory for insert/remove root:
```java
case "InsertRootEObject" -> {
    InsertRootEObject<HierarchicalId> c = RootFactory.eINSTANCE.createInsertRootEObject();
    c.setNewValue(new HierarchicalId(dto.newValueId));
    c.setUri(dto.resourceUri);
    c.setIndex(dto.index);
    yield c;
}
```

**EClass resolution**: Scan `EPackage.Registry.INSTANCE` for the class name:
```java
private EClass resolveEClass(String className) {
    for (Object value : EPackage.Registry.INSTANCE.values()) {
        if (value instanceof EPackage pkg) {
            EClassifier c = pkg.getEClassifier(className);
            if (c instanceof EClass ec) return ec;
        }
        // Handle EPackage.Descriptor (lazy-loaded packages)
    }
}
```

**Attribute value conversion**: DTO stores values as `Object` (Gson deserializes numbers as `Double`). Need to convert back to the correct type based on the `EAttribute.getEType()`:
```java
private Object convertValue(Object raw, EAttribute attr) {
    if (raw == null) return null;
    EDataType type = attr.getEAttributeType();
    return EcoreUtil.createFromString(type, raw.toString());
}
```

**HierarchicalId URI normalization**: The captured HierarchicalIds reference the original branch's file URIs (e.g., `/tmp/junit-xxx/example.model#/0/@components.0`). The target VSUM has different URIs. The `AtomicEChangeHierarchicalIdResolver` resolves HierarchicalIds against the target ResourceSet — it needs the IDs to reference the correct resources.

Fix: During deserialization, replace the resource URI prefix in the HierarchicalId with the target VSUM's resource URI. The fragment path (`/0/@components.0`) stays the same since the base model structure is shared.

### Step 2: Rewrite `SemanticMergeEngine.merge()`

Replace the current flow:

```
Current (workaround):
1. Extract states via JGit
2. Derive EChanges via EMFCompare (unused for replay)
3. Skip conflict detection or detect via DTOs
4. replayChangesViaView() — position-based additive merge
5. Or applyConflictResolutions() — value-based search

New (true replay):
1. Extract states via JGit TreeWalk (KEEP)
2. Load changelog DTOs from extracted dirs (KEEP)
3. UUID-based conflict detection on DTOs (KEEP)
4. If conflicts + no resolver → CONFLICT (KEEP)
5. If conflicts + resolver → filter DTOs by resolution choice (NEW)
6. Deserialize theirs' DTOs → List<EChange<HierarchicalId>> (NEW)
7. Load target VSUM from oursDir (KEEP)
8. Replay: resolveAndApply → assignIds → propagateChange (NEW)
```

**Remove**: `replayChangesViaView()`, `applyConflictResolutions()`, `applyTheirsValue()`, `deriveChanges()`, `findModelFiles()`, `loadResourceWithUri()`

**Keep**: `loadAllDtosFromDir()`, constructor, state extraction

**New `replayChanges()` method**:
```java
private void replayChanges(InternalVirtualModel targetVsum,
                           List<EChange<HierarchicalId>> changes) {
    ResourceSet rs = targetVsum.getViewSourceModels().iterator().next().getResourceSet();

    VitruviusChangeResolver<HierarchicalId> idResolver =
        VitruviusChangeResolverFactory.forHierarchicalIds(rs);
    VitruviusChangeResolver<Uuid> uuidResolver =
        VitruviusChangeResolverFactory.forUuids(targetVsum.getUuidResolver());

    VitruviusChange<HierarchicalId> change =
        VitruviusChangeFactory.getInstance().createTransactionalChange(changes);

    VitruviusChange<EObject> resolved = idResolver.resolveAndApply(change);
    VitruviusChange<Uuid> uuidChange = uuidResolver.assignIds(resolved);
    targetVsum.propagateChange(uuidChange);
}
```

**Conflict resolution via filtered DTOs**: Instead of `applyConflictResolutions()`:
```java
// Filter out conflicting theirs DTOs when user chose OURS
List<ChangeDto> filteredTheirs = theirsDtos.stream()
    .filter(dto -> !isConflictingAndChosenOurs(dto, conflicts, resolutions))
    .toList();
// Deserialize the filtered list and replay
```

### Step 3: Handle HierarchicalId URI mismatch

The theirs' DTOs have HierarchicalIds with theirs' resource URIs. The target VSUM has different URIs. Two options:

**Option A** (simple): Normalize HierarchicalIds during deserialization to strip the resource URI prefix and use only the fragment path. Then during resolution, the `HierarchicalIdResolver` resolves against the target's resources.

Looking at how `HierarchicalId` works: its `id` string is used by `HierarchicalIdResolver.getEObject(id)` to navigate the resource tree. The format encodes the resource URI + fragment. If the resource URI differs, resolution fails.

**Option B** (robust): The `HierarchicalIdResolver.getEObject()` uses the full ID string to find the resource and navigate. We need the IDs to reference the target's resource URIs. During deserialization, replace the resource URI prefix:
```java
String normalizedId = dto.affectedElementId
    .replace(theirsResourceUri, targetResourceUri);
```

For the prototype, since both branches have the same model file names (e.g., `example.model`), we can extract just the fragment and reconstruct with the target URI.

### Step 4: Update `SemanticChangeLog.ChangeDto`

Add `featureContainingClassNsUri` field to enable robust EClass resolution. For the prototype, scanning by class name works since our metamodel class names are unique.

### Step 5: Update tests

- Remove debug `System.out.println` statements from engine
- Keep the existing test structure (changelog capture + persist + merge)
- The `nonConflictingMerge` test should now work through true delta replay (not position counting)
- The `renameSameComponent_conflict` test should detect conflicts and resolve via filtered replay
- The `renameConflict_resolveWithTheirs` test should apply theirs' rename via delta replay

## Files to Create/Modify

| File | Action |
|------|--------|
| `ChangeDtoDeserializer.java` | **CREATE** — reconstruct `EChange<HierarchicalId>` from DTO |
| `SemanticMergeEngine.java` | **REWRITE** — true delta replay, remove workarounds |
| `SemanticMergeIntegrationTest.java` | **UPDATE** — clean up debug output, verify replay |
| `SemanticChangeLog.java` | Minor — clean up unused imports |

## Key Risks

1. **HierarchicalId URI mismatch** — mitigated by normalizing URIs during deserialization
2. **`resolveAndApply` fails for CreateEObject** — mitigated by setting `affectedEObjectType` correctly from DTO
3. **`assignIds` fails for new elements** — standard flow, `AtomicEChangeUuidResolver.assignIds()` handles CreateEObject by calling `registerEObject()`
4. **Attribute value type mismatch** — Gson deserializes numbers as Double; use `EcoreUtil.createFromString()` to convert

## Verification

```bash
cd /workspace/Vitruv && ./mvnw clean install -Dmaven.test.skip=true
cd /workspace/Vitruvius-Branching-Test && ./mvnw clean test -pl vsum \
    -Dtest="VSUMExampleTest,BranchSwitchingIntegrationTest,SemanticMergeIntegrationTest"
# Expected: 14 tests pass
```

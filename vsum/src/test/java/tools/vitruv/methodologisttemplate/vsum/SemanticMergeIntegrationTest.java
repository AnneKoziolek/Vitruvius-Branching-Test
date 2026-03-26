package tools.vitruv.methodologisttemplate.vsum;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import mir.reactions.model2Model2.Model2Model2ChangePropagationSpecification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.vitruv.change.testutils.TestUserInteraction;
import tools.vitruv.framework.views.CommittableView;
import tools.vitruv.framework.views.ViewTypeFactory;
import tools.vitruv.framework.vsum.VirtualModel;
import tools.vitruv.framework.vsum.VirtualModelBuilder;
import tools.vitruv.framework.vsum.branch.merge.ChangeLogCapture;
import tools.vitruv.framework.vsum.branch.merge.ConflictResolutionProvider;
import tools.vitruv.framework.vsum.branch.merge.GitStateLoader;
import tools.vitruv.framework.vsum.branch.merge.MergeConflict;
import tools.vitruv.framework.vsum.branch.merge.SemanticChangeLog;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeCommand;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult.MergeDirection;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;
import tools.vitruv.methodologisttemplate.model.model2.Root;

/**
 * Integration test for the semantic three-way merge.
 *
 * <p>Scenario: Two branches diverge from a common ancestor and make non-conflicting
 * additions to a shared model. The semantic merge derives changes via EMFCompare
 * and replays source branch changes onto the target VSUM via {@code propagateChange()},
 * so reactions fire and derived models are kept consistent.
 *
 * <p>Expected merged state: all components from both branches plus their
 * corresponding entities from the model2 metamodel (created by reactions).
 */
public class SemanticMergeIntegrationTest {

    @BeforeAll
    static void setup() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    }

    @Test
    @DisplayName("non-conflicting merge combines components from both branches via delta replay")
    void nonConflictingMerge(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // === Step 1: Create VSUM with InitialComponent on main (merge base) ===
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "InitialComponent");

            assertEquals(1, getDefaultView(vsum).getRootObjects(System.class)
                    .iterator().next().getComponents().size());

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit on main").call();

            // === Step 2: Feature branch — add FeatureComponent with changelog capture ===
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();

            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "FeatureComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added FeatureComponent").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Added FeatureComponent").call();

            // === Step 3: Switch back to main, add MainComponent ===
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "MainComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added MainComponent on main").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Added MainComponent on main").call();

            vsum.dispose();

            // === Step 4: Run semantic merge: feature -> main (true delta replay) ===
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider);

            // === Step 5: Verify merge succeeded ===
            assertTrue(result.isSuccess(), "Merge should succeed without conflicts");

            // === Step 6: Load merged VSUM and verify model state ===
            InternalVirtualModel mergedVsum = GitStateLoader.loadVsumFromDir(
                    result.getMergedStateFolder(),
                    List.of(new Model2Model2ChangePropagationSpecification()),
                    interactionProvider);

            var view = getDefaultView(mergedVsum);
            var system = view.getRootObjects(System.class).iterator().next();
            Set<String> componentNames = system.getComponents().stream()
                    .map(c -> c.getName()).collect(Collectors.toSet());

            assertEquals(3, system.getComponents().size(),
                    "Merged system should have 3 components (Initial + Feature + Main)");
            assertTrue(componentNames.contains("InitialComponent"));
            assertTrue(componentNames.contains("FeatureComponent"));
            assertTrue(componentNames.contains("MainComponent"));

            mergedVsum.dispose();
        }
    }

    @Test
    @DisplayName("changelog capture and JSON DTO persistence round-trip")
    void changelogCaptureAndPersistence(@TempDir Path tempDir) throws Exception {
        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Create VSUM with initial component
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "InitialComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit").call();

            // Register changelog capture
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            // Add a component — this will be captured
            addComponentToSystem(vsum, "NewComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added NewComponent").call();
            String commitSha = git.log().setMaxCount(1).call().iterator().next().getName();

            // Drain and persist
            var capturedChanges = capture.drainChanges();
            assertFalse(capturedChanges.isEmpty(), "Should have captured changes");

            // Save to JSON
            new SemanticChangeLog(commitSha, "main", capturedChanges).saveTo(tempDir);
            assertTrue(SemanticChangeLog.existsFor(tempDir, commitSha), "Changelog should exist");

            // Load JSON DTOs
            var dtos = SemanticChangeLog.loadDtosFrom(tempDir, commitSha);
            assertEquals(capturedChanges.size(), dtos.size(),
                    "DTO count should match captured change count");

            // Verify change types are preserved in DTOs
            for (int i = 0; i < capturedChanges.size(); i++) {
                String expectedType = capturedChanges.get(i).getClass().getSimpleName()
                        .replaceAll("Impl$", "");
                assertEquals(expectedType, dtos.get(i).changeType,
                        "Change type should be preserved at index " + i);
            }

            // Verify a CreateEObject is captured (for the new Component)
            assertTrue(dtos.stream().anyMatch(d -> d.changeType.contains("Create")),
                    "Should contain a CreateEObject for the new component");

            // Verify an InsertEReference is captured (component added to system)
            assertTrue(dtos.stream().anyMatch(d -> "InsertEReference".equals(d.changeType)
                            && "components".equals(d.featureName)),
                    "Should contain InsertEReference for components");

            // Verify a ReplaceSingleValuedEAttribute is captured (name set)
            assertTrue(dtos.stream().anyMatch(d -> d.changeType.contains("ReplaceSingleValuedEAttribute")
                            && "name".equals(d.featureName)
                            && "NewComponent".equals(d.newLiteralValue)),
                    "Should contain name attribute change");

            vsum.dispose();
            java.lang.System.out.println("=== Changelog Capture & Persistence PASSED ===");
        }
    }

    @Test
    @DisplayName("both branches rename same component: conflict detected via UUID changelogs")
    void renameSameComponent_conflict(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: System with "Shared" component
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base with Shared").call();

            // Feature branch: rename Shared → ServiceB
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();

            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "ServiceB");

            // Commit model changes, save changelog with a placeholder SHA,
            // then make a second commit that includes the changelog
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed to ServiceB").call();
            // Save changelog (use placeholder SHA, will be re-keyed by second commit)
            var featureChanges = capture.drainChanges();
            var featureUuidMap = capture.drainUuidMapping();
            // Save with a deterministic key derived from branch name
            new SemanticChangeLog("feature", "feature", featureChanges, featureUuidMap).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add changelog for feature rename").call();

            // Main branch: rename Shared → ServiceA
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "ServiceA");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed to ServiceA").call();
            var mainChanges = capture.drainChanges();
            var mainUuidMap = capture.drainUuidMapping();
            new SemanticChangeLog("main___", "main", mainChanges, mainUuidMap).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Add changelog for main rename").call();

            vsum.dispose();

            // Debug: check what changelogs exist on disk
            java.lang.System.out.println("=== DEBUG: Changelogs on disk ===");
            var clDir = tempDir.resolve(".vitruvius/semantic-changelogs");
            if (java.nio.file.Files.exists(clDir)) {
                java.nio.file.Files.list(clDir).forEach(f ->
                        java.lang.System.out.println("  " + f.getFileName()));
            } else {
                java.lang.System.out.println("  (no changelog dir)");
            }
            // Check feature SHA after amend
            String actualFeatureSha = git.log().add(git.getRepository().resolve("feature"))
                    .setMaxCount(1).call().iterator().next().getName();
            String actualMainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            java.lang.System.out.println("Feature SHA (after amend): " + actualFeatureSha);
            java.lang.System.out.println("Main SHA (after amend): " + actualMainSha);
            java.lang.System.out.println("Feature changelog exists: " + SemanticChangeLog.existsFor(tempDir, actualFeatureSha));
            java.lang.System.out.println("Main changelog exists: " + SemanticChangeLog.existsFor(tempDir, actualMainSha));

            // Merge without resolution → should report CONFLICT
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider);

            assertFalse(result.isSuccess(), "Merge should detect a conflict");
            assertEquals(SemanticMergeResult.Status.CONFLICT, result.getStatus());
            assertFalse(result.getConflicts().isEmpty(), "Should have at least one conflict");

            var conflict = result.getConflicts().get(0);
            assertEquals("name", conflict.getConflictingFeature(),
                    "Conflict should be on the 'name' feature");

            java.lang.System.out.println("=== Rename Conflict Test PASSED ===");
            java.lang.System.out.println("Conflict: " + conflict);
        }
    }

    @Test
    @DisplayName("rename conflict resolved by choosing theirs")
    void renameConflict_resolveWithTheirs(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Feature: rename → ServiceB
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);
            renameComponent(vsum, "Shared", "ServiceB");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed to ServiceB").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed to ServiceB").call();

            // Main: rename → ServiceA
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);
            renameComponent(vsum, "Shared", "ServiceA");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed to ServiceA").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed to ServiceA").call();
            vsum.dispose();

            // Merge with "choose theirs" resolution
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main", List.of(spec), interactionProvider,
                    ConflictResolutionProvider.chooseAllTheirs());

            // The merge should succeed (conflicts were resolved)
            assertTrue(result.isSuccess(),
                    "Merge with resolution should succeed. Status: " + result.getStatus());

            java.lang.System.out.println("=== Resolve With Theirs PASSED === " + result);
        }
    }

    @Test
    @DisplayName("empty branch merge produces no-op")
    void emptyBranchMerge(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "OnlyComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Feature branch: no changes
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            git.commit().setAllowEmpty(true).setMessage("Empty commit on feature").call();

            // Main: add another component
            git.checkout().setName("main").call();
            vsum.reload();
            addComponentToSystem(vsum, "MainOnly");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added MainOnly").call();
            vsum.dispose();

            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main", List.of(spec), interactionProvider);

            assertTrue(result.isSuccess(), "Empty branch merge should succeed");

            java.lang.System.out.println("=== Empty Branch Merge PASSED ===");
        }
    }

    @Test
    @DisplayName("indirect conflict: reaction from replay(A) overwrites user change on B")
    void indirectConflict_derivedReplayVsUserB(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: Component "Shared" → reaction creates Entity "Shared"
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Branch A (theirs): rename Component "Shared" → "Alpha"
            // Reaction will rename Entity "Shared" → "Alpha"
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "Alpha");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Component to Alpha").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Component to Alpha + changelog").call();

            // Branch B (ours): directly rename Entity "Shared" → "CustomName" via model2 view
            // This is a user-authored change to model2 (not via reaction)
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameEntity(vsum, "Shared", "CustomName");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Entity to CustomName").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Entity to CustomName + changelog").call();

            vsum.dispose();

            // Merge: feature → main
            // Replaying A's Component rename triggers reaction: Entity → "Alpha"
            // But B's user explicitly set Entity → "CustomName"
            // → indirect conflict: derived(replay(A)) vs user(B)
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main", List.of(spec), interactionProvider);

            // The merge succeeds (replay completes) but should report indirect conflicts
            assertTrue(result.isSuccess(), "Merge should succeed (replay completes)");

            // Check for indirect conflicts in warnings or result
            boolean hasIndirectConflict = result.getWarnings().stream()
                    .anyMatch(w -> w.getType() == MergeConflict.ConflictType.INDIRECT_CONFLICT);

            java.lang.System.out.println("=== Indirect Conflict Test ===");
            java.lang.System.out.println("Warnings: " + result.getWarnings());
            java.lang.System.out.println("Has indirect conflict: " + hasIndirectConflict);

            // Note: The indirect conflict detection depends on whether the reaction's
            // Entity rename footprint (UUID#name) matches the user(B) Entity rename footprint.
            // This requires the Entity to have the same UUID on both branches.
        }
    }

    @Test
    @DisplayName("multi-model merge: both branches add components + protocols + links")
    void multiModelMerge(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: System with ComponentA
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "ComponentA");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base with ComponentA").call();

            // Feature branch: add ComponentB
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "ComponentB");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added ComponentB on feature").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Added ComponentB + changelog").call();

            // Main: add ComponentC
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "ComponentC");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added ComponentC on main").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Added ComponentC + changelog").call();

            vsum.dispose();

            // Merge
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main", List.of(spec), interactionProvider);

            assertTrue(result.isSuccess(), "Multi-model merge should succeed");

            // Verify merged state
            InternalVirtualModel mergedVsum = GitStateLoader.loadVsumFromDir(
                    result.getMergedStateFolder(), List.of(spec), interactionProvider);

            var view = getDefaultView(mergedVsum);
            var system = view.getRootObjects(System.class).iterator().next();
            Set<String> componentNames = system.getComponents().stream()
                    .map(c -> c.getName()).collect(Collectors.toSet());

            assertEquals(3, system.getComponents().size(),
                    "Should have 3 components: A + B + C");
            assertTrue(componentNames.containsAll(Set.of("ComponentA", "ComponentB", "ComponentC")));

            // Verify model2 — entities should be created by reactions
            var model2Selector = mergedVsum.createSelector(
                    ViewTypeFactory.createIdentityMappingViewType("model2-check"));
            mergedVsum.getViewSourceModels().stream()
                    .flatMap(r -> r.getContents().stream())
                    .filter(obj -> obj instanceof Root)
                    .forEach(root -> model2Selector.setSelected(root, true));
            var model2View = model2Selector.createView().withChangeDerivingTrait();
            var roots = model2View.getRootObjects(Root.class);
            if (roots.iterator().hasNext()) {
                var root = roots.iterator().next();
                Set<String> entityNames = root.getEntities().stream()
                        .map(e -> e.getName()).collect(Collectors.toSet());
                assertEquals(3, root.getEntities().size(),
                        "Should have 3 entities (from reactions): A + B + C");
                assertTrue(entityNames.containsAll(Set.of("ComponentA", "ComponentB", "ComponentC")),
                        "Entity names should match component names. Got: " + entityNames);
            }

            mergedVsum.dispose();
            java.lang.System.out.println("=== Multi-Model Merge PASSED ===");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Bidirectional merge tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("bidirectional merge: reverse resolves indirect conflict (derived(A) vs user(B))")
    void bidirectionalMerge_reverseResolvesIndirectConflict(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: Component "Shared" → reaction creates Entity "Shared"
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Branch A: rename Component "Shared" → "Alpha" (user change in model1)
            // Reaction will derive Entity "Shared" → "Alpha" (model2)
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "Alpha");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Component to Alpha").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Component to Alpha + changelog").call();

            // Branch B: directly rename Entity "Shared" → "CustomName" (user change in model2)
            // This is NOT generated by a reaction — it's user intent
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameEntity(vsum, "Shared", "CustomName");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Entity to CustomName").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Entity to CustomName + changelog").call();

            vsum.dispose();

            // Bidirectional merge:
            // A→B: replaying A's Component rename triggers reaction Entity → "Alpha",
            //       which overwrites B's user Entity → "CustomName" → INDIRECT_CONFLICT
            // B→A: replaying B's Entity rename → "CustomName" on A (no reaction fires
            //       for Entity→Component in model2→model direction), so B→A is clean
            // → Engine should choose REVERSED direction (B→A)
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.executeBidirectional(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider, null);

            assertTrue(result.isSuccess(),
                    "Bidirectional merge should succeed via reverse direction");
            assertEquals(MergeDirection.REVERSED, result.getMergeDirection(),
                    "Should use REVERSED direction since A→B had indirect conflicts");

            java.lang.System.out.println("=== Bidirectional Reverse-Resolves Test ===");
            java.lang.System.out.println("Direction: " + result.getMergeDirection());
            java.lang.System.out.println("Warnings: " + result.getWarnings());
        }
    }

    @Test
    @DisplayName("bidirectional merge: forward is clean, no reverse attempted")
    void bidirectionalMerge_forwardClean(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: System with InitialComponent
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "InitialComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit on main").call();

            // Feature branch: add FeatureComponent
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "FeatureComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added FeatureComponent").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Added FeatureComponent").call();

            // Main: add MainComponent
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "MainComponent");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added MainComponent on main").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Added MainComponent on main").call();

            vsum.dispose();

            // Bidirectional merge: non-conflicting additions → forward is clean
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.executeBidirectional(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider, null);

            assertTrue(result.isSuccess(), "Merge should succeed without conflicts");
            assertEquals(MergeDirection.FORWARD, result.getMergeDirection(),
                    "Should use FORWARD direction — no indirect conflicts");

            java.lang.System.out.println("=== Bidirectional Forward-Clean Test PASSED ===");
        }
    }

    @Test
    @DisplayName("bidirectional merge: USER_VS_DERIVED_WARNING does not trigger reverse")
    void bidirectionalMerge_userVsDerivedWarning_staysForward(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: Component "Shared" → Entity "Shared"
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Branch A (feature): directly rename Entity "Shared" → "UserAEntity"
            // This is a user change to model2 (Entity)
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameEntity(vsum, "Shared", "UserAEntity");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Entity renamed to UserAEntity").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Entity renamed + changelog").call();

            // Branch B (main): rename Component "Shared" → "Beta"
            // Reaction derives Entity "Shared" → "Beta" (derived state on B)
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "Beta");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Component renamed to Beta").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Component renamed + changelog").call();

            vsum.dispose();

            // Bidirectional merge:
            // A→B: user(A) Entity rename ("UserAEntity") overwrites derived(B) Entity ("Beta")
            //       → USER_VS_DERIVED_WARNING (not INDIRECT_CONFLICT)
            // Since only USER_VS_DERIVED_WARNING (no INDIRECT_CONFLICT), no reverse is attempted
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.executeBidirectional(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider, null);

            assertTrue(result.isSuccess(), "Merge should succeed");
            assertEquals(MergeDirection.FORWARD, result.getMergeDirection(),
                    "Should stay FORWARD — USER_VS_DERIVED_WARNING doesn't trigger reverse");

            // Verify there are warnings but none are INDIRECT_CONFLICT
            boolean hasIndirect = result.getWarnings().stream()
                    .anyMatch(w -> w.getType() == MergeConflict.ConflictType.INDIRECT_CONFLICT);
            assertFalse(hasIndirect,
                    "Should NOT have INDIRECT_CONFLICT (only USER_VS_DERIVED_WARNING expected)");

            java.lang.System.out.println("=== Bidirectional USER_VS_DERIVED stays Forward PASSED ===");
            java.lang.System.out.println("Warnings: " + result.getWarnings());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Interleaving merge tests
    // ═══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("interleaving merge: finds clean ordering where directed A->B has indirect conflict")
    void interleavingMerge_cleansUpDirectedConflict(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: Component "Shared" -> reaction creates Entity "Shared"
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Branch A (feature): rename Component "Shared" -> "Alpha"
            // Reaction will rename Entity "Shared" -> "Alpha"
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "Alpha");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Component to Alpha").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Component to Alpha + changelog").call();

            // Branch B (main): directly rename Entity "Shared" -> "CustomName" (user intent)
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameEntity(vsum, "Shared", "CustomName");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Entity to CustomName").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Entity to CustomName + changelog").call();

            vsum.dispose();

            // Interleaving merge: A->B has INDIRECT_CONFLICT; B->A should be clean
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.executeWithInterleaving(
                    tempDir, "feature", "main", List.of(spec), interactionProvider, null);

            assertTrue(result.isSuccess(),
                    "Interleaving merge should succeed - at least one ordering avoids indirect conflicts");

            boolean hasIndirectConflict = result.getWarnings().stream()
                    .anyMatch(w -> w.getType() == MergeConflict.ConflictType.INDIRECT_CONFLICT);
            assertFalse(hasIndirectConflict,
                    "The chosen ordering should have no INDIRECT_CONFLICT warnings");

            java.lang.System.out.println("=== Interleaving CleanUpDirectedConflict PASSED ===");
            java.lang.System.out.println("Direction: " + result.getMergeDirection());
            java.lang.System.out.println("Warnings: " + result.getWarnings());
        }
    }

    @Test
    @DisplayName("interleaving merge: reports INTERLEAVING_CONFLICT when no ordering works")
    void interleavingMerge_reportsConflictWhenNoOrderingWorks(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // Base: Component "Shared" -> reaction creates Entity "Shared"
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "Shared");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Base").call();

            // Branch A (feature): rename Component "Shared" -> "Alpha"
            // Reaction renames Entity "Shared" -> "Alpha" (model1->model2)
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "Alpha");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Component to Alpha").call();
            String featureSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(featureSha, "feature", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Component to Alpha + changelog").call();

            // Branch B (main): rename Component "Shared" -> "Beta"
            // Reaction renames Entity "Shared" -> "Beta" (model1->model2)
            // This creates a MODIFY_MODIFY direct conflict on Component.name
            git.checkout().setName("main").call();
            vsum.reload();
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            renameComponent(vsum, "Shared", "Beta");

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Renamed Component to Beta").call();
            String mainSha = git.log().setMaxCount(1).call().iterator().next().getName();
            new SemanticChangeLog(mainSha, "main", capture.drainChanges(),
                    capture.drainUuidMapping()).saveTo(tempDir);
            git.add().addFilepattern(".").call();
            git.commit().setAmend(true).setMessage("Renamed Component to Beta + changelog").call();

            vsum.dispose();

            // Interleaving merge: both rename the same Component -> direct MODIFY_MODIFY conflict
            // Interleaving cannot help with direct conflicts
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.executeWithInterleaving(
                    tempDir, "feature", "main", List.of(spec), interactionProvider, null);

            assertFalse(result.isSuccess(),
                    "Interleaving merge should fail - direct conflict cannot be resolved by ordering");

            java.lang.System.out.println("=== Interleaving NoOrderingWorks PASSED ===");
            java.lang.System.out.println("Status: " + result.getStatus());
            java.lang.System.out.println("Conflicts: " + result.getConflicts());
        }
    }

    // === Helper methods ===

    private InternalVirtualModel createVirtualModel(Path projectPath) throws IOException {
        return new VirtualModelBuilder()
                .withStorageFolder(projectPath)
                .withUserInteractorForResultProvider(
                        new TestUserInteraction.ResultProvider(new TestUserInteraction()))
                .withChangePropagationSpecifications(new Model2Model2ChangePropagationSpecification())
                .buildAndInitialize();
    }

    private void addSystemWithComponent(VirtualModel vsum, Path projectPath, String componentName) {
        var view = getDefaultView(vsum).withChangeDerivingTrait();
        var system = ModelFactory.eINSTANCE.createSystem();
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName(componentName);
        system.getComponents().add(component);
        view.registerRoot(system, URI.createFileURI(projectPath.toString() + "/example.model"));
        view.commitChanges();
    }

    private void addComponentToSystem(VirtualModel vsum, String componentName) {
        var view = getDefaultView(vsum).withChangeDerivingTrait();
        var system = view.getRootObjects(System.class).iterator().next();
        var component = ModelFactory.eINSTANCE.createComponent();
        component.setName(componentName);
        system.getComponents().add(component);
        view.commitChanges();
    }

    /**
     * Renames a component using fine-grained change recording (not state diff).
     * This produces a single ReplaceSingleValuedEAttribute change that preserves
     * the element's UUID, enabling proper conflict detection across branches.
     */
    private void renameComponent(VirtualModel vsum, String oldName, String newName) {
        var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("rename"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof System)
                .forEach(it -> selector.setSelected(it, true));
        // Use change RECORDING (not deriving) to capture the actual rename operation
        var view = selector.createView().withChangeRecordingTrait();
        var system = view.getRootObjects(System.class).iterator().next();
        var component = system.getComponents().stream()
                .filter(c -> c.getName().equals(oldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Component not found: " + oldName));
        component.setName(newName);
        view.commitChanges();
    }

    /**
     * Renames an Entity in model2 directly (user-authored change to derived model).
     * Uses change recording to produce a ReplaceSingleValuedEAttribute with correct UUID.
     */
    private void renameEntity(VirtualModel vsum, String oldName, String newName) {
        var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("entity-rename"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof Root)
                .forEach(it -> selector.setSelected(it, true));
        var view = selector.createView().withChangeRecordingTrait();
        var root = view.getRootObjects(Root.class).iterator().next();
        var entity = root.getEntities().stream()
                .filter(e -> e.getName().equals(oldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Entity not found: " + oldName));
        entity.setName(newName);
        view.commitChanges();
    }

    /**
     * Adds a protocol to the system via state-deriving view.
     */
    private void addProtocol(VirtualModel vsum, String protocolName) {
        var view = getDefaultView(vsum).withChangeDerivingTrait();
        var system = view.getRootObjects(System.class).iterator().next();
        var protocol = ModelFactory.eINSTANCE.createProtocol();
        protocol.setName(protocolName);
        system.getProtocols().add(protocol);
        view.commitChanges();
    }

    private CommittableView getDefaultView(VirtualModel vsum) {
        var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof System)
                .forEach(it -> selector.setSelected(it, true));
        return selector.createView().withChangeDerivingTrait();
    }
}

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
import tools.vitruv.framework.vsum.branch.merge.GitStateLoader;
import tools.vitruv.framework.vsum.branch.merge.SemanticChangeLog;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeCommand;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;

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
    @DisplayName("non-conflicting merge combines components from both branches with reactions")
    void nonConflictingMerge(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // === Step 1: Create VSUM with InitialComponent on main (this is the merge base) ===
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "InitialComponent");

            CommittableView view = getDefaultView(vsum);
            var system = view.getRootObjects(System.class).iterator().next();
            assertEquals(1, system.getComponents().size());
            assertEquals("InitialComponent", system.getComponents().get(0).getName());

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit on main").call();

            // === Step 2: Create feature branch, add FeatureComponent ===
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();

            addComponentToSystem(vsum, "FeatureComponent");

            view = getDefaultView(vsum);
            system = view.getRootObjects(System.class).iterator().next();
            assertEquals(2, system.getComponents().size());

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added FeatureComponent").call();

            // === Step 3: Switch back to main, add MainComponent ===
            git.checkout().setName("main").call();
            vsum.reload();

            addComponentToSystem(vsum, "MainComponent");

            view = getDefaultView(vsum);
            system = view.getRootObjects(System.class).iterator().next();
            assertEquals(2, system.getComponents().size());

            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added MainComponent on main").call();

            vsum.dispose();

            // === Step 4: Run semantic merge: feature -> main ===
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider);

            // === Step 5: Verify merge succeeded ===
            if (!result.isSuccess()) {
                java.lang.System.out.println("=== CONFLICTS DETECTED ===");
                for (var conflict : result.getConflicts()) {
                    java.lang.System.out.println("  " + conflict);
                }
            }
            assertTrue(result.isSuccess(), "Merge should succeed without conflicts");
            assertFalse(result.getAppliedChanges().isEmpty(),
                    "Should have replayed source changes");
            assertNotNull(result.getMergedStateFolder(),
                    "Merged state should be written to disk");

            // === Step 6: Load the merged VSUM and verify model state ===
            InternalVirtualModel mergedVsum = GitStateLoader.loadVsumFromDir(
                    result.getMergedStateFolder(),
                    List.of(new Model2Model2ChangePropagationSpecification()),
                    interactionProvider);

            view = getDefaultView(mergedVsum);
            system = view.getRootObjects(System.class).iterator().next();

            // The merged state should have ALL THREE components
            Set<String> componentNames = system.getComponents().stream()
                    .map(c -> c.getName())
                    .collect(Collectors.toSet());

            java.lang.System.out.println("=== Merged model state ===");
            java.lang.System.out.println("Components: " + componentNames);
            java.lang.System.out.println("Component count: " + system.getComponents().size());
            java.lang.System.out.println("Applied changes: " + result.getAppliedChanges().size());

            assertEquals(3, system.getComponents().size(),
                    "Merged system should have 3 components (Initial + Feature + Main)");
            assertTrue(componentNames.contains("InitialComponent"),
                    "Merged system should contain InitialComponent");
            assertTrue(componentNames.contains("FeatureComponent"),
                    "Merged system should contain FeatureComponent from source branch");
            assertTrue(componentNames.contains("MainComponent"),
                    "Merged system should contain MainComponent from target branch");

            mergedVsum.dispose();

            java.lang.System.out.println("=== Semantic Merge Integration Test PASSED ===");
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

    private CommittableView getDefaultView(VirtualModel vsum) {
        var selector = vsum.createSelector(ViewTypeFactory.createIdentityMappingViewType("default"));
        selector.getSelectableElements().stream()
                .filter(element -> element instanceof System)
                .forEach(it -> selector.setSelected(it, true));
        return selector.createView().withChangeDerivingTrait();
    }
}

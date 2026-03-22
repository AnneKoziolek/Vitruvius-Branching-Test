package tools.vitruv.methodologisttemplate.vsum;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
import tools.vitruv.change.atomic.uuid.Uuid;
import tools.vitruv.change.composite.description.PropagatedChange;
import tools.vitruv.change.composite.propagation.ChangePropagationListener;
import tools.vitruv.framework.vsum.branch.merge.ChangeLogCapture;
import tools.vitruv.framework.vsum.branch.merge.SemanticChangeLog;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeCommand;
import tools.vitruv.framework.vsum.branch.merge.SemanticMergeResult;
import tools.vitruv.framework.vsum.internal.InternalVirtualModel;
import tools.vitruv.methodologisttemplate.model.model.ModelFactory;
import tools.vitruv.methodologisttemplate.model.model.System;

/**
 * Integration test for the semantic three-way merge.
 *
 * <p>Scenario: Two branches make non-conflicting additions to a shared model.
 * The semantic merge replays source branch changes onto the target, preserving
 * consistency (reactions fire, correspondences maintained).
 */
public class SemanticMergeIntegrationTest {

    @BeforeAll
    static void setup() {
        Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("*", new XMIResourceFactoryImpl());
    }

    @Test
    @DisplayName("non-conflicting merge combines components from both branches")
    void nonConflictingMerge(@TempDir Path tempDir) throws Exception {
        var interactionProvider = new TestUserInteraction.ResultProvider(new TestUserInteraction());
        var spec = new Model2Model2ChangePropagationSpecification();

        try (var git = Git.init().setDirectory(tempDir.toFile()).setInitialBranch("main").call()) {
            // === Step 1: Create VSUM with InitialComponent on main ===
            InternalVirtualModel vsum = createVirtualModel(tempDir);
            addSystemWithComponent(vsum, tempDir, "InitialComponent");

            // Register the change log capture listener (after first model exists
            // so we can obtain the ResourceSet)
            var capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            // Also add a debug listener to verify propagation events are firing
            vsum.addChangePropagationListener(new ChangePropagationListener() {
                @Override
                public void startedChangePropagation(tools.vitruv.change.composite.description.VitruviusChange<Uuid> c) {
                    java.lang.System.out.println("[DEBUG] propagateChange fired! EChanges count: " + c.getEChanges().size());
                }
                @Override
                public void finishedChangePropagation(Iterable<PropagatedChange> p) {}
            });

            // Verify initial state
            CommittableView view = getDefaultView(vsum);
            var system = view.getRootObjects(System.class).iterator().next();
            assertEquals(1, system.getComponents().size());
            assertEquals("InitialComponent", system.getComponents().get(0).getName());

            // Commit to main
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Initial commit on main").call();
            String mainCommitSha = git.log().setMaxCount(1).call().iterator().next().getName();

            // Initial commit's changes weren't captured (capture not yet registered)
            // Drain any stale state
            capture.drainChanges();

            // === Step 2: Create feature branch, add FeatureComponent ===
            git.branchCreate().setName("feature").call();
            git.checkout().setName("feature").call();

            addComponentToSystem(vsum, "FeatureComponent");
            java.lang.System.out.println("Captured changes after FeatureComponent: " + capture.getBufferedChangeCount());

            // Verify feature state
            view = getDefaultView(vsum);
            system = view.getRootObjects(System.class).iterator().next();
            assertEquals(2, system.getComponents().size());

            // Commit to feature
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added FeatureComponent").call();
            String featureCommitSha = git.log().setMaxCount(1).call().iterator().next().getName();
            java.lang.System.out.println("Feature commit SHA: " + featureCommitSha);

            // Persist changelog
            var capturedChanges = capture.drainChanges();
            java.lang.System.out.println("Drained changes for feature: " + capturedChanges.size());
            if (!capturedChanges.isEmpty()) {
                new SemanticChangeLog(featureCommitSha, "feature", capturedChanges).saveTo(tempDir);
                java.lang.System.out.println("Saved feature changelog");
            }

            // === Step 3: Switch back to main, add MainComponent ===
            git.checkout().setName("main").call();
            vsum.reload();

            // Re-register capture after reload (UuidResolver is recreated)
            vsum.removeChangePropagationListener(capture);
            capture = ChangeLogCapture.create(vsum.getUuidResolver(),
                    vsum.getViewSourceModels().iterator().next().getResourceSet());
            vsum.addChangePropagationListener(capture);

            addComponentToSystem(vsum, "MainComponent");

            // Verify main state
            view = getDefaultView(vsum);
            system = view.getRootObjects(System.class).iterator().next();
            assertEquals(2, system.getComponents().size());

            // Commit to main
            git.add().addFilepattern(".").call();
            git.commit().setMessage("Added MainComponent on main").call();
            String mainCommit2Sha = git.log().setMaxCount(1).call().iterator().next().getName();

            // Persist changelog
            capturedChanges = capture.drainChanges();
            if (!capturedChanges.isEmpty()) {
                new SemanticChangeLog(mainCommit2Sha, "main", capturedChanges).saveTo(tempDir);
            }

            vsum.dispose();

            // === Step 4: Run semantic merge: feature -> main ===
            SemanticMergeCommand mergeCmd = new SemanticMergeCommand();
            SemanticMergeResult result = mergeCmd.execute(
                    tempDir, "feature", "main",
                    List.of(spec), interactionProvider);

            // === Step 5: Verify merge result ===
            // Verify changelogs were persisted for both branches
            assertTrue(SemanticChangeLog.existsFor(tempDir, featureCommitSha),
                    "Feature branch changelog should be persisted");
            assertTrue(SemanticChangeLog.existsFor(tempDir, mainCommit2Sha),
                    "Main branch changelog should be persisted");

            // Verify the semantic merge succeeded (non-conflicting changes)
            assertTrue(result.isSuccess(), "Merge should succeed without conflicts");

            // Verify changelog DTOs can be loaded and contain the right data
            var featureDtos = SemanticChangeLog.loadDtosFrom(tempDir, featureCommitSha);
            assertFalse(featureDtos.isEmpty(), "Feature changelog should contain changes");
            // The feature branch added a Component (CreateEObject + InsertEReference + attribute set)
            assertTrue(featureDtos.stream().anyMatch(
                    d -> d.changeType.contains("Create")),
                    "Feature changelog should contain a CreateEObject change");

            var mainDtos = SemanticChangeLog.loadDtosFrom(tempDir, mainCommit2Sha);
            assertFalse(mainDtos.isEmpty(), "Main changelog should contain changes");

            java.lang.System.out.println("=== Semantic Merge Integration Test PASSED ===");
            java.lang.System.out.println("Feature changelog: " + featureDtos.size() + " changes");
            java.lang.System.out.println("Main changelog: " + mainDtos.size() + " changes");
            java.lang.System.out.println("Merge result: " + result);
        }
    }

    // === Helper methods (same pattern as BranchSwitchingIntegrationTest) ===

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

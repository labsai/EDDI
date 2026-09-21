/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.modules.rag.bootstrap.RagModule;
import ai.labs.eddi.configs.workflows.rest.RestWorkflowStepStore;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.modules.rag.RagTask;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every workflow step type EDDI documents must be a REGISTERED lifecycle
 * extension.
 *
 * <p>
 * {@code WorkflowStoreClientLibrary} resolves a step by {@code URI.getHost()}
 * against the {@code @LifecycleExtensions} map and throws
 * {@code UnrecognizedExtensionException} when the key is absent, so an
 * unregistered type makes every workflow using it undeployable. This is not
 * hypothetical: {@code ai.labs.rag} was documented in {@code docs/rag.md} and
 * offered by the Manager (whose MSW fixture hard-codes the step list) while no
 * module registered it, so RAG workflow steps could not be deployed at all.
 *
 * <p>
 * This asserts against the real CDI wiring through
 * {@link RestWorkflowStepStore} — the same bean that feeds the Manager's step
 * chooser — so it fails if {@link RagModule} is removed. The mechanics of the
 * deploy-time rejection are covered separately by
 * {@code RagWorkflowDeploymentTest}.
 */
@QuarkusTest
class RagWorkflowExtensionIT {

    /**
     * Step types EDDI documents in AGENTS.md §5.5 and {@code docs/rag.md}. Add a
     * row here whenever a new workflow step type ships.
     */
    private static final List<String> DOCUMENTED_STEP_TYPES = List.of(
            "ai.labs.parser",
            "ai.labs.behavior",
            "ai.labs.property",
            "ai.labs.httpcalls",
            "ai.labs.output",
            "ai.labs.llm",
            "ai.labs.mcpcalls",
            "ai.labs.templating",
            "ai.labs.rag");

    @Inject
    RestWorkflowStepStore workflowStepStore;

    private Set<String> offeredStepTypes() {
        return workflowStepStore.getWorkflowSteps(null).stream()
                .map(ExtensionDescriptor::getType)
                .map(TaskId::name)
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("the RAG step is offered, so a workflow can declare eddi://ai.labs.rag")
    void ragStepIsRegistered() {
        Set<String> offered = offeredStepTypes();

        assertTrue(offered.contains(RagTask.ID),
                "ai.labs.rag is not a registered lifecycle extension — every workflow with a RAG step fails to "
                        + "deploy with UnrecognizedExtensionException, and the Manager never offers the step. "
                        + "Offered: " + offered);
    }

    @Test
    @DisplayName("the registered key matches the host of the step URI agents actually write")
    void registeredKeyMatchesStepUriHost() {
        // A workflow step declares "eddi://ai.labs.rag" and the library keys off the
        // URI host; RagContextProvider discovers steps by that same literal. If the
        // registration key and the documented URI ever diverge, deployment breaks.
        String hostOfDocumentedStepUri = URI.create("eddi://ai.labs.rag").getHost();

        assertTrue(offeredStepTypes().contains(hostOfDocumentedStepUri),
                "no lifecycle extension registered under '" + hostOfDocumentedStepUri + "'");
    }

    @Test
    @DisplayName("every documented workflow step type is offered")
    void allDocumentedStepTypesAreRegistered() {
        Set<String> offered = offeredStepTypes();

        List<String> missing = DOCUMENTED_STEP_TYPES.stream()
                .filter(type -> !offered.contains(type))
                .toList();

        assertTrue(missing.isEmpty(),
                "documented workflow step types are not registered lifecycle extensions: " + missing
                        + " — workflows using them cannot be deployed. Offered: " + offered);
    }

    @Test
    @DisplayName("the RAG descriptor carries the uri field the Manager renders")
    void ragDescriptorIsUsable() {
        ExtensionDescriptor ragDescriptor = workflowStepStore.getWorkflowSteps(null).stream()
                .filter(descriptor -> RagTask.ID.equals(descriptor.getType().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no RAG descriptor offered"));

        assertFalse(ragDescriptor.getDisplayName() == null || ragDescriptor.getDisplayName().isBlank(),
                "RAG step needs a display name for the Manager's chooser");
        assertTrue(ragDescriptor.getConfigs().containsKey("uri"),
                "RAG step must declare the 'uri' config field, got: " + ragDescriptor.getConfigs().keySet());
    }
}

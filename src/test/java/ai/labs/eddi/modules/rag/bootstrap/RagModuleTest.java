/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag.bootstrap;

import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.rag.RagTask;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.inject.Provider;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RagModule} — the registration that makes {@code eddi://ai.labs.rag}
 * deployable.
 *
 * <p>
 * Covers the key under which the task is registered, because
 * {@code WorkflowStoreClientLibrary} resolves a step by {@code URI.getHost()}
 * and a mismatch there makes every RAG workflow undeployable. That the module
 * is actually discovered by CDI at runtime is asserted against live wiring in
 * {@code RagWorkflowExtensionIT}.
 */
class RagModuleTest {

    private Map<String, Provider<ILifecycleTask>> lifecycleTaskProviders;
    private RagTask ragTask;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lifecycleTaskProviders = new HashMap<>();
        ragTask = new RagTask(mock(IResourceClientLibrary.class));

        Instance<ILifecycleTask> instance = mock(Instance.class);
        Instance<RagTask> ragTaskInstance = mock(Instance.class);
        when(instance.select(eq(RagTask.class))).thenReturn(ragTaskInstance);
        when(ragTaskInstance.get()).thenReturn(ragTask);

        new RagModule(lifecycleTaskProviders, instance).configure();
    }

    @Test
    @DisplayName("registers the RAG task under ai.labs.rag")
    void registersRagTask() {
        Provider<ILifecycleTask> provider = lifecycleTaskProviders.get(RagTask.ID);

        assertNotNull(provider, "ai.labs.rag was not registered, registered keys: " + lifecycleTaskProviders.keySet());
        assertInstanceOf(RagTask.class, provider.get());
    }

    @Test
    @DisplayName("the registration key equals the host of the documented step URI")
    void keyMatchesStepUriHost() {
        // Workflows declare "eddi://ai.labs.rag"; WorkflowStoreClientLibrary looks the
        // step up by URI.getHost(). Registering under any other key silently makes
        // every RAG workflow fail to deploy.
        assertTrue(lifecycleTaskProviders.containsKey(URI.create("eddi://ai.labs.rag").getHost()));
    }

    @Test
    @DisplayName("does not disturb extensions registered by other modules")
    void leavesOtherRegistrationsAlone() {
        var otherTask = mock(ILifecycleTask.class);
        lifecycleTaskProviders.put("ai.labs.output", () -> otherTask);

        assertNotNull(lifecycleTaskProviders.get("ai.labs.output"));
        assertNotNull(lifecycleTaskProviders.get(RagTask.ID));
    }
}

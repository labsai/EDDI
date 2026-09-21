/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The v6 step aliases ({@code ai.labs.rules}, {@code ai.labs.apicalls}) are
 * registered as a second key on the same provider — see {@code RulesModule} and
 * {@code ApiCallsModule}. The extension list must still show each step once.
 */
class RestWorkflowStepStoreTest {

    private final ExtensionDescriptor behaviorDescriptor = new ExtensionDescriptor(new TaskId("ai.labs.behavior"));
    private final ExtensionDescriptor outputDescriptor = new ExtensionDescriptor(new TaskId("ai.labs.output"));
    private RestWorkflowStepStore store;

    @BeforeEach
    void setUp() {
        Provider<ILifecycleTask> behavior = providerOf(behaviorDescriptor);
        Provider<ILifecycleTask> output = providerOf(outputDescriptor);

        Map<String, Provider<ILifecycleTask>> extensions = new LinkedHashMap<>();
        extensions.put("ai.labs.behavior", behavior);
        extensions.put("ai.labs.rules", behavior);
        extensions.put("ai.labs.output", output);
        store = new RestWorkflowStepStore(extensions);
    }

    @Test
    @DisplayName("an aliased step is listed once")
    void aliasedStepListedOnce() {
        assertEquals(List.of(behaviorDescriptor, outputDescriptor), store.getWorkflowSteps(null));
        assertEquals(List.of(behaviorDescriptor, outputDescriptor), store.getWorkflowSteps(""));
    }

    @Test
    @DisplayName("a filter matching only the alias name still finds the step")
    void filterOnAliasFindsStep() {
        assertEquals(List.of(behaviorDescriptor), store.getWorkflowSteps("rules"));
        assertEquals(List.of(behaviorDescriptor), store.getWorkflowSteps("ai.labs"
                + ".behavior"));
        assertEquals(List.of(outputDescriptor), store.getWorkflowSteps("output"));
    }

    private static Provider<ILifecycleTask> providerOf(ExtensionDescriptor descriptor) {
        ILifecycleTask task = mock(ILifecycleTask.class);
        when(task.getExtensionDescriptor()).thenReturn(descriptor);
        return () -> task;
    }
}

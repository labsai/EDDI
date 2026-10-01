/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RagTask} — the workflow-step binding for a knowledge base.
 */
class RagTaskTest {

    private static final String KB_URI = "eddi://ai.labs.rag/ragstore/rags/5a8b1c2d3e4f5a6b7c8d9e0f?version=1";

    private IResourceClientLibrary resourceClientLibrary;
    private RagTask task;

    @BeforeEach
    void setUp() {
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        task = new RagTask(resourceClientLibrary);
    }

    private static Map<String, Object> config(String uri) {
        Map<String, Object> config = new HashMap<>();
        config.put("uri", uri);
        return config;
    }

    @Test
    @DisplayName("id is ai.labs.rag — the key WorkflowStoreClientLibrary looks a step up by")
    void idMatchesStepUriHost() {
        assertEquals("ai.labs.rag", task.getId().name());
        // A workflow step declares eddi://ai.labs.rag; the library resolves it via
        // URI.getHost(). If these ever diverge, every RAG workflow fails to deploy.
        assertEquals(task.getId().name(), URI.create("eddi://ai.labs.rag").getHost());
    }

    @Test
    @DisplayName("type is a lifecycle stage name, not the step URI")
    void typeIsStageNameNotUri() {
        assertEquals("rag", task.getType());
        // Guards the mistake this task shipped with on the original branch: returning
        // "eddi://ai.labs.rag" here matches no stage filter used for partial
        // lifecycle execution.
        assertFalse(task.getType().startsWith("eddi://"));
    }

    @Test
    @DisplayName("configure resolves the knowledge base and returns it as the component")
    void configureResolvesKnowledgeBase() throws Exception {
        var ragConfig = new RagConfiguration();
        ragConfig.setName("product-docs");
        when(resourceClientLibrary.getResource(eq(URI.create(KB_URI)), eq(RagConfiguration.class)))
                .thenReturn(ragConfig);

        Object component = task.configure(config(KB_URI), Map.of());

        assertSame(ragConfig, component);
        verify(resourceClientLibrary).getResource(eq(URI.create(KB_URI)), eq(RagConfiguration.class));
    }

    @Test
    @DisplayName("configure rejects a missing uri without hitting the resource store")
    void configureRejectsMissingUri() {
        var e = assertThrows(WorkflowConfigurationException.class, () -> task.configure(Map.of(), Map.of()));

        assertTrue(e.getMessage().contains("uri"), "message should name the missing field: " + e.getMessage());
        verifyNoInteractions(resourceClientLibrary);
    }

    @Test
    @DisplayName("configure rejects a blank uri")
    void configureRejectsBlankUri() {
        assertThrows(WorkflowConfigurationException.class, () -> task.configure(config("   "), Map.of()));
        verifyNoInteractions(resourceClientLibrary);
    }

    @Test
    @DisplayName("configure tolerates a null configuration map")
    void configureRejectsNullConfiguration() {
        assertThrows(WorkflowConfigurationException.class, () -> task.configure(null, Map.of()));
        verifyNoInteractions(resourceClientLibrary);
    }

    @Test
    @DisplayName("configure surfaces an unresolvable knowledge base as a workflow configuration error")
    void configureWrapsServiceException() throws Exception {
        when(resourceClientLibrary.getResource(eq(URI.create(KB_URI)), eq(RagConfiguration.class)))
                .thenThrow(new ServiceException("knowledge base not found"));

        var e = assertThrows(WorkflowConfigurationException.class, () -> task.configure(config(KB_URI), Map.of()));

        assertTrue(e.getMessage().contains(KB_URI), "message should name the URI: " + e.getMessage());
        assertTrue(e.getMessage().contains("knowledge base not found"),
                "message should carry the cause: " + e.getMessage());
    }

    @Test
    @DisplayName("configure rejects a malformed uri")
    void configureRejectsMalformedUri() {
        assertThrows(WorkflowConfigurationException.class,
                () -> task.configure(config("eddi://ai.labs.rag/ rags/ x"), Map.of()));
    }

    @Test
    @DisplayName("execute is a no-op — retrieval belongs to the LLM task")
    void executeDoesNotTouchMemory() throws Exception {
        var memory = mock(IConversationMemory.class);

        task.execute(memory, new RagConfiguration());

        verifyNoInteractions(memory);
        verify(resourceClientLibrary, never()).getResource(any(), any());
    }

    @Test
    @DisplayName("descriptor exposes the uri field so the Manager can render the step")
    void descriptorExposesUriField() {
        ExtensionDescriptor descriptor = task.getExtensionDescriptor();

        assertEquals(RagTask.TASK_ID, descriptor.getType());
        assertNotNull(descriptor.getDisplayName());
        var uriConfig = descriptor.getConfigs().get("uri");
        assertNotNull(uriConfig, "descriptor must declare the 'uri' config field");
        assertEquals(ExtensionDescriptor.FieldType.URI, uriConfig.getFieldType());
    }
}

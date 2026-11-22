/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The workflow side of the document-index separation: a workflow may not use a
 * document index as a RAG step, whichever way it is written.
 */
@DisplayName("WorkflowStore — no document index as a RAG step")
@SuppressWarnings("unchecked")
class WorkflowStoreDocumentIndexTest {

    private static final String INDEX = "65f0aa11bb22cc33dd44ee77";
    private static final String ORDINARY = "65f0aa11bb22cc33dd44ee55";
    private static final String MISSING = "65f0aa11bb22cc33dd44ee99";

    private IResourceStorage<WorkflowConfiguration> resourceStorage;
    private IRagStore ragStore;
    private WorkflowStore store;

    @BeforeEach
    void setUp() throws Exception {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        resourceStorage = mock(IResourceStorage.class);
        when(storageFactory.create(eq("workflows"), eq(documentBuilder), eq(WorkflowConfiguration.class), any(String[].class)))
                .thenReturn(resourceStorage);
        IResourceClientLibrary resourceClientLibrary = mock(IResourceClientLibrary.class);
        store = new WorkflowStore(storageFactory, documentBuilder, mock(IDocumentDescriptorStore.class), () -> resourceClientLibrary);

        ragStore = mock(IRagStore.class);
        store.useRagStore(() -> ragStore);
        when(ragStore.read(INDEX, 1)).thenReturn(rag(true));
        when(ragStore.read(ORDINARY, 1)).thenReturn(rag(null));
        when(ragStore.read(MISSING, 1)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(1);
        when(ragStore.getCurrentResourceId(INDEX)).thenReturn(current);
    }

    private static RagConfiguration rag(Boolean documentIndex) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setStoreType("pgvector");
        config.setStoreNamespace("id");
        config.setDocumentIndex(documentIndex);
        return config;
    }

    private static WorkflowConfiguration workflowWithRagStep(String uri) {
        var parser = new WorkflowConfiguration.WorkflowStep();
        parser.setType(URI.create("eddi://ai.labs.parser"));
        parser.setConfig(new HashMap<>(Map.of("uri", "eddi://ai.labs.parser/parserstore/parsers/" + ORDINARY + "?version=1")));
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(new HashMap<>(Map.of("uri", uri)));
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(new ArrayList<>(List.of(parser, step)));
        return workflow;
    }

    @Test
    @DisplayName("create refuses a RAG step that names a document index, before anything is stored")
    void createRefusesADocumentIndexStep() throws Exception {
        var workflow = workflowWithRagStep("eddi://ai.labs.rag/ragstore/rags/" + INDEX + "?version=1");

        var refused = assertThrows(IllegalArgumentException.class, () -> store.create(workflow));
        assertTrue(refused.getMessage().contains("workflowSteps[1]") && refused.getMessage().contains("document index"), refused.getMessage());
        verify(resourceStorage, never()).newResource(any());
    }

    @Test
    @DisplayName("update refuses it too")
    void updateRefusesADocumentIndexStep() throws Exception {
        var workflow = workflowWithRagStep("eddi://ai.labs.rag/ragstore/rags/" + INDEX + "?version=1");

        assertThrows(IllegalArgumentException.class, () -> store.update("65f0aa11bb22cc33dd44ee10", 1, workflow));
        verify(resourceStorage, never()).store(any());
    }

    @Test
    @DisplayName("an unversioned step URI is judged on the current version")
    void unversionedReferenceUsesTheCurrentVersion() {
        var workflow = workflowWithRagStep("eddi://ai.labs.rag/ragstore/rags/" + INDEX);

        assertThrows(IllegalArgumentException.class, () -> store.refuseDocumentIndexSteps(workflow));
    }

    @Test
    @DisplayName("an ordinary knowledge base, or one that does not exist, is not refused here")
    void ordinaryOrMissingKnowledgeBaseIsAccepted() {
        assertDoesNotThrow(() -> store.refuseDocumentIndexSteps(workflowWithRagStep("eddi://ai.labs.rag/ragstore/rags/" + ORDINARY + "?version=1")));
        assertDoesNotThrow(() -> store.refuseDocumentIndexSteps(workflowWithRagStep("eddi://ai.labs.rag/ragstore/rags/" + MISSING + "?version=1")));
    }
}

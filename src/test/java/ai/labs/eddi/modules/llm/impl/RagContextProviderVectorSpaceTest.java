/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import ai.labs.eddi.modules.rag.VectorSpaceMismatchException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retrieval from a knowledge base whose vector store holds another embedding
 * model's vectors gives the agent no context — and says why in the trace —
 * instead of context ranked by meaningless scores.
 */
@DisplayName("RAG retrieval and the vector space")
class RagContextProviderVectorSpaceTest {

    private static final String KB_ID = "65f0aa11bb22cc33dd44ee55";

    private EmbeddingStoreFactory embeddingStoreFactory;
    private IDataFactory dataFactory;
    private RagContextProvider provider;
    private IConversationMemory memory;
    private IResourceClientLibrary resourceClientLibrary;
    private VectorSpaceGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        var embeddingModelFactory = mock(EmbeddingModelFactory.class);
        embeddingStoreFactory = mock(EmbeddingStoreFactory.class);
        dataFactory = mock(IDataFactory.class);
        @SuppressWarnings("unchecked")
        IData<Object> data = mock(IData.class);
        lenient().when(dataFactory.createData(anyString(), any())).thenReturn(data);

        EmbeddingModel model = mock(EmbeddingModel.class);
        lenient().when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1f, 0f})));
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(model);
        var store = new InMemoryEmbeddingStore<TextSegment>();
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("a fact about apples"));
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(store);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.rag/ragstore/rags/" + KB_ID + "?version=1"));
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(List.of(step));
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-vs?version=1")));
        when(agentStore.read("agent-vs", 1)).thenReturn(agent);
        when(workflowStore.read("wf-vs", 1)).thenReturn(workflow);

        provider = new RagContextProvider(agentStore, workflowStore, resourceClientLibrary, embeddingModelFactory, embeddingStoreFactory,
                dataFactory);
        guard = mock(VectorSpaceGuard.class);
        provider.vectorSpaceGuard = guard;

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IConversationMemory.IWritableConversationStep.class));
        when(memory.getAgentId()).thenReturn("agent-vs");
        when(memory.getAgentVersion()).thenReturn(1);

        var config = new RagConfiguration();
        config.setName("alpha");
        config.setStoreType("in-memory");
        config.setMaxResults(10);
        config.setMinScore(0.0);
        when(resourceClientLibrary.getResource(any(URI.class), eq(RagConfiguration.class))).thenReturn(config);
    }

    @AfterEach
    void tearDown() {
        WorkflowTraversal.clearCache();
    }

    private String retrieve() {
        var task = new LlmConfiguration.Task();
        task.setEnableWorkflowRag(true);
        return provider.retrieveContext(memory, task, "apples");
    }

    @Test
    @DisplayName("a mismatch yields no context, never embeds the query, and the trace says why")
    @SuppressWarnings("unchecked")
    void mismatchYieldsNoContext() {
        doThrow(new VectorSpaceMismatchException("holds emb-8, configured other-8")).when(guard).requireCompatible(eq(KB_ID), any());

        assertNull(retrieve());

        verify(embeddingStoreFactory, never()).getOrCreate(anyString(), any());
        ArgumentCaptor<Object> trace = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory).createData(eq("rag:trace:default"), trace.capture());
        Map<String, Object> entry = ((List<Map<String, Object>>) trace.getValue()).get(0);
        assertEquals("alpha", entry.get("kb"));
        assertTrue(String.valueOf(entry.get("error")).contains("holds emb-8, configured other-8"), String.valueOf(entry));
    }

    @Test
    @DisplayName("a matching space retrieves as before")
    void matchRetrieves() {
        String context = retrieve();

        assertTrue(context != null && context.contains("a fact about apples"), context);
        verify(guard).requireCompatible(eq(KB_ID), any(RagConfiguration.class));
    }
}

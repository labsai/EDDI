/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding I3, read side — {@code chunkStrategy} is an ingestion-time setting. A
 * knowledge base whose documents are already embedded must keep grounding the
 * agent no matter what that field says; an unimplemented value is a warning in
 * the audit trace, never a silent blackout of the knowledge base.
 * <p>
 * Retrieval used to call {@code RagConfiguration.validate()} and
 * {@code continue} on failure, which made such a knowledge base contribute zero
 * chunks and — when it was the only one — made {@code retrieveContext} return
 * {@code null}, so the LLM answered with no retrieved context at all and
 * nothing surfaced to the user.
 */
class RagContextProviderChunkStrategyTest {

    private IRestAgentStore restAgentStore;
    private IRestWorkflowStore restWorkflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private EmbeddingModelFactory embeddingModelFactory;
    private EmbeddingStoreFactory embeddingStoreFactory;
    private IDataFactory dataFactory;
    private IConversationMemory memory;
    private IConversationMemory.IWritableConversationStep currentStep;
    private RagContextProvider ragContextProvider;

    @BeforeEach
    void setUp() {
        WorkflowTraversal.clearCache();
        restAgentStore = mock(IRestAgentStore.class);
        restWorkflowStore = mock(IRestWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        embeddingModelFactory = mock(EmbeddingModelFactory.class);
        embeddingStoreFactory = mock(EmbeddingStoreFactory.class);
        dataFactory = mock(IDataFactory.class);

        ragContextProvider = new RagContextProvider(
                restAgentStore, restWorkflowStore, resourceClientLibrary,
                embeddingModelFactory, embeddingStoreFactory, dataFactory);

        memory = mock(IConversationMemory.class);
        currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getAgentVersion()).thenReturn(1);

        @SuppressWarnings("unchecked")
        IData<Object> mockData = mock(IData.class);
        lenient().when(dataFactory.createData(anyString(), any())).thenReturn(mockData);
    }

    @AfterEach
    void tearDown() {
        WorkflowTraversal.clearCache();
    }

    @Test
    @DisplayName("a KB stored with the legacy 'paragraph' strategy still grounds the answer")
    void legacyChunkStrategyStillRetrieves() {
        setupWorkflowWithSuccessfulRetrieval("legacy-kb", "paragraph", "Warranty lasts 24 months");

        var task = new LlmConfiguration.Task();
        task.setId("task1");
        task.setEnableWorkflowRag(true);

        String result = ragContextProvider.retrieveContext(memory, task, "how long is the warranty?");

        assertNotNull(result, "an ingestion-time setting must never blank out retrieval");
        assertTrue(result.contains("Warranty lasts 24 months"), "retrieved chunk must reach the prompt: " + result);
        assertTrue(result.contains("legacy-kb"));

        List<Map<String, Object>> trace = trace("rag:trace:task1");
        assertEquals(2, trace.size(), "expected one warning entry plus the retrieval entry: " + trace);
        assertTrue(String.valueOf(trace.get(0).get("warning")).contains("paragraph"),
                "the unusable setting must be traced as a warning: " + trace.get(0));
        assertNull(trace.get(0).get("error"), "an ingestion-time setting is not a retrieval error");
        assertEquals("legacy-kb", trace.get(1).get("kb"));
        assertEquals(1, trace.get(1).get("retrievedCount"), "the KB must actually contribute its chunk");
    }

    @Test
    @DisplayName("an unknown chunkStrategy is warned about, not silently dropped")
    void unknownChunkStrategyStillRetrieves() {
        setupWorkflowWithSuccessfulRetrieval("typo-kb", "paragrpah", "Support is available 24/7");

        var task = new LlmConfiguration.Task();
        task.setId("task1");
        task.setEnableWorkflowRag(true);

        String result = ragContextProvider.retrieveContext(memory, task, "when is support available?");

        assertNotNull(result);
        assertTrue(result.contains("Support is available 24/7"));
        assertEquals(2, trace("rag:trace:task1").size());
    }

    @Test
    @DisplayName("a supported chunkStrategy produces no warning entry")
    void supportedChunkStrategyProducesNoWarning() {
        setupWorkflowWithSuccessfulRetrieval("clean-kb", "recursive", "Returns are free");

        var task = new LlmConfiguration.Task();
        task.setId("task1");
        task.setEnableWorkflowRag(true);

        String result = ragContextProvider.retrieveContext(memory, task, "returns?");

        assertNotNull(result);
        List<Map<String, Object>> trace = trace("rag:trace:task1");
        assertEquals(1, trace.size(), "a valid KB must produce exactly the retrieval trace entry: " + trace);
        assertFalse(trace.get(0).containsKey("warning"));
        assertEquals(1, trace.get(0).get("retrievedCount"));
    }

    // ==================== Helpers ====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> trace(String traceKey) {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory, atLeastOnce()).createData(keys.capture(), values.capture());

        var allKeys = keys.getAllValues();
        for (int i = allKeys.size() - 1; i >= 0; i--) {
            if (traceKey.equals(allKeys.get(i))) {
                return (List<Map<String, Object>>) values.getAllValues().get(i);
            }
        }
        throw new AssertionError("no audit trace was stored under " + traceKey);
    }

    @SuppressWarnings("unchecked")
    private void setupWorkflowWithSuccessfulRetrieval(String kbName, String chunkStrategy, String contentText) {
        var ragConfig = new RagConfiguration();
        ragConfig.setName(kbName);
        ragConfig.setStoreType("in-memory");
        ragConfig.setChunkStrategy(chunkStrategy);
        ragConfig.setMaxResults(10);
        ragConfig.setMinScore(0.5);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.rag/ragstore/rag/rag-1?version=1"));

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));

        when(restAgentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);
        when(restWorkflowStore.readWorkflow("wf-1", 1)).thenReturn(workflowConfig);

        try {
            when(resourceClientLibrary.getResource(any(URI.class), eq(RagConfiguration.class))).thenReturn(ragConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModelFactory.getOrCreate(any())).thenReturn(embeddingModel);

        var embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));

        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(embeddingStoreFactory.getOrCreate(any(), anyString())).thenReturn(store);

        var match = new EmbeddingMatch<>(0.9, "id-1", embedding, TextSegment.from(contentText));
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of(match)));
    }
}

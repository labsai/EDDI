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
import ai.labs.eddi.modules.llm.model.LlmConfiguration.KnowledgeBaseReference;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.RagDefaults;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link RagContextProvider} — covers deeper retrieval paths
 * including successful retrieval with formatting, workflow discovery
 * integration, error handling during retrieval, trace data storage, and
 * ragDefaults usage.
 * <p>
 * Every test drives a <em>different</em> knowledge base through the same
 * (agentId, version) coordinates, and RAG discovery goes through
 * {@link WorkflowTraversal}'s process-wide memoization cache (finding F12) — so
 * the cache is cleared around each test. Without that, one test's knowledge
 * base is served to the next, which silently turns a matched KB into "no
 * match".
 */
class RagContextProviderExtendedTest {

    private IAgentStore restAgentStore;
    private IWorkflowStore restWorkflowStore;
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
        restAgentStore = mock(IAgentStore.class);
        restWorkflowStore = mock(IWorkflowStore.class);
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
        // Do not leak this class's knowledge bases into test classes sharing the fork.
        WorkflowTraversal.clearCache();
    }

    // ==================== No RAG Early Returns ====================

    @Nested
    @DisplayName("Early return scenarios")
    class EarlyReturnTests {

        @Test
        @DisplayName("null knowledgeBases and enableWorkflowRag=null → return null")
        void nullKbAndNullWorkflowRag() {
            var task = new LlmConfiguration.Task();
            task.setKnowledgeBases(null);
            task.setEnableWorkflowRag(null);

            assertNull(ragContextProvider.retrieveContext(memory, task, "query"));
        }

        @Test
        @DisplayName("empty knowledgeBases and enableWorkflowRag=false → return null")
        void emptyKbFalseWorkflow() {
            var task = new LlmConfiguration.Task();
            task.setKnowledgeBases(List.of());
            task.setEnableWorkflowRag(false);

            assertNull(ragContextProvider.retrieveContext(memory, task, "query"));
        }

        @Test
        @DisplayName("enableWorkflowRag=true but no workflow steps → return null")
        void workflowRagNoSteps() throws Exception {
            var task = new LlmConfiguration.Task();
            task.setEnableWorkflowRag(true);

            // Agent returns null → no workflow steps
            when(restAgentStore.read("agent-1", 1)).thenReturn(null);

            assertNull(ragContextProvider.retrieveContext(memory, task, "query"));
        }
    }

    // ==================== Explicit KB Refs ====================

    @Nested
    @DisplayName("Explicit KB reference matching")
    class ExplicitKbRefTests {

        @Test
        @DisplayName("KB ref that doesn't match any workflow step → return null")
        void kbRefNoMatch() {
            var ref = new KnowledgeBaseReference();
            ref.setName("nonexistent-kb");

            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setKnowledgeBases(List.of(ref));

            // Setup workflow with RAG config that has a different name
            setupWorkflowWithRagConfig("product-docs");

            assertNull(ragContextProvider.retrieveContext(memory, task, "query"));
        }

        @Test
        @DisplayName("KB ref with custom maxResults and minScore overrides")
        void kbRefWithOverrides() {
            var ref = new KnowledgeBaseReference();
            ref.setName("product-docs");
            ref.setMaxResults(5);
            ref.setMinScore(0.8);

            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setKnowledgeBases(List.of(ref));

            setupWorkflowWithSuccessfulRetrieval("product-docs", "Relevant content about products");

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNotNull(result);
            assertTrue(result.contains("product-docs"));
            assertTrue(result.contains("Relevant content about products"));
        }

        @Test
        @DisplayName("KB ref with null maxResults → uses KB default")
        void kbRefNullMaxResults() {
            var ref = new KnowledgeBaseReference();
            ref.setName("docs");
            ref.setMaxResults(null);
            ref.setMinScore(null);

            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setKnowledgeBases(List.of(ref));

            setupWorkflowWithSuccessfulRetrieval("docs", "Document content");

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNotNull(result, "a reference with no overrides must still retrieve — the KB defaults apply");
            assertTrue(result.contains("### Source: docs"));
            assertTrue(result.contains("Document content"));

            var trace = firstTraceEntry("rag:trace:task1");
            assertEquals(10, trace.get("maxResults"), "null ref maxResults must fall back to the KB's own maxResults");
            assertEquals(0.5, trace.get("minScore"), "null ref minScore must fall back to the KB's own minScore");
        }
    }

    // ==================== Workflow Discovery (auto mode) ====================

    @Nested
    @DisplayName("Workflow discovery auto-mode")
    class WorkflowDiscoveryTests {

        @Test
        @DisplayName("enableWorkflowRag=true with ragDefaults should use default params")
        void workflowRagWithDefaults() {
            var defaults = new RagDefaults();
            defaults.setMaxResults(3);
            defaults.setMinScore(0.7);

            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setEnableWorkflowRag(true);
            task.setRagDefaults(defaults);

            setupWorkflowWithSuccessfulRetrieval("auto-kb", "Auto-discovered content");

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNotNull(result);
            assertTrue(result.contains("auto-kb"), "workflow discovery must surface the KB it actually traversed");
            assertTrue(result.contains("Auto-discovered content"));

            var trace = firstTraceEntry("rag:trace:task1");
            assertEquals(3, trace.get("maxResults"), "ragDefaults.maxResults must win over the KB default");
            assertEquals(0.7, trace.get("minScore"), "ragDefaults.minScore must win over the KB default");
        }

        @Test
        @DisplayName("enableWorkflowRag=true with null ragDefaults → uses KB defaults")
        void workflowRagNullDefaults() {
            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setEnableWorkflowRag(true);
            task.setRagDefaults(null);

            setupWorkflowWithSuccessfulRetrieval("kb1", "Content");

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNotNull(result);
        }

        @Test
        @DisplayName("ragDefaults with null fields → uses KB defaults")
        void ragDefaultsNullFields() {
            var defaults = new RagDefaults();
            defaults.setMaxResults(null);
            defaults.setMinScore(null);

            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setEnableWorkflowRag(true);
            task.setRagDefaults(defaults);

            setupWorkflowWithSuccessfulRetrieval("kb1", "Content");

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNotNull(result);
        }
    }

    // ==================== Error Handling ====================

    @Nested
    @DisplayName("Error handling during retrieval")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Exception during embedding model creation → stores error trace")
        void embeddingModelCreationFails() {
            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setEnableWorkflowRag(true);

            setupWorkflowWithRagConfig("kb-error");

            when(embeddingModelFactory.getOrCreate(any())).thenThrow(
                    new RuntimeException("Model creation failed"));

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            // Should return null but store error trace
            assertNull(result);
            // Verify trace data was stored (error trace)
            verify(currentStep, atLeastOnce()).storeData(any());
        }
    }

    // ==================== Context Formatting ====================

    @Nested
    @DisplayName("Context formatting")
    class FormattingTests {

        @Test
        @DisplayName("empty retrieval results → return null")
        void emptyResults() {
            var task = new LlmConfiguration.Task();
            task.setId("task1");
            task.setEnableWorkflowRag(true);

            setupWorkflowWithEmptyRetrieval("kb-empty");

            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNull(result, "Should return null when no results are retrieved");
        }

        @Test
        @DisplayName("null taskId → uses 'default' suffix")
        void nullTaskId() {
            var ref = new KnowledgeBaseReference();
            ref.setName("docs");

            var task = new LlmConfiguration.Task();
            task.setId(null); // null task ID
            task.setKnowledgeBases(List.of(ref));

            setupWorkflowWithSuccessfulRetrieval("docs", "Content");

            // Should not throw
            String result = ragContextProvider.retrieveContext(memory, task, "query");

            assertNotNull(result);
            assertTrue(result.contains("Content"));
            assertNotNull(storedData("rag:context:default"), "a task with no id must store under the 'default' suffix");
            assertNotNull(storedData("rag:trace:default"));
            assertNull(storedData("rag:context:null"), "'null' must never be stringified into the memory key");
        }
    }

    // ==================== RetrievalResult record ====================

    @Test
    @DisplayName("RetrievalResult record stores kbName and content")
    void retrievalResultRecord() {
        var content = Content.from(TextSegment.from("test text"));
        var result = new RagContextProvider.RetrievalResult("my-kb", content);

        assertEquals("my-kb", result.kbName());
        assertNotNull(result.content());
        assertEquals("test text", result.content().textSegment().text());
    }

    // ==================== Helpers ====================

    /**
     * The value the provider wrote to conversation memory under {@code key}, or
     * null if it never wrote that key. Reading the audit trace is the only way to
     * observe the retrieval parameters the provider actually resolved.
     */
    private Object storedData(String key) {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> values = ArgumentCaptor.forClass(Object.class);
        verify(dataFactory, atLeastOnce()).createData(keys.capture(), values.capture());

        var allKeys = keys.getAllValues();
        for (int i = allKeys.size() - 1; i >= 0; i--) {
            if (key.equals(allKeys.get(i))) {
                return values.getAllValues().get(i);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstTraceEntry(String traceKey) {
        var trace = (List<Map<String, Object>>) storedData(traceKey);
        assertNotNull(trace, "expected an audit trace under " + traceKey);
        assertFalse(trace.isEmpty());
        return trace.get(0);
    }

    private void setupWorkflowWithRagConfig(String kbName) {
        var ragConfig = new RagConfiguration();
        ragConfig.setName(kbName);
        ragConfig.setStoreType("in-memory");
        ragConfig.setMaxResults(10);
        ragConfig.setMinScore(0.5);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.rag/ragstore/rag/rag-1?version=1"));

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of(step));

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));

        try {
            when(restAgentStore.read("agent-1", 1)).thenReturn(agentConfig);
            when(restWorkflowStore.read("wf-1", 1)).thenReturn(workflowConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            when(resourceClientLibrary.getResource(any(URI.class), eq(RagConfiguration.class)))
                    .thenReturn(ragConfig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void setupWorkflowWithSuccessfulRetrieval(String kbName, String contentText) {
        setupWorkflowWithRagConfig(kbName);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModelFactory.getOrCreate(any())).thenReturn(embeddingModel);

        var embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));

        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(embeddingStoreFactory.getOrCreate(any(), anyString())).thenReturn(store);

        var match = new EmbeddingMatch<>(0.9, "id-1", embedding, TextSegment.from(contentText));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of(match)));
    }

    @SuppressWarnings("unchecked")
    private void setupWorkflowWithEmptyRetrieval(String kbName) {
        setupWorkflowWithRagConfig(kbName);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModelFactory.getOrCreate(any())).thenReturn(embeddingModel);

        var embedding = Embedding.from(new float[]{0.1f, 0.2f, 0.3f});
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));

        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(embeddingStoreFactory.getOrCreate(any(), anyString())).thenReturn(store);

        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
    }
}

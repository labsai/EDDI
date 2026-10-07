/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retrieval reads the knowledge base the workflow binds — by its id — and, in
 * the per-id layout, only that knowledge base's chunks.
 *
 * <p>
 * The live defect: an agent bound only to "alpha" answered with text that an
 * editor of "beta" had ingested with {@code ?kbId=alpha}, because the store was
 * addressed by the name and nothing at read time checked whose chunk it was.
 */
@DisplayName("RAG retrieval is isolated per knowledge base")
class RagContextProviderIsolationTest {

    private static final String ALPHA_ID = "65f0aa11bb22cc33dd44ee55";
    private static final String BETA_ID = "65f0aa11bb22cc33dd44ee66";

    private IResourceClientLibrary resourceClientLibrary;
    private EmbeddingStoreFactory embeddingStoreFactory;
    private RagContextProvider provider;
    private IConversationMemory memory;
    private final InMemoryEmbeddingStore<TextSegment> sharedStore = new InMemoryEmbeddingStore<>();

    @BeforeEach
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();
        var agentStore = mock(IAgentStore.class);
        var workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        var embeddingModelFactory = mock(EmbeddingModelFactory.class);
        embeddingStoreFactory = mock(EmbeddingStoreFactory.class);
        var dataFactory = mock(IDataFactory.class);
        @SuppressWarnings("unchecked")
        IData<Object> data = mock(IData.class);
        lenient().when(dataFactory.createData(anyString(), any())).thenReturn(data);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1f, 0f})));
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(model);
        // Both knowledge bases resolve to one physical store — a shared explicit table.
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(sharedStore);

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rag"));
        step.setConfig(Map.of("uri", "eddi://ai.labs.rag/ragstore/rags/" + ALPHA_ID + "?version=1"));
        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(List.of(step));
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-iso?version=1")));
        when(agentStore.read("agent-iso", 1)).thenReturn(agent);
        when(workflowStore.read("wf-iso", 1)).thenReturn(workflow);

        provider = new RagContextProvider(agentStore, workflowStore, resourceClientLibrary, embeddingModelFactory, embeddingStoreFactory,
                dataFactory);

        memory = mock(IConversationMemory.class);
        when(memory.getCurrentStep()).thenReturn(mock(IConversationMemory.IWritableConversationStep.class));
        when(memory.getAgentId()).thenReturn("agent-iso");
        when(memory.getAgentVersion()).thenReturn(1);

        add("ALPHA-LEGIT about apples", "alpha");
        add("POISON-123 from beta", BETA_ID);
    }

    @AfterEach
    void tearDown() {
        WorkflowTraversal.clearCache();
    }

    private void add(String text, String kbTag) {
        sharedStore.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from(text, Metadata.from(KnowledgeBaseStorage.METADATA_KB_ID, kbTag)));
    }

    private String retrieve(RagConfiguration alpha) throws Exception {
        when(resourceClientLibrary.getResource(any(URI.class), eq(RagConfiguration.class))).thenReturn(alpha);
        var task = new LlmConfiguration.Task();
        task.setEnableWorkflowRag(true);
        return provider.retrieveContext(memory, task, "apples");
    }

    private static RagConfiguration alpha(String namespace) {
        var config = new RagConfiguration();
        config.setName("alpha");
        config.setStoreType("in-memory");
        config.setStoreNamespace(namespace);
        config.setMaxResults(10);
        config.setMinScore(0.0);
        return config;
    }

    @Test
    @DisplayName("the store is asked for by the bound configuration's id, not by its name")
    void storeIsAddressedById() throws Exception {
        retrieve(alpha("id"));
        verify(embeddingStoreFactory).getOrCreate(eq(ALPHA_ID), any(RagConfiguration.class));
    }

    @Test
    @DisplayName("an id-layout knowledge base sees only chunks tagged with its own id")
    void idLayoutFiltersOtherKnowledgeBasesOut() throws Exception {
        add("ALPHA-BY-ID about apples", ALPHA_ID);

        String context = retrieve(alpha("id"));

        assertNotNull(context);
        assertTrue(context.contains("ALPHA-BY-ID"), context);
        assertFalse(context.contains("POISON-123"), context);
        assertFalse(context.contains("ALPHA-LEGIT"), "a chunk tagged with a name is not this knowledge base's in the id layout");
    }

    @Test
    @DisplayName("a 6.5.0-layout knowledge base is not filtered, so the chunks it already has stay retrievable")
    void legacyLayoutIsNotFiltered() throws Exception {
        String context = retrieve(alpha(null));

        assertNotNull(context);
        assertTrue(context.contains("ALPHA-LEGIT"), context);
    }

    @Test
    void stepWithoutAnIdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> RagContextProvider.ragConfigIdOf(Map.of("uri", "eddi://ai.labs.rag")));
        assertThrows(IllegalArgumentException.class, () -> RagContextProvider.ragConfigIdOf(Map.of()));
        assertEquals(ALPHA_ID, RagContextProvider.ragConfigIdOf(Map.of("uri", "eddi://ai.labs.rag/ragstore/rags/" + ALPHA_ID + "?version=3")));
    }
}

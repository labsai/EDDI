package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.KnowledgeBaseReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RagContextProviderTest {

    @Mock
    private IRestAgentStore restAgentStore;
    @Mock
    private IRestWorkflowStore restWorkflowStore;
    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private EmbeddingModelFactory embeddingModelFactory;
    @Mock
    private EmbeddingStoreFactory embeddingStoreFactory;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IConversationMemory.IWritableConversationStep currentStep;

    private RagContextProvider ragContextProvider;

    @BeforeEach
    void setUp() {
        openMocks(this);
        ragContextProvider = new RagContextProvider(restAgentStore, restWorkflowStore, resourceClientLibrary, embeddingModelFactory,
                embeddingStoreFactory, dataFactory);

        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getAgentId()).thenReturn("agent-123");
        when(memory.getAgentVersion()).thenReturn(1);

        @SuppressWarnings("unchecked")
        IData<Object> mockData = mock(IData.class);
        when(dataFactory.createData(anyString(), any())).thenReturn(mockData);
    }

    @Test
    void noKnowledgeBases_andNoWorkflowRag_shouldReturnNull() {
        var task = new LlmConfiguration.Task();
        task.setKnowledgeBases(null);
        task.setEnableWorkflowRag(false);

        String result = ragContextProvider.retrieveContext(memory, task, "hello");

        assertNull(result);
        verify(restAgentStore, never()).readAgent(anyString(), anyInt());
    }

    @Test
    void emptyKnowledgeBases_andNoWorkflowRag_shouldReturnNull() {
        var task = new LlmConfiguration.Task();
        task.setKnowledgeBases(List.of());
        task.setEnableWorkflowRag(false);

        String result = ragContextProvider.retrieveContext(memory, task, "hello");

        assertNull(result);
    }

    @Test
    void autoDiscovery_withNoWorkflowSteps_shouldReturnNull() {
        var task = new LlmConfiguration.Task();
        task.setEnableWorkflowRag(true);

        when(restAgentStore.readAgent("agent-123", 1)).thenReturn(null);

        String result = ragContextProvider.retrieveContext(memory, task, "hello");

        assertNull(result);
    }

    @Test
    void explicitKbRef_withNoWorkflowSteps_shouldReturnNull() {
        var ref = new KnowledgeBaseReference();
        ref.setName("product-docs");

        var task = new LlmConfiguration.Task();
        task.setId("testTask");
        task.setKnowledgeBases(List.of(ref));

        when(restAgentStore.readAgent("agent-123", 1)).thenReturn(null);

        String result = ragContextProvider.retrieveContext(memory, task, "hello");

        assertNull(result);
        verify(restAgentStore).readAgent("agent-123", 1);
    }

    @Test
    void nullTaskId_shouldUseDefaultSuffix() {
        var ref = new KnowledgeBaseReference();
        ref.setName("kb1");

        var task = new LlmConfiguration.Task();
        task.setId(null);
        task.setKnowledgeBases(List.of(ref));

        when(restAgentStore.readAgent("agent-123", 1)).thenReturn(null);

        // Should not throw
        ragContextProvider.retrieveContext(memory, task, "hello");
    }
}

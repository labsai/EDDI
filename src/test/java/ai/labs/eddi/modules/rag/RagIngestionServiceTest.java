/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Map;

import dev.langchain4j.data.embedding.Embedding;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RagIngestionServiceTest {

    @Mock
    private EmbeddingModelFactory embeddingModelFactory;
    @Mock
    private EmbeddingStoreFactory embeddingStoreFactory;
    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private EmbeddingStore<TextSegment> embeddingStore;

    private RagIngestionService service;

    @BeforeEach
    void setUp() {
        openMocks(this);
        service = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory);
    }

    @Test
    void ingest_shouldReturnIngestionId() {
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(embeddingModel);
        when(embeddingStoreFactory.getOrCreate(any(), anyString())).thenReturn(embeddingStore);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f})));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f})));

        var config = createConfig();

        String ingestionId = service.ingest("test-kb", "Hello world document content.", "test-doc.txt", config);

        assertNotNull(ingestionId);
        assertFalse(ingestionId.isBlank());
    }

    /**
     * Ingestion must ask for a DOCUMENT model.
     * <p>
     * This and its counterpart in {@code RagContextProviderTest} are what make the
     * fix real: the decorator can be perfect and the defect survives if both call
     * sites still ask for the same role. They did — both called a single-argument
     * {@code getOrCreate} with the same configuration, so they shared one cache
     * entry, and Gemini's {@code taskType} (default {@code RETRIEVAL_DOCUMENT}) was
     * applied to queries too.
     */
    @Test
    void ingest_asksForADocumentModel() {
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(embeddingModel);
        when(embeddingStoreFactory.getOrCreate(any(), anyString())).thenReturn(embeddingStore);
        when(embeddingModel.embed(any(TextSegment.class)))
                .thenReturn(Response.from(Embedding.from(new float[]{0.1f})));
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f})));

        service.ingest("test-kb", "Hello world document content.", "test-doc.txt", createConfig());

        // ingest() hands the work to a virtual thread, so the factory call has not
        // necessarily happened yet when ingest() returns.
        var role = ArgumentCaptor.forClass(EmbeddingInputType.class);
        verify(embeddingModelFactory, timeout(5000).atLeastOnce()).getOrCreate(any(), role.capture());
        assertEquals(EmbeddingInputType.DOCUMENT, role.getValue(),
                "text being stored must be embedded as a DOCUMENT");
    }

    @Test
    void getStatus_shouldReturnPendingInitially() {
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(embeddingModel);
        when(embeddingStoreFactory.getOrCreate(any(), anyString())).thenReturn(embeddingStore);

        var config = createConfig();
        String ingestionId = service.ingest("test-kb", "Content", "doc.txt", config);

        // Status should be either pending or processing (virtual thread may have
        // started)
        String status = service.getStatus(ingestionId);
        assertTrue(status.equals("pending") || status.equals("processing") || status.equals("completed") || status.startsWith("failed"),
                "Status should be a valid state, got: " + status);
    }

    @Test
    void getStatus_unknownId_shouldReturnUnknown() {
        String status = service.getStatus("non-existent-id");
        assertEquals("unknown", status);
    }

    private RagConfiguration createConfig() {
        var config = new RagConfiguration();
        config.setName("test-kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("apiKey", "test-key"));
        config.setStoreType("in-memory");
        config.setChunkSize(256);
        config.setChunkOverlap(32);
        return config;
    }
}

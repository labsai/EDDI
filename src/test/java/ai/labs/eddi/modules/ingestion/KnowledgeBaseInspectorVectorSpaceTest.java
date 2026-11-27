/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import ai.labs.eddi.modules.rag.VectorSpaceMismatchException;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A search checks the embedding space before it spends an embedding call, and a
 * document's chunk list says when it is not the whole document.
 */
@DisplayName("KnowledgeBaseInspector and the vector space")
class KnowledgeBaseInspectorVectorSpaceTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private InMemoryEmbeddingStore<TextSegment> vectorStore;
    private EmbeddingModel model;
    private VectorSpaceGuard guard;
    private KnowledgeBaseInspector inspector;

    @BeforeEach
    void setUp() {
        vectorStore = new InMemoryEmbeddingStore<>();
        EmbeddingStoreFactory storeFactory = mock(EmbeddingStoreFactory.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(vectorStore);
        model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1f, 0f})));
        EmbeddingModelFactory modelFactory = mock(EmbeddingModelFactory.class);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        inspector = new KnowledgeBaseInspector(new InMemoryIngestionStateStore(), new InMemoryIngestedFileStore(),
                storeFactory, modelFactory);
        guard = mock(VectorSpaceGuard.class);
        inspector.vectorSpaceGuard = guard;
    }

    private static RagConfiguration knowledgeBase() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_ID);
        return config;
    }

    private static IngestionSource webSource() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var source = new IngestionSource();
        source.setId("src-web");
        source.setName("docs");
        source.setWeb(web);
        return source;
    }

    private void storeChunks(int count) {
        for (int i = 0; i < count; i++) {
            var metadata = Metadata.from(IngestionPipeline.METADATA_DOCUMENT_ID, "https://example.com/a")
                    .put(IngestionPipeline.METADATA_SOURCE_KEY, IngestionPipeline.stateKey(KB_ID, webSource()))
                    .put(KnowledgeBaseStorage.METADATA_KB_ID, KB_ID)
                    .put(KnowledgeBaseInspector.METADATA_SEGMENT_INDEX, i);
            vectorStore.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("chunk " + i, metadata));
        }
    }

    @Test
    @DisplayName("a search in another embedding space is refused before the query is embedded")
    void searchIsRefusedBeforeEmbedding() {
        doThrow(new VectorSpaceMismatchException("holds emb-8, configured other-8")).when(guard).requireCompatible(eq(KB_ID), any());

        assertThrows(VectorSpaceMismatchException.class, () -> inspector.search(KB_ID, knowledgeBase(), null, "hello", 5, 0.0));

        verify(model, never()).embed(anyString());
    }

    @Test
    @DisplayName("the query's vector size is checked against the store, without being recorded")
    void theQueryDimensionIsChecked() {
        inspector.search(KB_ID, knowledgeBase(), null, "hello", 5, 0.0);

        verify(guard).verifyDimension(eq(KB_ID), any(), eq(2), eq(false));
    }

    @Test
    @DisplayName("a vector of another size is refused with the guard's message, not a driver error")
    void aWrongSizedQueryIsRefused() {
        doThrow(new VectorSpaceMismatchException("2 dimensions, store made for 1536")).when(guard).verifyDimension(anyString(), any(), anyInt(),
                anyBoolean());

        var e = assertThrows(VectorSpaceMismatchException.class, () -> inspector.search(KB_ID, knowledgeBase(), null, "hello", 5, 0.0));

        assertTrue(e.getMessage().contains("1536"));
    }

    @Test
    @DisplayName("a document with more chunks than the limit says so")
    void chunkListsSayWhenTruncated() {
        storeChunks(5);

        var page = inspector.documentChunkPage(KB_ID, knowledgeBase(), webSource(), "https://example.com/a", 3);

        assertEquals(3, page.chunks().size());
        assertTrue(page.truncated());
    }

    @Test
    @DisplayName("a document that fits is not flagged")
    void completeChunkListsAreNotFlagged() {
        storeChunks(3);

        var page = inspector.documentChunkPage(KB_ID, knowledgeBase(), webSource(), "https://example.com/a", 3);

        assertEquals(3, page.chunks().size());
        assertFalse(page.truncated());
    }
}

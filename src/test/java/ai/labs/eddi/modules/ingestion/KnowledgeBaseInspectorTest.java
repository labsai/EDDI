/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The read-only views into a knowledge base, against a real in-memory vector
 * store — the filters are the point, and a mocked store would accept any.
 */
class KnowledgeBaseInspectorTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String OTHER_KB_ID = "6b9c2d3e4f5a6b7c8d9e0f1a";

    private InMemoryIngestionStateStore stateStore;
    private InMemoryIngestedFileStore fileStore;
    private InMemoryEmbeddingStore<TextSegment> vectorStore;
    private KnowledgeBaseInspector inspector;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        fileStore = new InMemoryIngestedFileStore();
        vectorStore = new InMemoryEmbeddingStore<>();

        EmbeddingStoreFactory storeFactory = mock(EmbeddingStoreFactory.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(vectorStore);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{1f, 0f})));
        EmbeddingModelFactory modelFactory = mock(EmbeddingModelFactory.class);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);

        inspector = new KnowledgeBaseInspector(stateStore, fileStore, storeFactory, modelFactory);
    }

    private static RagConfiguration knowledgeBase() {
        var config = new RagConfiguration();
        config.setName("product-docs");
        // The per-id layout, so retrieval isolation applies.
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

    private static IngestionSource uploadSource() {
        var source = new IngestionSource();
        source.setId("src-files");
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        return source;
    }

    private void storeChunk(String kbId, IngestionSource source, String documentId, int index, String text,
                            float[] vector) {
        var metadata = Metadata.from(IngestionPipeline.METADATA_DOCUMENT_ID, documentId)
                .put(IngestionPipeline.METADATA_SOURCE_KEY, IngestionPipeline.stateKey(kbId, source))
                .put(KnowledgeBaseStorage.METADATA_KB_ID, kbId)
                .put(KnowledgeBaseInspector.METADATA_SEGMENT_INDEX, index);
        vectorStore.add(Embedding.from(vector), TextSegment.from(text, metadata));
    }

    private void recordIngested(IngestionSource source, String documentId) {
        String key = IngestionPipeline.stateKey(KB_ID, source);
        String runId = stateStore.startRun(key).orElseThrow();
        stateStore.recordIngested(key, documentId, "hash-" + documentId, null, null, runId);
        stateStore.finishRun(new IIngestionStateStore.IngestionRun(runId, key,
                IIngestionStateStore.IngestionRun.Status.COMPLETED, null, null, 1, 1, 0, 0, 0, 1, 0.0, null));
    }

    @Test
    @DisplayName("lists a web source's documents by URL, filterable and paged")
    void listsWebDocuments() {
        recordIngested(webSource(), "https://example.com/a");
        recordIngested(webSource(), "https://example.com/b");
        recordIngested(webSource(), "https://example.com/guide");

        var all = inspector.listDocuments(KB_ID, webSource(), null, 0, 50);
        assertEquals(3, all.total());
        assertFalse(all.truncated());
        assertEquals("https://example.com/a", all.documents().stream()
                .filter(d -> d.documentId().endsWith("/a")).findFirst().orElseThrow().url());

        var filtered = inspector.listDocuments(KB_ID, webSource(), "GUIDE", 0, 50);
        assertEquals(1, filtered.total());

        var page = inspector.listDocuments(KB_ID, webSource(), null, 2, 2);
        assertEquals(3, page.total());
        assertEquals(1, page.documents().size());
    }

    @Test
    @DisplayName("labels an uploaded file by its name, not its id")
    void labelsFilesByName() {
        IngestionSource source = uploadSource();
        IIngestedFileStore.StoredFile file = fileStore.store(IngestionPipeline.stateKey(KB_ID, source),
                "handbook.pdf", "application/pdf", "content".getBytes(StandardCharsets.UTF_8));
        recordIngested(source, file.fileId());

        var page = inspector.listDocuments(KB_ID, source, "handbook", 0, 50);

        assertEquals(1, page.total());
        assertEquals("handbook.pdf", page.documents().get(0).label());
        assertEquals(null, page.documents().get(0).url());
    }

    @Test
    @DisplayName("the orphan-sweep marker is bookkeeping, never listed as a document")
    void hidesTheSweepMarker() {
        recordIngested(webSource(), IngestionPipeline.SWEEP_MARKER);
        recordIngested(webSource(), "https://example.com/a");

        assertEquals(1, inspector.listDocuments(KB_ID, webSource(), null, 0, 50).total());
    }

    @Test
    @DisplayName("a document's chunks come back in document order, and only that document's")
    void documentChunksInOrder() {
        storeChunk(KB_ID, webSource(), "https://example.com/a", 1, "second", new float[]{1f, 0f});
        storeChunk(KB_ID, webSource(), "https://example.com/a", 0, "first", new float[]{0f, 1f});
        storeChunk(KB_ID, webSource(), "https://example.com/b", 0, "other document", new float[]{1f, 0f});

        var chunks = inspector.documentChunks(KB_ID, knowledgeBase(), webSource(), "https://example.com/a", 50);

        assertEquals(List.of("first", "second"), chunks.stream().map(KnowledgeBaseInspector.Chunk::text).toList());
        assertEquals(0, chunks.get(0).index());
    }

    @Test
    @DisplayName("search applies retrieval's isolation: another knowledge base's chunks in a shared store stay hidden")
    void searchIsIsolated() {
        storeChunk(KB_ID, webSource(), "https://example.com/a", 0, "ours", new float[]{1f, 0f});
        storeChunk(OTHER_KB_ID, webSource(), "https://example.com/a", 0, "theirs", new float[]{1f, 0f});

        var hits = inspector.search(KB_ID, knowledgeBase(), null, "anything", 10, 0.0);

        assertEquals(List.of("ours"), hits.stream().map(KnowledgeBaseInspector.Chunk::text).toList());
        assertTrue(hits.get(0).score() > 0.9, "a search reports its score");
    }

    @Test
    @DisplayName("search can be narrowed to one source")
    void searchNarrowsToASource() {
        storeChunk(KB_ID, webSource(), "https://example.com/a", 0, "from the site", new float[]{1f, 0f});
        storeChunk(KB_ID, uploadSource(), "file-1", 0, "from a file", new float[]{1f, 0f});

        var hits = inspector.search(KB_ID, knowledgeBase(), uploadSource(), "anything", 10, 0.0);

        assertEquals(List.of("from a file"), hits.stream().map(KnowledgeBaseInspector.Chunk::text).toList());
    }

    @Test
    @DisplayName("search honours the minimum score")
    void searchHonoursMinScore() {
        storeChunk(KB_ID, webSource(), "https://example.com/a", 0, "close", new float[]{1f, 0f});
        storeChunk(KB_ID, webSource(), "https://example.com/b", 0, "far", new float[]{-1f, 0f});

        var hits = inspector.search(KB_ID, knowledgeBase(), null, "anything", 10, 0.9);

        assertEquals(List.of("close"), hits.stream().map(KnowledgeBaseInspector.Chunk::text).toList());
    }
}

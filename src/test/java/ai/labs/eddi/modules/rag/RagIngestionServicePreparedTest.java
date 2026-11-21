/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The document-index entry point: prepared text with metadata in, chunks tagged
 * {@code doclib:<id>} with the owner and the document out — tags no caller
 * chooses — and deletion by document id only.
 */
@DisplayName("RagIngestionService — prepared document chunks")
class RagIngestionServicePreparedTest {

    private static final String INDEX_ID = "65f0aa11bb22cc33dd44ee77";
    private static final String ROOT = "65f0aa11bb22cc33dd44ee01";
    private static final String OTHER_ROOT = "65f0aa11bb22cc33dd44ee02";
    private static final String OWNER_A = "doclib|tenant-a";
    private static final String OWNER_B = "doclib|tenant-b";

    private final InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private EmbeddingStoreFactory embeddingStoreFactory;
    private RagIngestionService service;

    @BeforeEach
    void setUp() {
        var embeddingModelFactory = mock(EmbeddingModelFactory.class);
        embeddingStoreFactory = mock(EmbeddingStoreFactory.class);
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream().map(s -> Embedding.from(new float[]{1f, 0f})).toList());
            }
        };
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(model);
        when(embeddingStoreFactory.getOrCreateForIngestion(anyString(), any())).thenReturn(store);
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(store);
        service = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory, new CacheFactory());
    }

    private static RagConfiguration index() {
        var config = new RagConfiguration();
        config.setName("documents");
        config.setStoreType("in-memory");
        config.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_ID);
        config.setDocumentIndex(true);
        return config;
    }

    private List<TextSegment> all() {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0f})).maxResults(100).minScore(0.0)
                .build()).matches().stream().map(match -> match.embedded()).toList();
    }

    private void addOrdinaryChunk(String text, String kbTag, String documentId) {
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from(text, Metadata.from(KnowledgeBaseStorage.METADATA_KB_ID, kbTag)
                .put(IngestionPipeline.METADATA_DOCUMENT_ID, documentId)));
    }

    @Test
    @DisplayName("every chunk carries the doclib tag, the owner and the document, whatever the segment says")
    void chunksAreTaggedByTheService() {
        service.ingestPrepared(INDEX_ID, index(), List.of(new PreparedSegment("Chapter one text.", Map.of("unit", "u1", "pageFrom", 3))),
                OWNER_A, ROOT);

        List<TextSegment> chunks = all();
        assertEquals(1, chunks.size());
        Metadata metadata = chunks.get(0).metadata();
        assertEquals("doclib:" + INDEX_ID, metadata.getString(KnowledgeBaseStorage.METADATA_KB_ID));
        assertEquals(OWNER_A, metadata.getString(IngestionPipeline.METADATA_SOURCE_KEY));
        assertEquals(ROOT, metadata.getString(IngestionPipeline.METADATA_DOCUMENT_ID));
        assertEquals("u1", metadata.getString("unit"));
        assertEquals(3, metadata.getInteger("pageFrom"));
    }

    @Test
    @DisplayName("re-indexing a document replaces its chunks — those of a previous owner too — and nothing else")
    void reindexReplacesTheDocumentOnly() {
        service.ingestPrepared(INDEX_ID, index(), List.of(new PreparedSegment("old text", null)), OWNER_A, ROOT);
        service.ingestPrepared(INDEX_ID, index(), List.of(new PreparedSegment("other document", null)), OWNER_A, OTHER_ROOT);
        addOrdinaryChunk("knowledge base text", INDEX_ID, ROOT);

        service.ingestPrepared(INDEX_ID, index(), List.of(new PreparedSegment("new text", null)), OWNER_B, ROOT);

        List<String> texts = all().stream().map(TextSegment::text).toList();
        assertTrue(texts.contains("new text"), texts.toString());
        assertTrue(!texts.contains("old text"), "no chunk survives under the previous owner: " + texts);
        assertTrue(texts.contains("other document"), texts.toString());
        assertTrue(texts.contains("knowledge base text"), "a chunk of the knowledge base's own is not a document chunk: " + texts);
    }

    @Test
    @DisplayName("an ineligible configuration is refused before anything is embedded or stored")
    void ineligibleConfigurationIsRefused() {
        var ordinary = index();
        ordinary.setDocumentIndex(null);
        assertThrows(IllegalStateException.class,
                () -> service.ingestPrepared(INDEX_ID, ordinary, List.of(new PreparedSegment("text", null)), OWNER_A, ROOT));

        var nameLayout = index();
        nameLayout.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_NAME);
        assertThrows(IllegalStateException.class,
                () -> service.ingestPrepared(INDEX_ID, nameLayout, List.of(new PreparedSegment("text", null)), OWNER_A, ROOT));

        verify(embeddingStoreFactory, never()).getOrCreateForIngestion(anyString(), any());
        assertTrue(all().isEmpty());
    }

    @Test
    @DisplayName("the owner key must be doclib|<ownerKey>, and a document id and text are required")
    void argumentsAreChecked() {
        List<PreparedSegment> segments = List.of(new PreparedSegment("text", null));
        assertThrows(IllegalArgumentException.class, () -> service.ingestPrepared(INDEX_ID, index(), segments, INDEX_ID + ":crawler", ROOT));
        assertThrows(IllegalArgumentException.class, () -> service.ingestPrepared(INDEX_ID, index(), segments, "doclib|", ROOT));
        assertThrows(IllegalArgumentException.class, () -> service.ingestPrepared(INDEX_ID, index(), segments, null, ROOT));
        assertThrows(IllegalArgumentException.class, () -> service.ingestPrepared(INDEX_ID, index(), segments, OWNER_A, " "));
        assertThrows(IllegalArgumentException.class, () -> service.ingestPrepared(INDEX_ID, index(), List.of(), OWNER_A, ROOT));
        assertTrue(all().isEmpty());
    }

    @Test
    @DisplayName("a prepared segment cannot set the keys that decide whose a chunk is")
    void reservedMetadataIsRefused() {
        for (String reserved : PreparedSegment.RESERVED_KEYS) {
            assertThrows(IllegalArgumentException.class, () -> new PreparedSegment("text", Map.of(reserved, "x")), reserved);
        }
        assertThrows(IllegalArgumentException.class, () -> new PreparedSegment("text", Map.of("pages", List.of(1, 2))));
        assertThrows(IllegalArgumentException.class, () -> new PreparedSegment(" ", null));
    }

    @Test
    @DisplayName("deleting by document id removes that document's chunks under every owner, and nothing else")
    void deleteByDocumentIdRemovesOnlyThatDocument() {
        service.ingestPrepared(INDEX_ID, index(), List.of(new PreparedSegment("doc A", null)), OWNER_A, ROOT);
        service.ingestPrepared(INDEX_ID, index(), List.of(new PreparedSegment("doc B", null)), OWNER_A, OTHER_ROOT);
        // Written before an owner change, so under another owner: still this document.
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("doc A, previous owner",
                Metadata.from(KnowledgeBaseStorage.METADATA_KB_ID, "doclib:" + INDEX_ID).put(IngestionPipeline.METADATA_SOURCE_KEY, OWNER_B)
                        .put(IngestionPipeline.METADATA_DOCUMENT_ID, ROOT)));
        addOrdinaryChunk("knowledge base text with the same document id", INDEX_ID, ROOT);
        addOrdinaryChunk("another index's chunk", "doclib:65f0aa11bb22cc33dd44ee99", ROOT);

        assertEquals(RagIngestionService.DeleteOutcome.DELETED, service.deleteByDocumentId(INDEX_ID, index(), ROOT));

        List<String> texts = all().stream().map(TextSegment::text).toList();
        assertEquals(List.of("another index's chunk", "doc B", "knowledge base text with the same document id"), texts.stream().sorted().toList());
    }

    @Test
    @DisplayName("deletion is never refused for eligibility, but needs a document id")
    void deleteNeedsOnlyADocumentId() {
        var ordinary = index();
        ordinary.setDocumentIndex(null);
        assertEquals(RagIngestionService.DeleteOutcome.DELETED, service.deleteByDocumentId(INDEX_ID, ordinary, ROOT));
        assertThrows(IllegalArgumentException.class, () -> service.deleteByDocumentId(INDEX_ID, index(), ""));
    }
}

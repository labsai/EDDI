/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.langchain4j.data.embedding.Embedding;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(embeddingStore);
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
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(embeddingStore);
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
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(embeddingStore);

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

    /**
     * With {@code replace}, the second ingestion of a name supersedes the first —
     * against the real in-memory store, so the metadata filter is exercised rather
     * than a mock that accepts anything.
     */
    @Test
    void replace_supersedesThePreviousVersionOfTheSameName() {
        var store = new InMemoryEmbeddingStore<TextSegment>();
        wireRealStore(store);

        awaitCompleted(service.ingest("test-kb", "Refunds within 30 days.", "policy.txt", createConfig(), false));
        awaitCompleted(service.ingest("test-kb", "Shipping takes 5 days.", "shipping.txt", createConfig(), false));
        awaitCompleted(service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true));

        assertEquals(List.of("Refunds within 47 days.", "Shipping takes 5 days."), storedTexts(store),
                "the old policy.txt is gone, the new one and the unrelated document remain");
    }

    @Test
    void withoutReplace_bothVersionsAreKept() {
        var store = new InMemoryEmbeddingStore<TextSegment>();
        wireRealStore(store);

        awaitCompleted(service.ingest("test-kb", "Refunds within 30 days.", "policy.txt", createConfig(), false));
        awaitCompleted(service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig()));

        assertEquals(List.of("Refunds within 30 days.", "Refunds within 47 days."), storedTexts(store));
    }

    /**
     * Chunks ingested before chunks carried an ingestion id have no such key. A
     * replacement must still supersede them, or every document ingested before the
     * upgrade could never be replaced.
     */
    @Test
    void replace_alsoSupersedesChunksWrittenWithoutAnIngestionId() {
        var store = new InMemoryEmbeddingStore<TextSegment>();
        wireRealStore(store);
        var legacy = TextSegment.from("Refunds within 30 days.", Metadata.from("source", "policy.txt").put("kbId", "test-kb"));
        store.add(Embedding.from(new float[]{1f, 0f}), legacy);

        awaitCompleted(service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true));

        assertEquals(List.of("Refunds within 47 days."), storedTexts(store));
    }

    @Test
    void replace_onAStoreThatCannotDeleteByMetadata_completesWithAWarning() {
        wireRealStore(embeddingStore);
        doThrow(new UnsupportedOperationException("not supported")).when(embeddingStore).removeAll(any(Filter.class));

        String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true);
        awaitCompleted(id);

        assertNotNull(service.getWarning(id), "the operator is told the old version is still retrievable");
        verify(embeddingStore).addAll(anyList(), anyList());
    }

    @Test
    void replace_whoseCleanupFails_failsAndSaysBothVersionsMayRemain() {
        wireRealStore(embeddingStore);
        doThrow(new RuntimeException("connection reset")).when(embeddingStore).removeAll(any(Filter.class));

        String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true);
        long deadline = System.currentTimeMillis() + 5000;
        while (service.getStatus(id).equals("pending") || service.getStatus(id).equals("processing")) {
            assertTrue(System.currentTimeMillis() < deadline, "ingestion did not finish");
            Thread.onSpinWait();
        }

        assertTrue(service.getStatus(id).startsWith("failed"), service.getStatus(id));
        assertNotNull(service.getWarning(id), "the status must say the new version is stored alongside the old");
    }

    @Test
    void withoutReplace_nothingIsRemovedAndThereIsNoWarning() {
        wireRealStore(embeddingStore);

        String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), false);
        awaitCompleted(id);

        verify(embeddingStore, never()).removeAll(any(Filter.class));
        assertNull(service.getWarning(id));
    }

    /**
     * Two replacements of one name each store, then delete every other ingestion's
     * chunks under it. Interleaved — both store before either deletes — each
     * deletion takes the other's new chunks and the document is left with none. The
     * store below holds the first write until a second one arrives (or a second
     * passes), forcing exactly that interleaving when nothing serializes the two.
     */
    @Test
    void concurrentReplacementsOfOneNameLeaveOneVersion() throws Exception {
        var bothWriting = new CountDownLatch(2);
        var store = new InMemoryEmbeddingStore<TextSegment>() {
            @Override
            public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
                bothWriting.countDown();
                try {
                    bothWriting.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.addAll(ids, embeddings, embedded);
            }
        };
        wireRealStore(store);

        String first = service.ingest("test-kb", "Refunds within 30 days.", "policy.txt", createConfig(), true);
        String second = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true);
        awaitCompleted(first);
        awaitCompleted(second);

        List<String> stored = storedTexts(store);
        assertEquals(1, stored.size(), "exactly one version must survive two concurrent replacements: " + stored);
    }

    /**
     * Two knowledge bases in one physical store — two with an explicit shared
     * table, or two 6.5.0-layout ones that were (until now) always merged. A
     * replacement by one of them must not delete a document of the same name that
     * belongs to the other, and every chunk carries the id of the knowledge base
     * that wrote it, which is what retrieval filters on.
     */
    @Test
    void replaceInASharedStoreOnlyTouchesItsOwnKnowledgeBase() {
        var shared = new InMemoryEmbeddingStore<TextSegment>();
        wireRealStore(shared);
        var alpha = createConfig();
        alpha.setName("same-name");
        alpha.setStoreNamespace("id");
        var beta = createConfig();
        beta.setName("same-name");
        beta.setStoreNamespace("id");

        awaitCompleted(service.ingest(ALPHA_ID, "ALPHA policy text.", "policy.txt", alpha, false));
        awaitCompleted(service.ingest(BETA_ID, "BETA policy text.", "policy.txt", beta, true));

        assertEquals(List.of("ALPHA policy text.", "BETA policy text."), storedTexts(shared),
                "beta's replace=true must not delete alpha's document of the same name");
        var alphaOnly = shared.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0f}))
                .maxResults(10).minScore(0.0).filter(EmbeddingStoreFactory.retrievalFilter(ALPHA_ID, alpha)).build())
                .matches().stream().map(match -> match.embedded().text()).toList();
        assertEquals(List.of("ALPHA policy text."), alphaOnly, "alpha's retrieval filter must see only alpha's chunks");
    }

    @Test
    void ingestionAddressesTheStoreByTheConfigurationId() {
        wireRealStore(new InMemoryEmbeddingStore<>());
        var config = createConfig();
        config.setStoreNamespace("id");

        awaitCompleted(service.ingest(ALPHA_ID, "text", "doc", config, false));

        verify(embeddingStoreFactory).getOrCreate(eq(ALPHA_ID), same(config));
    }

    private static final String ALPHA_ID = "65f0aa11bb22cc33dd44ee55";
    private static final String BETA_ID = "65f0aa11bb22cc33dd44ee66";

    private void wireRealStore(EmbeddingStore<TextSegment> store) {
        EmbeddingModel fixed = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{1f, 0f})).toList());
            }
        };
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(fixed);
        when(embeddingStoreFactory.getOrCreate(anyString(), any())).thenReturn(store);
    }

    private void awaitCompleted(String ingestionId) {
        long deadline = System.currentTimeMillis() + 5000;
        String status = service.getStatus(ingestionId);
        while (("pending".equals(status) || "processing".equals(status)) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
            status = service.getStatus(ingestionId);
        }
        assertEquals("completed", status);
    }

    private static List<String> storedTexts(InMemoryEmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0f})).maxResults(100).minScore(0.0)
                .build()).matches().stream().map(match -> match.embedded().text()).sorted().toList();
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

/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.cluster.lease.IConversationLeaseManager;
import ai.labs.eddi.engine.cluster.lease.LeaseHandle;
import ai.labs.eddi.engine.cluster.lease.LeaseInfo;
import ai.labs.eddi.engine.cluster.lease.LeaseUnavailableException;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.RagIngestionService.DeleteOutcome;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.modules.rag.RagIngestionService.METADATA_KB_ID;
import static ai.labs.eddi.modules.rag.RagIngestionService.METADATA_SOURCE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #1312: two instances sharing one vector store. Each "instance" is its
 * own {@link RagIngestionService} — its own document locks — and the two share
 * a lease manager that excludes across them the way the NATS one does across
 * nodes, the vector store, and (in cluster mode, the shared bucket) the status
 * caches. The same scenarios against real NATS are in
 * {@code RagDocumentLeaseIT}.
 */
@DisplayName("RagIngestionService across instances")
class RagIngestionServiceClusterTest {

    private static final String RAG_ID = "65f0aa11bb22cc33dd44ee55";
    private static final String DOC = "policy.txt";

    private final EmbeddingModelFactory models = mock(EmbeddingModelFactory.class);
    private final EmbeddingStoreFactory stores = mock(EmbeddingStoreFactory.class);
    private final InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
    private final FakeClusterLeases cluster = new FakeClusterLeases();
    /** Stands in for the shared buckets: both instances read the same caches. */
    private final CacheFactory sharedCaches = new CacheFactory();

    /** Holds embedding while closed, so a test can act mid-replacement. */
    private volatile CountDownLatch embeddingGate = new CountDownLatch(0);
    private final CountDownLatch embedding = new CountDownLatch(1);

    private RagIngestionService instanceA;
    private RagIngestionService instanceB;
    private RagConfiguration config;
    private String kbId;

    @BeforeEach
    void setUp() {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                embedding.countDown();
                try {
                    embeddingGate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{1f, 0f})).toList());
            }
        };
        when(models.getOrCreate(any(), any())).thenReturn(model);
        when(stores.getOrCreateForIngestion(anyString(), any())).thenReturn(store);
        when(stores.getOrCreate(anyString(), any())).thenReturn(store);
        instanceA = new RagIngestionService(models, stores, sharedCaches, cluster);
        instanceB = new RagIngestionService(models, stores, sharedCaches, cluster);
        config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("apiKey", "test-key"));
        config.setStoreType("in-memory");
        config.setChunkSize(256);
        config.setChunkOverlap(32);
        kbId = KnowledgeBaseStorage.chunkKbId(RAG_ID, config);
    }

    @Test
    @DisplayName("a delete on B waits for A's replacement and then removes its chunks too")
    void deleteOnAnotherInstanceWaitsForTheReplacement() throws Exception {
        embeddingGate = new CountDownLatch(1);
        String replacing = instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        assertTrue(embedding.await(5, TimeUnit.SECONDS), "A never started embedding");

        CompletableFuture<DeleteOutcome> delete = CompletableFuture.supplyAsync(() -> instanceB.delete(RAG_ID, DOC, config));
        Thread.sleep(200);
        assertFalse(delete.isDone(), "the delete answered while the replacement was still embedding");

        embeddingGate.countDown();
        assertEquals(DeleteOutcome.DELETED, delete.get(10, TimeUnit.SECONDS));
        awaitStatus(instanceA, replacing, "completed");

        assertEquals(List.of(), storedTexts(), "the replacement's chunks came back after the delete");
    }

    @Test
    @DisplayName("a delete that cannot wait out the replacement on another instance answers BUSY and removes nothing")
    void deleteGivesUpWhileTheReplacementHoldsTheDocument() throws Exception {
        embeddingGate = new CountDownLatch(1);
        instanceB.deleteLockTimeout = Duration.ofMillis(200);
        try {
            instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
            assertTrue(embedding.await(5, TimeUnit.SECONDS));

            assertEquals(DeleteOutcome.BUSY, instanceB.delete(RAG_ID, DOC, config));
            verify(stores, never()).getOrCreate(anyString(), any());
        } finally {
            embeddingGate.countDown();
        }
    }

    @Test
    @DisplayName("a delete whose cluster lease cannot be had answers BUSY and leaves the document alone")
    void deleteWithoutALeaseIsBusy() {
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("Refunds within 30 days.",
                Metadata.from(METADATA_SOURCE, DOC).put(METADATA_KB_ID, kbId)));
        cluster.unavailable.set(LeaseUnavailableException.Reason.DEGRADED);

        assertEquals(DeleteOutcome.BUSY, instanceA.delete(RAG_ID, DOC, config));

        assertEquals(List.of("Refunds within 30 days."), storedTexts());
    }

    @Test
    @DisplayName("a delete whose lease was lost before it starts answers BUSY and leaves the document alone")
    void deleteOnALostLeaseIsBusy() {
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("Refunds within 30 days.",
                Metadata.from(METADATA_SOURCE, DOC).put(METADATA_KB_ID, kbId)));
        cluster.loseEveryLease.set(true);

        assertEquals(DeleteOutcome.BUSY, instanceA.delete(RAG_ID, DOC, config));

        assertEquals(List.of("Refunds within 30 days."), storedTexts());
        assertEquals(1, cluster.released.get(), "the lost lease is still released");
    }

    @Test
    @DisplayName("a delete removes the document under the lease and releases it")
    void deleteRemovesTheDocumentUnderTheLease() {
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("Refunds within 30 days.",
                Metadata.from(METADATA_SOURCE, DOC).put(METADATA_KB_ID, kbId)));

        assertEquals(DeleteOutcome.DELETED, instanceA.delete(RAG_ID, DOC, config));

        assertEquals(List.of(), storedTexts());
        assertEquals(1, cluster.acquired.size());
        assertEquals(1, cluster.released.get());
    }

    @Test
    @DisplayName("two replacements on two instances leave exactly one version")
    void concurrentReplacementsOnTwoInstancesLeaveOneVersion() throws Exception {
        embeddingGate = new CountDownLatch(1);
        String first = instanceA.ingest(RAG_ID, "Refunds within 30 days.", DOC, config, true);
        assertTrue(embedding.await(5, TimeUnit.SECONDS));
        String second = instanceB.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        Thread.sleep(100);
        embeddingGate.countDown();

        awaitStatus(instanceA, first, "completed");
        awaitStatus(instanceB, second, "completed");
        assertEquals(List.of("Refunds within 47 days."), storedTexts());
    }

    @Test
    @DisplayName("a replacement that lost its lease before storing writes nothing")
    void replacementThatLostItsLeaseStoresNothing() {
        cluster.loseEveryLease.set(true);

        String id = instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        String status = awaitFinished(instanceA, id);

        assertTrue(status.startsWith("failed") && status.contains("lost the lease"), status);
        assertEquals(List.of(), storedTexts(), "an expired holder finished its write");
        assertEquals(1, cluster.released.get(), "the lost lease is still released");
    }

    @Test
    @DisplayName("a lease lost between store and cleanup skips the cleanup and says both versions may remain")
    void leaseLostBeforeTheCleanupSkipsIt() {
        var spied = spy(store);
        when(stores.getOrCreateForIngestion(anyString(), any())).thenReturn(spied);
        doAnswer(call -> {
            call.callRealMethod();
            cluster.loseEveryLease.set(true);
            return null;
        }).when(spied).addAll(anyList(), anyList());
        store.add(Embedding.from(new float[]{1f, 0f}), TextSegment.from("Refunds within 30 days.",
                Metadata.from(METADATA_SOURCE, DOC).put(METADATA_KB_ID, kbId)));

        String id = instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        String status = awaitFinished(instanceA, id);

        assertTrue(status.startsWith("failed"), status);
        assertNotNull(instanceA.getWarning(RAG_ID, id));
        assertEquals(List.of("Refunds within 30 days.", "Refunds within 47 days."), storedTexts(),
                "the cleanup ran without the lease");
    }

    @Test
    @DisplayName("the lease is released when the replacement fails")
    void leaseIsReleasedWhenTheReplacementFails() {
        var failing = spy(store);
        doThrow(new RuntimeException("connection reset")).when(failing).removeAll(any(Filter.class));
        when(stores.getOrCreateForIngestion(anyString(), any())).thenReturn(failing);

        awaitFinished(instanceA, instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true));

        assertEquals(1, cluster.acquired.size());
        assertEquals(1, cluster.released.get());
        assertTrue(cluster.free(cluster.acquired.get(0)), "the document stays held after a failed replacement");
    }

    @Test
    @DisplayName("a replacement whose lease cannot be had at all fails and touches nothing")
    void replacementWithoutALeaseFails() {
        cluster.unavailable.set(LeaseUnavailableException.Reason.DEGRADED);

        String status = awaitFinished(instanceA, instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true));

        assertTrue(status.startsWith("failed") && status.contains("NATS"), status);
        assertEquals(List.of(), storedTexts());
    }

    @Test
    @DisplayName("an ingestion without replace takes no lease")
    void appendingTakesNoLease() {
        awaitStatus(instanceA, instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, false), "completed");

        assertEquals(List.of(), cluster.acquired);
    }

    @Test
    @DisplayName("the lease key is per document and never carries the name in clear")
    void leaseKeyIsAHashOfTheDocument() {
        String key = RagIngestionService.documentLeaseKey(RAG_ID, kbId, "Customer contract ACME.pdf");

        assertTrue(key.startsWith(IConversationLeaseManager.RAG_DOCUMENT + "h_"), key);
        assertFalse(key.contains("ACME"), key);
        assertTrue(key.matches("[A-Za-z0-9_.=/-]+"), "not a valid NATS KV key: " + key);
        assertNotEquals(key, RagIngestionService.documentLeaseKey(RAG_ID, kbId, "other.pdf"));
        assertNotEquals(key, RagIngestionService.documentLeaseKey("65f0aa11bb22cc33dd44ee66", kbId, "Customer contract ACME.pdf"));
    }

    @Test
    @DisplayName("the status and warning of an ingestion on A can be read on B")
    void statusIsReadableOnTheOtherInstance() {
        var unsupported = spy(store);
        doThrow(new UnsupportedOperationException("no")).when(unsupported).removeAll(any(Filter.class));
        when(stores.getOrCreateForIngestion(anyString(), any())).thenReturn(unsupported);

        String id = instanceA.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);

        awaitStatus(instanceB, id, "completed");
        assertNotNull(instanceB.getWarning(RAG_ID, id), "B must see the warning A wrote");
    }

    private List<String> storedTexts() {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0f})).maxResults(100)
                .minScore(0.0).build()).matches().stream().map(match -> match.embedded().text()).sorted().toList();
    }

    private static String awaitFinished(RagIngestionService service, String id) {
        long deadline = System.currentTimeMillis() + 10_000;
        String status = service.getStatus(RAG_ID, id);
        while (("pending".equals(status) || "processing".equals(status)) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
            status = service.getStatus(RAG_ID, id);
        }
        return status;
    }

    private static void awaitStatus(RagIngestionService service, String id, String expected) {
        assertEquals(expected, awaitFinished(service, id));
    }

    /**
     * Leases shared by every instance built on it: one permit per key, so a key
     * held through one instance is refused to the other until released.
     */
    static final class FakeClusterLeases implements IConversationLeaseManager {
        final Map<String, Semaphore> permits = new ConcurrentHashMap<>();
        final List<String> acquired = new CopyOnWriteArrayList<>();
        final AtomicInteger released = new AtomicInteger();
        final AtomicBoolean loseEveryLease = new AtomicBoolean();
        final AtomicReference<LeaseUnavailableException.Reason> unavailable = new AtomicReference<>();

        boolean free(String key) {
            return permits.get(key).availablePermits() == 1;
        }

        @Override
        public CompletionStage<LeaseHandle> acquireKey(String key, Duration maxWait) {
            return CompletableFuture.supplyAsync(() -> {
                if (unavailable.get() != null) {
                    throw new LeaseUnavailableException(unavailable.get(), null, "unavailable");
                }
                try {
                    if (!permits.computeIfAbsent(key, k -> new Semaphore(1)).tryAcquire(maxWait.toNanos(), TimeUnit.NANOSECONDS)) {
                        throw new LeaseUnavailableException(LeaseUnavailableException.Reason.TIMEOUT, "other-node", "held");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                acquired.add(key);
                return new FakeHandle(key);
            });
        }

        @Override
        public Optional<LeaseHandle> tryAcquireKey(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void release(LeaseHandle handle) {
            if (((FakeHandle) handle).released.compareAndSet(false, true)) {
                released.incrementAndGet();
                permits.get(handle.key()).release();
            }
        }

        @Override
        public Optional<LeaseInfo> peekKey(String key) {
            return Optional.empty();
        }

        @Override
        public int heldCount() {
            return (int) permits.values().stream().filter(p -> p.availablePermits() == 0).count();
        }

        final class FakeHandle implements LeaseHandle {
            final String key;
            final AtomicBoolean released = new AtomicBoolean();

            FakeHandle(String key) {
                this.key = key;
            }

            @Override
            public String key() {
                return key;
            }

            @Override
            public String conversationId() {
                return key;
            }

            @Override
            public Long fence() {
                return 1L;
            }

            @Override
            public boolean isLost() {
                return loseEveryLease.get();
            }

            @Override
            public void onLost(Runnable callback) {
            }
        }
    }
}

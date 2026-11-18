/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.lease;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.caching.SharedKvCache;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.NatsConnectionManager;
import ai.labs.eddi.engine.cluster.NatsSharedStateFactory;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.cluster.SharedBucket;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.RagIngestionService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #1312 against a real NATS JetStream server: two EDDI instances — each
 * its own {@link RagIngestionService}, lease manager, NATS connection and local
 * caches — sharing one vector store and one NATS. The vector store is one
 * in-process store both instances write to, standing in for a shared pgvector
 * or Chroma; what the test proves is the coordination, which runs entirely
 * through NATS: the document lease in {@code LEASES} (acquired, renewed by the
 * heartbeat while a replacement embeds for longer than the lease TTL, lost when
 * its holder is cut off) and the status in {@code RAG_INGESTION}.
 */
@DisplayName("RAG document lease across two instances on real NATS")
class RagDocumentLeaseIT {

    @SuppressWarnings("resource")
    static final GenericContainer<?> NATS = new GenericContainer<>("nats:2.11-alpine").withExposedPorts(4222)
            .withCommand("--jetstream", "--store_dir=/data").waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));

    /**
     * Short, so a replacement that embeds for a few seconds outlives several of
     * them.
     */
    private static final Duration LEASE_TTL = Duration.ofSeconds(4);
    private static final Duration HEARTBEAT = Duration.ofSeconds(1);

    private static final String RAG_ID = "65f0aa11bb22cc33dd44ee55";
    private static final String DOC = "policy.txt";

    @BeforeAll
    static void startNats() {
        NATS.start();
    }

    @AfterAll
    static void stopNats() {
        NATS.stop();
    }

    private final InMemoryEmbeddingStore<TextSegment> sharedStore = new InMemoryEmbeddingStore<>();
    private final List<Instance> instances = new ArrayList<>();
    private String prefix;
    private ScheduledExecutorService scheduler;
    private ExecutorService io;
    private RagConfiguration config;
    private String kbId;

    /** Holds every embedding while closed. */
    private volatile CountDownLatch embeddingGate = new CountDownLatch(0);
    private final CountDownLatch embedding = new CountDownLatch(1);

    /** One EDDI instance: its connection, lease manager, caches and service. */
    final class Instance {
        final NodeIdentity identity;
        final NatsConnectionManager connections;
        final NatsSharedStateFactory shared;
        final KvLeaseManager leases;
        final RagIngestionService service;

        Instance(String id) throws InterruptedException {
            identity = new NodeIdentity(id, UUID.randomUUID().toString().substring(0, 8));
            ClusterConfig clusterConfig = ClusterConfig.defaults().withMessagingType("nats")
                    .withNatsUrl("nats://" + NATS.getHost() + ":" + NATS.getMappedPort(4222)).withNatsPrefix(prefix).withNodeId(id)
                    .withLeaseTimings(LEASE_TTL, HEARTBEAT, Duration.ofSeconds(30));
            var meters = new SimpleMeterRegistry();
            connections = new NatsConnectionManager(clusterConfig, identity, meters);
            shared = new NatsSharedStateFactory(connections, meters);
            connections.start();
            ISharedKv leaseBucket = shared.bucket(SharedBucket.LEASES.withTtl(clusterConfig.leaseTtl()));
            long deadline = System.currentTimeMillis() + 15_000;
            while (!connections.isConnected() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(connections.isConnected(), id + " did not connect");
            leases = new KvLeaseManager(leaseBucket, identity, clusterConfig, scheduler, io, new NatsLeaseManager.NatsNotifier(connections),
                    new KvLeaseManager.ClusterView() {
                        @Override
                        public boolean isConnected() {
                            return connections.isConnected();
                        }

                        @Override
                        public boolean isDegraded() {
                            return false;
                        }

                        @Override
                        public Optional<String> liveBoot(String nodeId) {
                            return instances.stream().filter(i -> i.identity.nodeId().equals(nodeId)).map(i -> i.identity.bootId())
                                    .findFirst();
                        }
                    }, new SimpleMeterRegistry());
            leases.start();
            service = new RagIngestionService(models(), stores(), sharedCaches(), leases);
            shared.provisionAll();
        }

        /**
         * What {@code CacheFactory} builds in NATS mode for the RAG ingestion cache:
         * this instance's own local cache, written through to the shared bucket.
         */
        private ICacheFactory sharedCaches() {
            CacheFactory local = new CacheFactory();
            Map<String, String> buckets = Map.of(RagIngestionService.INGESTION_CACHE_NAME, "RAG_INGESTION");
            return new ICacheFactory() {
                @Override
                public <K, V> ICache<K, V> getCache(String cacheName) {
                    throw new UnsupportedOperationException(cacheName);
                }

                @Override
                public <K, V> ICache<K, V> getCache(String cacheName, Duration ttl) {
                    return new SharedKvCache<>(local.getCache(cacheName, ttl),
                            shared.bucket(new SharedBucket(buckets.get(cacheName), ttl, -1)), false);
                }
            };
        }
    }

    private EmbeddingModelFactory models() {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
                embedding.countDown();
                try {
                    embeddingGate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{1f, 0f})).toList());
            }
        };
        EmbeddingModelFactory models = mock(EmbeddingModelFactory.class);
        when(models.getOrCreate(any(), any())).thenReturn(model);
        return models;
    }

    private EmbeddingStoreFactory stores() {
        EmbeddingStoreFactory stores = mock(EmbeddingStoreFactory.class);
        when(stores.getOrCreateForIngestion(anyString(), any())).thenReturn(sharedStore);
        return stores;
    }

    @BeforeEach
    void setUp() throws Exception {
        prefix = "RG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        scheduler = Executors.newScheduledThreadPool(2);
        io = Executors.newVirtualThreadPerTaskExecutor();
        config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("apiKey", "test-key"));
        config.setStoreType("in-memory");
        kbId = KnowledgeBaseStorage.chunkKbId(RAG_ID, config);
        instances.add(new Instance("a"));
        instances.add(new Instance("b"));
    }

    @AfterEach
    void tearDown() {
        embeddingGate.countDown();
        for (Instance i : instances) {
            i.leases.releaseAll();
            i.connections.shutdown();
        }
        instances.clear();
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    @Test
    @DisplayName("a delete on B waits out A's replacement — embedding for longer than the lease TTL — then removes it; B reads A's status")
    void deleteWaitsForAReplacementOnTheOtherInstance() throws Exception {
        Instance a = instances.get(0);
        Instance b = instances.get(1);
        embeddingGate = new CountDownLatch(1);
        String replacing = a.service.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        assertTrue(embedding.await(10, TimeUnit.SECONDS), "A never started embedding");

        CompletableFuture<Void> delete = CompletableFuture.runAsync(() -> b.service.underDocumentLease(RAG_ID, kbId, DOC,
                Duration.ofSeconds(30), lease -> {
                    sharedStore.removeAll(metadataKey("source").isEqualTo(DOC).and(metadataKey("kbId").isEqualTo(kbId)));
                    return null;
                }));
        // Twice the lease TTL: without the heartbeat renewing A's lease, B would have
        // taken it over by now.
        Thread.sleep(LEASE_TTL.toMillis() * 2);
        assertFalse(delete.isDone(), "B deleted while A's replacement was still embedding");
        assertEquals("processing", b.service.getStatus(RAG_ID, replacing), "B reads the status A writes");

        embeddingGate.countDown();
        delete.get(30, TimeUnit.SECONDS);

        assertEquals("completed", awaitFinished(b.service, replacing), "the replacement must not have lost its lease");
        assertEquals(List.of(), storedTexts(), "the replacement's chunks came back after the delete");
    }

    @Test
    @DisplayName("a delete on B gives up with TIMEOUT while A holds the document")
    void deleteTimesOutWhileTheOtherInstanceHoldsTheDocument() throws Exception {
        embeddingGate = new CountDownLatch(1);
        instances.get(0).service.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        assertTrue(embedding.await(10, TimeUnit.SECONDS));

        var refused = assertThrows(LeaseUnavailableException.class, () -> instances.get(1).service.underDocumentLease(RAG_ID, kbId, DOC,
                Duration.ofSeconds(1), lease -> "ran"));

        assertEquals(LeaseUnavailableException.Reason.TIMEOUT, refused.reason());
    }

    @Test
    @DisplayName("a replacement whose instance is cut off from NATS loses its lease and does not write after B took the document")
    void cutOffHolderDoesNotFinishItsWrite() throws Exception {
        Instance a = instances.get(0);
        Instance b = instances.get(1);
        embeddingGate = new CountDownLatch(1);
        String replacing = a.service.ingest(RAG_ID, "Refunds within 47 days.", DOC, config, true);
        assertTrue(embedding.await(10, TimeUnit.SECONDS));

        a.connections.shutdown();
        // B gets the document once A's lease has expired on the server.
        String took = b.service.underDocumentLease(RAG_ID, kbId, DOC, Duration.ofSeconds(30), lease -> "took");
        assertEquals("took", took);

        embeddingGate.countDown();
        String status = awaitFinished(a.service, replacing);

        assertTrue(status.startsWith("failed") && status.contains("lost the lease"), status);
        assertEquals(List.of(), storedTexts(), "the expired holder wrote its chunks after B held the document");
    }

    private List<String> storedTexts() {
        return sharedStore.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0f})).maxResults(100)
                .minScore(0.0).build()).matches().stream().map(match -> match.embedded().text()).sorted().toList();
    }

    private static String awaitFinished(RagIngestionService service, String id) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        String status = service.getStatus(RAG_ID, id);
        while (("pending".equals(status) || "processing".equals(status)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            status = service.getStatus(RAG_ID, id);
        }
        return status;
    }
}

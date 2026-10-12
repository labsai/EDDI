/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * In-memory knowledge bases are data, not clients.
 *
 * <p>
 * They used to share the store-client cache — 30-minute idle TTL, 50 entries,
 * and a full flush on any secret or global-variable change — so a knowledge
 * base with no query for half an hour, or any credential rotation anywhere in
 * the deployment, silently emptied it. Retrieval then returned no context
 * rather than an error, and a scheduled run did not refill it, because every
 * page's content hash still matched.
 */
@DisplayName("EmbeddingStoreFactory — in-memory stores")
class EmbeddingStoreFactoryInMemoryTest {

    private static final String KB = "product-docs";

    private final GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
    private final SecretResolver secretResolver = mock(SecretResolver.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private EmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = offlineFactory(100);
    }

    /**
     * The real factory, with an external store's client replaced by a fresh object
     * per build: what is under test is the cache around it, and building a real
     * Elasticsearch client opens sockets.
     */
    private EmbeddingStoreFactory offlineFactory(int maxInMemorySegments) {
        return offlineFactory(globalVariableResolver, secretResolver, maxInMemorySegments, ticker);
    }

    static EmbeddingStoreFactory offlineFactory(GlobalVariableResolver variables, SecretResolver secrets,
                                                int maxInMemorySegments, Ticker ticker) {
        return new EmbeddingStoreFactory(variables, secrets, maxInMemorySegments, ticker) {
            @Override
            EmbeddingStore<TextSegment> build(RagConfiguration config, String kbId) {
                return new InMemoryEmbeddingStore<>();
            }
        };
    }

    private static RagConfiguration inMemory() {
        var config = new RagConfiguration();
        config.setName(KB);
        config.setStoreType("in-memory");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", "text-embedding-3-small", "apiKey", "${vault:openai}"));
        return config;
    }

    private static RagConfiguration elasticsearch() {
        var config = new RagConfiguration();
        config.setName(KB);
        config.setStoreType("elasticsearch");
        return config;
    }

    private static void addSegments(EmbeddingStore<TextSegment> store, int count) {
        for (int i = 0; i < count; i++) {
            store.add(Embedding.from(new float[]{0.1f, 0.2f}), TextSegment.from("segment " + i));
        }
    }

    private static int countOf(EmbeddingStore<TextSegment> store) {
        return store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f}))
                .maxResults(1000)
                .minScore(0.0)
                .build()).matches().size();
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Nested
    @DisplayName("what an in-memory store survives")
    class Survival {

        @Test
        @DisplayName("a vault secret changing anywhere in the deployment does not empty it")
        void survivesSecretInvalidation() {
            var secretListener = ArgumentCaptor.forClass(Consumer.class);
            factory.registerInvalidation();
            verify(secretResolver).registerInvalidationListener(secretListener.capture());
            var store = factory.getOrCreate(inMemory(), KB);
            addSegments(store, 3);

            @SuppressWarnings("unchecked")
            Consumer<SecretReference> listener = secretListener.getValue();
            listener.accept(new SecretReference("default", "openai"));

            var after = factory.getOrCreate(inMemory(), KB);
            assertSame(store, after, "an in-memory store holds no credential; rotating one must not rebuild it");
            assertEquals(3, countOf(after));
        }

        @Test
        @DisplayName("a global variable changing does not empty it")
        void survivesGlobalVariableInvalidation() {
            var variableListener = ArgumentCaptor.forClass(Runnable.class);
            factory.registerInvalidation();
            verify(globalVariableResolver).registerInvalidationListener(variableListener.capture());
            var store = factory.getOrCreate(inMemory(), KB);
            addSegments(store, 2);

            variableListener.getValue().run();

            assertSame(store, factory.getOrCreate(inMemory(), KB));
            assertEquals(2, countOf(store));
        }

        @Test
        @DisplayName("an hour without a query does not empty it")
        void survivesPastTheClientCacheTtl() {
            var store = factory.getOrCreate(inMemory(), KB);
            addSegments(store, 4);

            advance(Duration.ofHours(1));

            var after = factory.getOrCreate(inMemory(), KB);
            assertSame(store, after);
            assertEquals(4, countOf(after));
        }

        @Test
        @DisplayName("more knowledge bases than the client cache holds do not push it out")
        void survivesMoreKnowledgeBasesThanTheClientCacheHolds() {
            var first = factory.getOrCreate(inMemory(), KB);
            addSegments(first, 1);
            for (int i = 0; i < EmbeddingStoreFactory.MAX_STORES + 10; i++) {
                factory.getOrCreate(inMemory(), "kb-" + i);
            }

            assertSame(first, factory.getOrCreate(inMemory(), KB));
            assertEquals(1, countOf(first));
        }

        @Test
        @DisplayName("a new version with the same embedding configuration keeps the data")
        void newVersionWithSameEmbeddingKeepsTheData() {
            var store = factory.getOrCreateForIngestion(inMemory(), KB);
            addSegments(store, 2);
            var nextVersion = inMemory();
            nextVersion.setChunkSize(256);
            nextVersion.setMaxResults(9);

            assertFalse(factory.dropSupersededInMemoryStore(inMemory(), nextVersion));
            assertSame(store, factory.getOrCreateForIngestion(nextVersion, KB));
            assertEquals(2, countOf(store));
        }
    }

    @Nested
    @DisplayName("external stores are still evicted")
    class ExternalStores {

        @Test
        @DisplayName("past the idle TTL the client is rebuilt")
        void evictedAfterTheTtl() {
            var before = factory.getOrCreate(elasticsearch(), KB);

            advance(Duration.ofMinutes(31));

            assertNotSame(before, factory.getOrCreate(elasticsearch(), KB));
        }

        @Test
        @DisplayName("within the TTL the client is reused")
        void reusedWithinTheTtl() {
            var before = factory.getOrCreate(elasticsearch(), KB);
            advance(Duration.ofMinutes(29));
            assertSame(before, factory.getOrCreate(elasticsearch(), KB));
        }

        @Test
        @DisplayName("a secret change rebuilds the client")
        void rebuiltOnSecretInvalidation() {
            var secretListener = ArgumentCaptor.forClass(Consumer.class);
            factory.registerInvalidation();
            verify(secretResolver).registerInvalidationListener(secretListener.capture());
            var before = factory.getOrCreate(elasticsearch(), KB);

            @SuppressWarnings("unchecked")
            Consumer<SecretReference> listener = secretListener.getValue();
            listener.accept(new SecretReference("default", "es-password"));

            assertNotSame(before, factory.getOrCreate(elasticsearch(), KB));
        }

        @Test
        @DisplayName("has no fingerprint, so ingestion state is trusted exactly as before")
        void noFingerprint() {
            assertNull(factory.ingestionFingerprint(elasticsearch(), KB));
            assertNull(factory.previewIngestionFingerprint(elasticsearch(), KB));
        }
    }

    @Nested
    @DisplayName("when an in-memory store is dropped")
    class Dropping {

        @Test
        @DisplayName("deleting the knowledge base drops it and its documents")
        void droppedOnDelete() {
            var store = factory.getOrCreate(inMemory(), KB);
            addSegments(store, 3);

            assertTrue(factory.dropInMemoryStore(inMemory()));

            var after = factory.getOrCreate(inMemory(), KB);
            assertNotSame(store, after);
            assertEquals(0, countOf(after));
            assertEquals(0, factory.inMemorySegmentCount(), "a dropped store no longer counts against the cap");
        }

        @Test
        @DisplayName("deleting an external knowledge base touches no in-memory store of the same name")
        void externalDeleteLeavesInMemoryAlone() {
            var store = factory.getOrCreate(inMemory(), KB);

            assertFalse(factory.dropInMemoryStore(elasticsearch()));
            assertSame(store, factory.getOrCreate(inMemory(), KB));
        }

        @Test
        @DisplayName("an update that renames, moves or re-models the knowledge base drops the old store")
        void droppedWhenAnUpdateLeavesItBehind() {
            var renamed = inMemory();
            renamed.setName("renamed");
            var moved = elasticsearch();
            var remodelled = inMemory();
            remodelled.setEmbeddingParameters(Map.of("model", "text-embedding-3-large"));

            for (RagConfiguration updated : List.of(renamed, moved, remodelled)) {
                var store = factory.getOrCreate(inMemory(), KB);
                assertTrue(factory.dropSupersededInMemoryStore(inMemory(), updated), updated.getStoreType());
                assertNotSame(store, factory.getOrCreate(inMemory(), KB));
            }
        }

        @Test
        @DisplayName("a rotated embedding API key is not a new model")
        void credentialChangeKeepsTheStore() {
            var store = factory.getOrCreate(inMemory(), KB);
            var rotated = inMemory();
            rotated.setEmbeddingParameters(Map.of("model", "text-embedding-3-small", "apiKey", "${vault:openai-2}"));

            assertFalse(factory.dropSupersededInMemoryStore(inMemory(), rotated));
            assertSame(store, factory.getOrCreateForIngestion(rotated, KB));
        }

        @Test
        @DisplayName("a write under another embedding model replaces the store; a read does not")
        void onlyAWriteReplacesOnModelChange() {
            var store = factory.getOrCreateForIngestion(inMemory(), KB);
            addSegments(store, 2);
            var remodelled = inMemory();
            remodelled.setEmbeddingParameters(Map.of("model", "text-embedding-3-large"));

            assertSame(store, factory.getOrCreate(remodelled, KB),
                    "retrieval by a version still pinned to the old model must not wipe the store");
            var written = factory.getOrCreateForIngestion(remodelled, KB);
            assertNotSame(store, written, "vectors of two models must not be mixed in one store");
            assertEquals(0, countOf(written));
        }
    }

    @Nested
    @DisplayName("store epoch")
    class Epoch {

        @Test
        @DisplayName("the fingerprint is stable for one store and new for the next")
        void fingerprintIdentifiesTheStore() {
            String first = factory.ingestionFingerprint(inMemory(), KB);
            assertTrue(first.startsWith(EmbeddingStoreFactory.FINGERPRINT_EPOCH_PREFIX), first);
            assertEquals(first, factory.ingestionFingerprint(inMemory(), KB));

            factory.dropInMemoryStore(inMemory());

            assertNotEquals(first, factory.ingestionFingerprint(inMemory(), KB));
        }

        @Test
        @DisplayName("a restarted process has a different epoch for the same knowledge base")
        void restartChangesTheEpoch() {
            var restarted = offlineFactory(100);

            assertNotEquals(factory.ingestionFingerprint(inMemory(), KB), restarted.ingestionFingerprint(inMemory(), KB));
        }

        @Test
        @DisplayName("a preview neither creates a store nor matches a recorded fingerprint before one exists")
        void previewHasNoSideEffects() {
            assertEquals(EmbeddingStoreFactory.FINGERPRINT_NO_STORE, factory.previewIngestionFingerprint(inMemory(), KB));
            String created = factory.ingestionFingerprint(inMemory(), KB);
            assertEquals(created, factory.previewIngestionFingerprint(inMemory(), KB));

            var remodelled = inMemory();
            remodelled.setEmbeddingParameters(Map.of("model", "text-embedding-3-large"));
            assertEquals(EmbeddingStoreFactory.FINGERPRINT_NO_STORE, factory.previewIngestionFingerprint(remodelled, KB));
            assertEquals(created, factory.ingestionFingerprint(inMemory(), KB), "the preview replaced nothing");
        }
    }

    @Nested
    @DisplayName("deployment-wide segment cap")
    class Cap {

        @Test
        @DisplayName("a write past the cap fails with an actionable error and stores nothing")
        void refusesPastTheCap() {
            var capped = offlineFactory(5);
            var one = capped.getOrCreateForIngestion(inMemory(), KB);
            addSegments(one, 3);
            var other = capped.getOrCreateForIngestion(inMemory(), "other-kb");

            List<Embedding> embeddings = List.of(Embedding.from(new float[]{0.1f, 0.2f}), Embedding.from(new float[]{0.2f, 0.1f}),
                    Embedding.from(new float[]{0.3f, 0.1f}));
            List<TextSegment> segments = List.of(TextSegment.from("a"), TextSegment.from("b"), TextSegment.from("c"));
            var full = assertThrows(InMemoryStoreFullException.class, () -> other.addAll(embeddings, segments));

            assertTrue(full.getMessage().contains(EmbeddingStoreFactory.MAX_IN_MEMORY_SEGMENTS_PROPERTY), full.getMessage());
            assertTrue(full.getMessage().contains("pgvector"), "the error names the way out: " + full.getMessage());
            assertEquals(0, countOf(other), "nothing of a refused batch is stored");
            assertEquals(3, countOf(one), "nothing is evicted to make room");

            other.addAll(embeddings.subList(0, 2), segments.subList(0, 2));
            assertEquals(5, capped.inMemorySegmentCount(), "up to the cap is allowed");
            assertThrows(InMemoryStoreFullException.class, () -> addSegments(one, 1));
        }

        @Test
        @DisplayName("removing segments makes room again")
        void removalFreesCapacity() {
            var capped = offlineFactory(2);
            var store = capped.getOrCreateForIngestion(inMemory(), KB);
            addSegments(store, 2);
            assertThrows(InMemoryStoreFullException.class, () -> addSegments(store, 1));

            store.removeAll();
            addSegments(store, 2);

            assertEquals(2, countOf(store));
        }

        @Test
        @DisplayName("zero disables the cap")
        void zeroDisablesTheCap() {
            var uncapped = offlineFactory(0);
            var store = uncapped.getOrCreateForIngestion(inMemory(), KB);

            addSegments(store, 250);

            assertEquals(250, uncapped.inMemorySegmentCount());
        }
    }
}

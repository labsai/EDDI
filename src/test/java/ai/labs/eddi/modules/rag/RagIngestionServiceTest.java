/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.caching.SharedKvCache;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.secrets.ResolvedCredentials;
import ai.labs.eddi.secrets.SecretResolver;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import dev.langchain4j.data.embedding.Embedding;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RagIngestionServiceTest {

    /** The RAG configuration id the tests ingest into unless they say otherwise. */
    private static final String KB = "test-kb";

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
        service = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory, new CacheFactory());
    }

    @Test
    void ingest_shouldReturnIngestionId() {
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(embeddingModel);
        when(embeddingStoreFactory.getOrCreateForIngestion(anyString(), any())).thenReturn(embeddingStore);
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
        when(embeddingStoreFactory.getOrCreateForIngestion(anyString(), any())).thenReturn(embeddingStore);
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
        when(embeddingStoreFactory.getOrCreateForIngestion(anyString(), any())).thenReturn(embeddingStore);

        var config = createConfig();
        String ingestionId = service.ingest("test-kb", "Content", "doc.txt", config);

        // Status should be either pending or processing (virtual thread may have
        // started)
        String status = service.getStatus(KB, ingestionId);
        assertTrue(status.equals("pending") || status.equals("processing") || status.equals("completed") || status.startsWith("failed"),
                "Status should be a valid state, got: " + status);
    }

    @Test
    void getStatus_unknownId_shouldReturnUnknown() {
        String status = service.getStatus(KB, "non-existent-id");
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

        assertNotNull(service.getWarning(KB, id), "the operator is told the old version is still retrievable");
        verify(embeddingStore).addAll(anyList(), anyList());
    }

    @Test
    void replace_whoseCleanupFails_failsAndSaysBothVersionsMayRemain() {
        wireRealStore(embeddingStore);
        doThrow(new RuntimeException("connection reset")).when(embeddingStore).removeAll(any(Filter.class));

        String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true);
        long deadline = System.currentTimeMillis() + 5000;
        while (service.getStatus(KB, id).equals("pending") || service.getStatus(KB, id).equals("processing")) {
            assertTrue(System.currentTimeMillis() < deadline, "ingestion did not finish");
            Thread.onSpinWait();
        }

        assertTrue(service.getStatus(KB, id).startsWith("failed"), service.getStatus(KB, id));
        assertNotNull(service.getWarning(KB, id), "the status must say the new version is stored alongside the old");
    }

    /**
     * Rotation between build and failure: the embedding factory builds a client
     * from {@code apiKey}, the reference is rotated, and the in-flight client then
     * fails echoing the secret it holds. The factory captured exactly that secret,
     * and the ingestion hands it to the redactor; the redactor's own resolution now
     * returns the rotated value, which would not match.
     */
    private String[] ingestAfterRotation(String apiKey, String heldSecret, String echoed) throws Exception {
        Map<String, String> vault = new HashMap<>(Map.of("${vault:kb-key}", heldSecret));
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
            Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
            resolved.replaceAll((k, v) -> vault.getOrDefault(v, v));
            return resolved;
        });
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> vault.getOrDefault(inv.getArgument(0), inv.getArgument(0)));
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        var credentials = new ResolvedCredentials();
        var factory = new EmbeddingModelFactory(variables, secretResolver);
        factory.useResolvedCredentials(credentials);
        var config = createConfig();
        config.setEmbeddingParameters(Map.of("apiKey", apiKey));
        EmbeddingModel built = factory.getOrCreate(config, EmbeddingInputType.DOCUMENT);
        assertEquals(Set.of(heldSecret), credentials.heldBy(built), "the factory captures the secret itself, not the whole value");

        vault.put("${vault:kb-key}", "rotated-" + "t".repeat(12));
        // The client in flight: it holds what the factory captured for the built one.
        EmbeddingModel inFlight = mock(EmbeddingModel.class);
        credentials.record(inFlight, credentials.heldBy(built));
        when(inFlight.embedAll(anyList())).thenThrow(new IllegalStateException("401: key " + echoed + " was revoked"));
        when(embeddingModelFactory.getOrCreate(any(), any())).thenReturn(inFlight);
        when(embeddingStoreFactory.getOrCreateForIngestion(anyString(), any())).thenReturn(embeddingStore);
        var redactor = new KnowledgeBaseFailureRedactor(secretResolver, variables);
        redactor.resolvedCredentials = credentials;
        service.failureRedactor = redactor;
        service.resolvedCredentials = credentials;

        List<String> logged = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        logged.add(String.valueOf(parameter));
                    }
                }
                if (record.getThrown() != null) {
                    logged.add(String.valueOf(record.getThrown()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger julLogger = Logger.getLogger(RagIngestionService.class.getName());
        Level previous = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        String status;
        try {
            String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", config);
            long deadline = System.currentTimeMillis() + 5000;
            while (service.getStatus(KB, id).equals("pending") || service.getStatus(KB, id).equals("processing")) {
                assertTrue(System.currentTimeMillis() < deadline, "ingestion did not finish");
                Thread.onSpinWait();
            }
            status = service.getStatus(KB, id);
            // The status is stored before the failure is logged, on the ingestion thread.
            while (logged.stream().noneMatch(line -> line.contains("failed for KB"))) {
                assertTrue(System.currentTimeMillis() < deadline + 5000, "the failure was not logged: " + logged);
                Thread.onSpinWait();
            }
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previous);
        }
        return new String[]{status, String.join("\n", logged)};
    }

    @Test
    void rotatedSecretEmbeddedInAValue_isScrubbedFromStatusAndLog() throws Exception {
        String secret = "embedded-" + "s".repeat(12);

        String[] result = ingestAfterRotation("prefix-${vault:kb-key}", secret, secret);

        assertTrue(result[0].startsWith("failed"), result[0]);
        assertFalse(result[0].contains(secret), result[0]);
        assertTrue(result[0].contains("was revoked"), result[0]);
        assertTrue(result[1].contains("was revoked"), "the redacted provider text is logged: " + result[1]);
        assertFalse(result[1].contains(secret), result[1]);
    }

    @Test
    void rotatedShortSecret_withholdsTheStatusAndLog() throws Exception {
        String shortSecret = "q".repeat(3);

        String[] result = ingestAfterRotation("${vault:kb-key}", shortSecret, shortSecret);

        assertTrue(result[0].startsWith("failed: The provider's error was withheld"), result[0]);
        assertFalse(result[0].contains("was revoked"), result[0]);
        assertFalse(result[1].contains("was revoked"), result[1]);
        assertFalse(result[1].contains("key " + shortSecret), result[1]);
    }

    /**
     * A failure with no message of its own whose cause quotes a short store
     * password: the stored status is the generic text, whatever the top-level
     * message is.
     */
    @Test
    void failureWithANullMessage_andAShortPasswordInItsCause_storesTheGenericStatus() {
        String shortPassword = "v".repeat(5);
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        service.failureRedactor = new KnowledgeBaseFailureRedactor(secretResolver, variables);
        when(embeddingModelFactory.getOrCreate(any(), any()))
                .thenThrow(new RuntimeException((String) null, new IllegalStateException("password authentication failed: " + shortPassword)));
        var config = createConfig();
        config.setStoreType("pgvector");
        config.setStoreParameters(Map.of("user", "eddi", "password", shortPassword));

        String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", config);
        long deadline = System.currentTimeMillis() + 5000;
        while (service.getStatus(KB, id).equals("pending") || service.getStatus(KB, id).equals("processing")) {
            assertTrue(System.currentTimeMillis() < deadline, "ingestion did not finish");
            Thread.onSpinWait();
        }

        String status = service.getStatus(KB, id);
        assertTrue(status.startsWith("failed: The provider's error was withheld"), status);
        assertFalse(status.contains(shortPassword), status);
        assertFalse(status.contains("password authentication failed"), status);
    }

    /**
     * POST /ingest answers 202 on one node, and the poll that follows can land on
     * any other: with the status in a node-local cache, N-1 of N polls in a cluster
     * answered 404 for an ingestion that was running or done.
     */
    @Test
    void statusAndWarning_writtenOnOneNode_areReadOnAnother() {
        ISharedKv bucket = new InMemorySharedKv("RAG_INGESTION", Duration.ofHours(1));
        var nodeA = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory, sharedThrough(bucket));
        var nodeB = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory, sharedThrough(bucket));
        wireRealStore(embeddingStore);
        doThrow(new UnsupportedOperationException("not supported")).when(embeddingStore).removeAll(any(Filter.class));

        String id = nodeA.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), true);
        assertNotEquals(RagIngestionService.STATUS_UNKNOWN, nodeB.getStatus(KB, id), "the poll on the other node finds the ingestion");
        long deadline = System.currentTimeMillis() + 5000;
        while (!"completed".equals(nodeA.getStatus(KB, id)) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        // Status and warning share one bucket: neither may overwrite the other.
        assertEquals("completed", nodeB.getStatus(KB, id));
        assertEquals(nodeA.getWarning(KB, id), nodeB.getWarning(KB, id));
        assertNotNull(nodeB.getWarning(KB, id), "the warning travels with the status");
        assertNull(nodeB.getWarning(KB, "another-id"));
        assertEquals(RagIngestionService.STATUS_UNKNOWN, nodeB.getStatus(KB, "warning:" + id), "a warning is never read as a status");
    }

    /**
     * The status endpoint checks access on the knowledge base in its path, while an
     * ingestion id is global: a caller with access to knowledge base B who holds an
     * ingestion id of A must not read A's status (a document name, or a failure
     * message) by polling it through B — on any node of a cluster.
     */
    @Test
    void statusAndWarning_ofOneKnowledgeBase_areUnknownThroughAnother() {
        ISharedKv bucket = new InMemorySharedKv("RAG_INGESTION", Duration.ofHours(1));
        var nodeA = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory, sharedThrough(bucket));
        var nodeB = new RagIngestionService(embeddingModelFactory, embeddingStoreFactory, sharedThrough(bucket));
        wireRealStore(embeddingStore);
        doThrow(new UnsupportedOperationException("not supported")).when(embeddingStore).removeAll(any(Filter.class));

        String id = nodeA.ingest(ALPHA_ID, "Refunds within 47 days.", "secret-policy.txt", createConfig(), true);
        long deadline = System.currentTimeMillis() + 5000;
        while (!"completed".equals(nodeA.getStatus(ALPHA_ID, id)) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }

        assertEquals("completed", nodeB.getStatus(ALPHA_ID, id), "the owning knowledge base still reads it, on another node");
        assertNotNull(nodeB.getWarning(ALPHA_ID, id));
        for (RagIngestionService node : List.of(nodeA, nodeB)) {
            assertEquals(RagIngestionService.STATUS_UNKNOWN, node.getStatus(BETA_ID, id), "polled through another knowledge base");
            assertNull(node.getWarning(BETA_ID, id), "a warning is not read through another knowledge base either");
        }
    }

    /**
     * A node's cache factory in cluster mode: its own node-local cache, written
     * through to the bucket every node shares.
     */
    private static ICacheFactory sharedThrough(ISharedKv bucket) {
        var local = new CacheFactory();
        return new ICacheFactory() {
            @Override
            public <K, V> ICache<K, V> getCache(String cacheName) {
                return new SharedKvCache<>(local.getCache(cacheName), bucket, false);
            }

            @Override
            public <K, V> ICache<K, V> getCache(String cacheName, Duration ttl) {
                return new SharedKvCache<>(local.getCache(cacheName, ttl), bucket, false);
            }
        };
    }

    @Test
    void withoutReplace_nothingIsRemovedAndThereIsNoWarning() {
        wireRealStore(embeddingStore);

        String id = service.ingest("test-kb", "Refunds within 47 days.", "policy.txt", createConfig(), false);
        awaitCompleted(id);

        verify(embeddingStore, never()).removeAll(any(Filter.class));
        assertNull(service.getWarning(KB, id));
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

        awaitCompleted(ALPHA_ID, service.ingest(ALPHA_ID, "ALPHA policy text.", "policy.txt", alpha, false));
        awaitCompleted(BETA_ID, service.ingest(BETA_ID, "BETA policy text.", "policy.txt", beta, true));

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

        awaitCompleted(ALPHA_ID, service.ingest(ALPHA_ID, "text", "doc", config, false));

        verify(embeddingStoreFactory).getOrCreateForIngestion(eq(ALPHA_ID), same(config));
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
        when(embeddingStoreFactory.getOrCreateForIngestion(anyString(), any())).thenReturn(store);
    }

    private void awaitCompleted(String ingestionId) {
        awaitCompleted(KB, ingestionId);
    }

    private void awaitCompleted(String ragConfigId, String ingestionId) {
        long deadline = System.currentTimeMillis() + 5000;
        String status = service.getStatus(ragConfigId, ingestionId);
        while (("pending".equals(status) || "processing".equals(status)) && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
            status = service.getStatus(ragConfigId, ingestionId);
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

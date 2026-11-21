/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.rest.KnowledgeBaseStorageGuard;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseReembedService.ReembedIncompleteException;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseReembedService.ReembedRefusedException;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory.PgVectorTableInfo;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory.RemovalOutcome;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import ai.labs.eddi.modules.rag.VectorSpaceMismatchException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Re-embedding deletes vectors, so every way it can stop half-way is played
 * through here against real state: the in-memory ingestion state store (the
 * same contract as the MongoDB and PostgreSQL ones), the real vector-space
 * guard on it, and a second guard over the same store standing for another node
 * with a cold cache. After any failure the knowledge base must be either
 * unchanged, or refused with the advice to re-embed again — never searchable
 * half-empty, never "unchanged" with its vectors gone.
 */
@DisplayName("Re-embedding — what is left after it stops half-way")
class KnowledgeBaseReembedRecoveryTest {

    private static final String ID = "kb1";
    private static final int SIZE = 8;

    private InMemoryIngestionStateStore stateStore;
    private GlobalVariableResolver variables;
    private VectorSpaceGuard thisNode;
    private EmbeddingStoreFactory storeFactory;
    private RagSourceIngestionService sources;
    private KnowledgeBaseReembedService service;
    private IngestionSource source;

    /** A process that dies: nothing after it runs, no catch block sees it. */
    private static final class Killed extends Error {
    }

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(call -> call.getArgument(0));
        thisNode = new VectorSpaceGuard(stateStore, variables);

        // Claims as the pipeline takes them: a RUNNING row in the state store.
        IngestionPipeline pipeline = mock(IngestionPipeline.class);
        when(pipeline.claimForMaintenance(eq(ID), any())).thenAnswer(
                call -> stateStore.startRun(IngestionPipeline.stateKey(ID, call.getArgument(1)), null));
        doAnswer(call -> {
            stateStore.finishRun(new IngestionRun(call.getArgument(2), IngestionPipeline.stateKey(ID, call.getArgument(1)),
                    IngestionRun.Status.MAINTENANCE, null, Instant.now(), 0, 0, 0, 0, 0, 0, 0.0, null));
            return null;
        }).when(pipeline).releaseClaim(eq(ID), any(), anyString());

        storeFactory = mock(EmbeddingStoreFactory.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(storeFactory.getOrCreate(anyString(), any())).thenReturn(store);
        when(storeFactory.removeKnowledgeBaseVectors(anyString(), any())).thenReturn(RemovalOutcome.CHUNKS_DELETED);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[SIZE])));
        EmbeddingModelFactory models = mock(EmbeddingModelFactory.class);
        when(models.getOrCreate(any(RagConfiguration.class), eq(EmbeddingInputType.DOCUMENT))).thenReturn(model);

        KnowledgeBaseStorageGuard storageGuard = mock(KnowledgeBaseStorageGuard.class);
        when(storageGuard.sharersOf(anyString(), any(), any())).thenReturn(List.of());

        sources = mock(RagSourceIngestionService.class);
        when(sources.runAsync(eq(ID), any(), any(), any(), any())).thenReturn(Optional.of("run-1"));

        service = new KnowledgeBaseReembedService(pipeline, sources, stateStore, storeFactory, thisNode, storageGuard, models);

        source = new IngestionSource();
        source.setId("src");
        source.setName("Docs");
    }

    private RagConfiguration knowledgeBase(String storeType, String model) {
        RagConfiguration config = new RagConfiguration();
        config.setName("docs");
        config.setStoreNamespace("id");
        config.setStoreType(storeType);
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", model));
        Map<String, String> params = new HashMap<>(Map.of("host", "store.internal", "password", "x"));
        if ("pgvector".equals(storeType)) {
            params.put("dimension", String.valueOf(SIZE));
        } else {
            params.put("collectionName", "docs");
        }
        config.setStoreParameters(params);
        config.setSources(List.of(source));
        return config;
    }

    private String sourceKey() {
        return IngestionPipeline.stateKey(ID, source);
    }

    /**
     * The knowledge base before the re-embed: one document, ingested with the old
     * model, whose space is recorded.
     */
    private void ingestedWith(RagConfiguration old) {
        thisNode.requireCompatible(ID, old);
        stateStore.recordIngested(sourceKey(), "doc-1", "hash-1", null, null, "run-0");
    }

    private VectorSpaceGuard otherNode() {
        return new VectorSpaceGuard(stateStore, variables);
    }

    @Test
    @DisplayName("the store fails in the middle of the delete: refused everywhere, nothing believed up to date, a second re-embed recovers it")
    void failureMidDelete() {
        RagConfiguration old = knowledgeBase("qdrant", "old-8");
        RagConfiguration now = knowledgeBase("qdrant", "new-8");
        ingestedWith(old);
        when(storeFactory.removeKnowledgeBaseVectors(ID, now)).thenThrow(new IllegalStateException("connection reset"))
                .thenReturn(RemovalOutcome.CHUNKS_DELETED);

        assertThrows(ReembedIncompleteException.class, () -> service.reembed(ID, now));

        // Some vectors may be gone: the knowledge base is refused on every node, for
        // search and for a write, whichever model the caller holds.
        VectorSpaceGuard cold = otherNode();
        var refused = assertThrows(VectorSpaceMismatchException.class, () -> cold.requireCompatible(ID, old));
        assertTrue(refused.getMessage().contains("did not finish"), refused.getMessage());
        assertThrows(VectorSpaceMismatchException.class, () -> cold.requireCompatibleForWrite(ID, now));
        assertTrue(cold.status(ID, now).rebuilding());
        // Nothing is believed up to date, and nothing is left claimed.
        assertNull(stateStore.lookup(sourceKey(), "doc-1").orElseThrow().contentHash());
        assertTrue(stateStore.activeRun(sourceKey()).isEmpty());

        var result = service.reembed(ID, now);

        assertFalse(result.refused());
        assertDoesNotThrow(() -> otherNode().requireCompatible(ID, now));
        assertThrows(VectorSpaceMismatchException.class, () -> otherNode().requireCompatible(ID, old));
        assertTrue(stateStore.lookup(sourceKey(), "doc-1").isEmpty(), "the state is gone, so the run embeds everything");
        verify(sources).runAsync(eq(ID), eq(now), eq(source), any(), any());
    }

    @Test
    @DisplayName("the process dies while deleting: the mark stays, the claim is reaped later, and a second re-embed recovers it")
    void killedMidDelete() {
        RagConfiguration old = knowledgeBase("qdrant", "old-8");
        RagConfiguration now = knowledgeBase("qdrant", "new-8");
        ingestedWith(old);
        when(storeFactory.removeKnowledgeBaseVectors(ID, now)).thenThrow(new Killed()).thenReturn(RemovalOutcome.CHUNKS_DELETED);

        assertThrows(Killed.class, () -> service.reembed(ID, now));

        assertThrows(VectorSpaceMismatchException.class, () -> otherNode().requireCompatible(ID, old));
        assertThrows(VectorSpaceMismatchException.class, () -> otherNode().requireCompatibleForWrite(ID, now));
        assertNull(stateStore.lookup(sourceKey(), "doc-1").orElseThrow().contentHash());
        // The dead process's claim holds the source until it is reaped; meanwhile a
        // re-embed is refused as busy and changes nothing.
        assertTrue(stateStore.activeRun(sourceKey()).isPresent());
        assertTrue(service.reembed(ID, now).refused());
        stateStore.reapStaleRuns(sourceKey(), Instant.now().plusSeconds(1));

        assertFalse(service.reembed(ID, now).refused());
        assertDoesNotThrow(() -> otherNode().requireCompatible(ID, now));
    }

    @Test
    @DisplayName("a pgvector table that has to change size and stays busy: nothing is removed, and the record is as it was")
    void busyTableRemovesNothing() {
        RagConfiguration old = knowledgeBase("pgvector", "old-8");
        RagConfiguration now = knowledgeBase("pgvector", "new-8");
        ingestedWith(old);
        when(storeFactory.inspectPgVectorTable(eq(ID), any(), anyString())).thenReturn(new PgVectorTableInfo(true, true, 1536, false));
        when(storeFactory.dropKnowledgeBaseTable(ID, now)).thenReturn(RemovalOutcome.TABLE_BUSY);

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, now));

        assertEquals(KnowledgeBaseReembedService.CODE_TABLE_BUSY, refused.code());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        // Unchanged: the old model is still the recorded one, nothing is marked, and
        // the source is free again.
        VectorSpaceGuard cold = otherNode();
        assertDoesNotThrow(() -> cold.requireCompatible(ID, old));
        assertFalse(cold.status(ID, now).rebuilding());
        assertTrue(stateStore.activeRun(sourceKey()).isEmpty());
    }

    @Test
    @DisplayName("a pgvector table that changes size is dropped whole, never emptied row by row first")
    void sizeChangeDropsTheTable() {
        RagConfiguration old = knowledgeBase("pgvector", "old-8");
        RagConfiguration now = knowledgeBase("pgvector", "new-8");
        ingestedWith(old);
        when(storeFactory.inspectPgVectorTable(eq(ID), any(), anyString())).thenReturn(new PgVectorTableInfo(true, true, 1536, false));
        when(storeFactory.dropKnowledgeBaseTable(ID, now)).thenReturn(RemovalOutcome.TABLE_DROPPED);

        var result = service.reembed(ID, now);

        assertEquals(Boolean.TRUE, result.tableRecreated());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        assertDoesNotThrow(() -> otherNode().requireCompatibleForWrite(ID, now));
    }

    @Test
    @DisplayName("a collection that rejects a vector of the new size refuses the re-embed before anything is touched")
    void collectionOfAnotherSizeRemovesNothing() {
        RagConfiguration old = knowledgeBase("qdrant", "old-8");
        RagConfiguration now = knowledgeBase("qdrant", "new-8");
        ingestedWith(old);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> sizedForAnother = mock(EmbeddingStore.class);
        when(sizedForAnother.search(any())).thenThrow(new RuntimeException("Wrong input: Vector dimension error: expected dim: 1536, got 8"));
        when(storeFactory.getOrCreate(eq(ID), any())).thenReturn(sizedForAnother);

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, now));

        assertEquals(KnowledgeBaseReembedService.CODE_STORE_PROBE_FAILED, refused.code());
        assertFalse(refused.getMessage().contains("expected dim"), "the store's own words stay in the log");
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        assertEquals("hash-1", stateStore.lookup(sourceKey(), "doc-1").orElseThrow().contentHash());
        assertDoesNotThrow(() -> otherNode().requireCompatible(ID, old));
    }

    @Test
    @DisplayName("a write on another node after the mark is refused at once, not after its cache expires")
    void otherNodeWritesAreRefusedAtOnce() {
        RagConfiguration old = knowledgeBase("qdrant", "old-8");
        RagConfiguration now = knowledgeBase("qdrant", "new-8");
        ingestedWith(old);
        VectorSpaceGuard warm = otherNode();
        // That node has the old record cached.
        warm.requireCompatible(ID, old);
        when(storeFactory.removeKnowledgeBaseVectors(ID, now)).thenThrow(new IllegalStateException("store down"));

        assertThrows(ReembedIncompleteException.class, () -> service.reembed(ID, now));

        assertThrows(VectorSpaceMismatchException.class, () -> warm.requireCompatibleForWrite(ID, old));
    }
}

/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.rest.KnowledgeBaseStorageGuard;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseReembedService.ReembedRefusedException;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.OperatorFailureException;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("KnowledgeBaseReembedService")
class KnowledgeBaseReembedServiceTest {

    private static final String ID = "kb1";

    private IngestionPipeline pipeline;
    private RagSourceIngestionService sources;
    private IIngestionStateStore stateStore;
    private EmbeddingStoreFactory storeFactory;
    private VectorSpaceGuard guard;
    private KnowledgeBaseStorageGuard storageGuard;
    private KnowledgeBaseReembedService service;

    private IngestionSource web;
    private IngestionSource upload;
    private RagConfiguration knowledgeBase;

    @BeforeEach
    void setUp() {
        pipeline = mock(IngestionPipeline.class);
        sources = mock(RagSourceIngestionService.class);
        stateStore = mock(IIngestionStateStore.class);
        storeFactory = mock(EmbeddingStoreFactory.class);
        guard = mock(VectorSpaceGuard.class);
        storageGuard = mock(KnowledgeBaseStorageGuard.class);
        service = new KnowledgeBaseReembedService(pipeline, sources, stateStore, storeFactory, guard, storageGuard);

        web = new IngestionSource();
        web.setId("web1");
        web.setName("Docs site");
        upload = new IngestionSource();
        upload.setId("up1");
        upload.setName("Files");
        knowledgeBase = new RagConfiguration();
        knowledgeBase.setName("docs");
        knowledgeBase.setStoreNamespace("id");
        knowledgeBase.setSources(List.of(web, upload));
        when(storageGuard.sharersOf(anyString(), any())).thenReturn(List.of());
        when(pipeline.claimForMaintenance(eq(ID), any())).thenReturn(Optional.of("claim"));
        when(sources.runAsync(eq(ID), any(), any(), any(), any())).thenReturn(Optional.of("run-1"));
    }

    private String key(IngestionSource source) {
        return IngestionPipeline.stateKeyForSourceId(ID, source.effectiveId());
    }

    @Test
    @DisplayName("forgets what the sources hold, then removes the vectors, records the new space, and runs every source")
    void rebuildsEverything() {
        var result = service.reembed(ID, knowledgeBase);

        assertTrue(!result.refused());
        assertEquals(2, result.sources().size());
        assertEquals("run-1", result.sources().get(0).runId());
        assertEquals(1, result.notReEmbedded().size());
        assertTrue(result.notReEmbedded().get(0).contains("/ingest"), result.notReEmbedded().get(0));

        InOrder order = inOrder(storeFactory, guard, stateStore, sources);
        // The durable step comes first: whatever happens next, a run embeds everything
        // again.
        order.verify(stateStore).invalidateContent(key(web));
        order.verify(stateStore).invalidateContent(key(upload));
        order.verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
        order.verify(guard).reset(eq(ID), eq(knowledgeBase), anyBoolean());
        order.verify(stateStore).purgeSource(key(web));
        order.verify(sources).runAsync(eq(ID), eq(knowledgeBase), eq(web), any(), any());
        // Direct ingests wait only while the vectors go.
        verify(guard).beginMaintenance(ID);
        verify(guard).endMaintenance(ID);
    }

    @Test
    @DisplayName("a crash while the vectors are removed leaves every source marked for a rebuild, and the claims released")
    void crashWhileDeleting() {
        doThrow(new IllegalStateException("db down")).when(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);

        assertThrows(IllegalStateException.class, () -> service.reembed(ID, knowledgeBase));

        verify(stateStore).invalidateContent(key(web));
        verify(stateStore).invalidateContent(key(upload));
        verify(guard, never()).reset(anyString(), any(), anyBoolean());
        verify(pipeline).releaseClaim(ID, web, "claim");
        verify(pipeline).releaseClaim(ID, upload, "claim");
        verify(stateStore, never()).purgeSource(anyString());
        verify(guard).endMaintenance(ID);
    }

    @Test
    @DisplayName("a crash after the vectors are gone and before the record is reset still leaves the state marked for a rebuild")
    void crashAfterDeleteBeforeReset() {
        doThrow(new IllegalStateException("record store down")).when(guard).reset(eq(ID), any(), anyBoolean());

        assertThrows(IllegalStateException.class, () -> service.reembed(ID, knowledgeBase));

        InOrder order = inOrder(stateStore, storeFactory);
        order.verify(stateStore).invalidateContent(key(web));
        order.verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
        verify(stateStore).invalidateContent(key(upload));
        verify(stateStore, never()).purgeSource(anyString());
    }

    @Test
    @DisplayName("a crash after the first source was purged leaves the others marked for a rebuild")
    void crashAfterFirstPurge() {
        doThrow(new IllegalStateException("state store down")).when(stateStore).purgeSource(key(upload));

        var result = service.reembed(ID, knowledgeBase);

        assertEquals("run-1", result.sources().get(0).runId());
        assertNull(result.sources().get(1).runId());
        assertTrue(result.sources().get(1).note().contains("could not be cleared"));
        // Both were marked before anything was removed.
        InOrder order = inOrder(stateStore, storeFactory);
        order.verify(stateStore).invalidateContent(key(upload));
        order.verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
    }

    @Test
    @DisplayName("a run in flight refuses the whole operation and changes nothing")
    void aRunInFlightRefuses() {
        when(pipeline.claimForMaintenance(ID, upload)).thenReturn(Optional.empty());

        var result = service.reembed(ID, knowledgeBase);

        assertTrue(result.refused());
        assertEquals("up1", result.busySourceId());
        verify(pipeline).releaseClaim(ID, web, "claim");
        verify(stateStore, never()).invalidateContent(anyString());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        verify(guard, never()).reset(anyString(), any(), anyBoolean());
        verify(stateStore, never()).purgeSource(anyString());
    }

    @Test
    @DisplayName("an unresolvable embedding setting refuses before anything is claimed or removed")
    void unresolvedSettingsRefuseFirst() {
        when(guard.configured(any())).thenThrow(new OperatorFailureException("variable not resolved"));

        assertThrows(OperatorFailureException.class, () -> service.reembed(ID, knowledgeBase));

        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("another knowledge base with the same chunk tag in the same store (6.5.0 name layout) refuses the operation, naming it")
    void sameNameSameTableRefuses() {
        knowledgeBase.setStoreNamespace(null);
        when(storageGuard.sharersOf(eq(ID), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer("kb2", "docs"),
                new KnowledgeBaseStorageGuard.Sharer("kb3", "other")));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_SHARED_CHUNKS, refused.code());
        assertEquals(List.of("kb2"), refused.knowledgeBases());
        assertTrue(refused.getMessage().contains("separate tables"), refused.getMessage());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("a shared table with other ids is no obstacle: their chunks carry other tags")
    void sharedTableWithOtherIdsIsFine() {
        when(storageGuard.sharersOf(eq(ID), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer("kb2", "kb2")));

        var result = service.reembed(ID, knowledgeBase);

        assertTrue(!result.refused());
        // The location's vector size is kept: somebody else still holds vectors there.
        verify(guard).reset(ID, knowledgeBase, false);
    }

    @Test
    @DisplayName("a changed vector size on a pgvector table that other knowledge bases keep rows in is refused up front")
    void sharedPgVectorTableCannotChangeSize() {
        knowledgeBase.setStoreType("pgvector");
        knowledgeBase.setStoreParameters(Map.of("dimension", "768"));
        when(storeFactory.inspectPgVectorTable(eq(ID), any(), anyString()))
                .thenReturn(new EmbeddingStoreFactory.PgVectorTableInfo(true, true, 1536, true));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_SHARED_TABLE_SIZE, refused.code());
        assertTrue(refused.getMessage().contains("1536") && refused.getMessage().contains("768"), refused.getMessage());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("the same size on a shared pgvector table, or a table of the knowledge base's own, goes ahead")
    void sameSizeOrOwnTableGoesAhead() {
        knowledgeBase.setStoreType("pgvector");
        knowledgeBase.setStoreParameters(Map.of("dimension", "1536"));
        when(storeFactory.inspectPgVectorTable(eq(ID), any(), anyString()))
                .thenReturn(new EmbeddingStoreFactory.PgVectorTableInfo(true, true, 1536, true));
        assertTrue(!service.reembed(ID, knowledgeBase).refused());

        knowledgeBase.setStoreParameters(Map.of("dimension", "768"));
        when(storeFactory.inspectPgVectorTable(eq(ID), any(), anyString()))
                .thenReturn(new EmbeddingStoreFactory.PgVectorTableInfo(true, true, 1536, false));
        assertTrue(!service.reembed(ID, knowledgeBase).refused());
    }

    @Test
    @DisplayName("a disabled source is not run, and says so")
    void disabledSourceIsReported() {
        web.setEnabled(false);

        var result = service.reembed(ID, knowledgeBase);

        assertNull(result.sources().get(0).runId());
        assertTrue(result.sources().get(0).note().contains("disabled"), result.sources().get(0).note());
        verify(sources, never()).runAsync(eq(ID), any(), eq(web), any(), any());
        verify(sources).runAsync(eq(ID), any(), eq(upload), any(), any());
    }

    @Test
    @DisplayName("a knowledge base with no sources still has its vectors removed")
    void noSources() {
        knowledgeBase.setSources(List.of());

        var result = service.reembed(ID, knowledgeBase);

        assertTrue(result.sources().isEmpty());
        verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
        verify(guard).reset(eq(ID), eq(knowledgeBase), anyBoolean());
    }
}

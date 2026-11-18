/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.rest.KnowledgeBaseStorageGuard;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseReembedService.ReembedRefusedException;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory.PgVectorTableInfo;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory.RemovalOutcome;
import ai.labs.eddi.modules.rag.OperatorFailureException;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    /** What the fake embedding model returns: the size of the new vectors. */
    private static final int NEW_SIZE = 768;

    private IngestionPipeline pipeline;
    private RagSourceIngestionService sources;
    private IIngestionStateStore stateStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    private EmbeddingModel model;
    private VectorSpaceGuard guard;
    private KnowledgeBaseStorageGuard storageGuard;
    private KnowledgeBaseReembedService service;

    private IngestionSource web;
    private IngestionSource upload;
    private RagConfiguration knowledgeBase;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        pipeline = mock(IngestionPipeline.class);
        sources = mock(RagSourceIngestionService.class);
        stateStore = mock(IIngestionStateStore.class);
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        model = mock(EmbeddingModel.class);
        guard = mock(VectorSpaceGuard.class);
        storageGuard = mock(KnowledgeBaseStorageGuard.class);
        service = new KnowledgeBaseReembedService(pipeline, sources, stateStore, storeFactory, guard, storageGuard, modelFactory);

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
        when(modelFactory.getOrCreate(any(RagConfiguration.class), eq(EmbeddingInputType.DOCUMENT))).thenReturn(model);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[NEW_SIZE])));
        when(storageGuard.sharersOf(anyString(), any(), any())).thenReturn(List.of());
        when(pipeline.claimForMaintenance(eq(ID), any())).thenReturn(Optional.of("claim"));
        when(sources.runAsync(eq(ID), any(), any(), any(), any())).thenReturn(Optional.of("run-1"));
        when(storeFactory.removeKnowledgeBaseVectors(anyString(), any())).thenReturn(RemovalOutcome.CHUNKS_DELETED);
        probedStore = mock(EmbeddingStore.class);
        when(storeFactory.getOrCreate(anyString(), any())).thenReturn(probedStore);
    }

    /** What a collection store answers the probe search with. */
    private EmbeddingStore<TextSegment> probedStore;

    private String key(IngestionSource source) {
        return IngestionPipeline.stateKeyForSourceId(ID, source.effectiveId());
    }

    private void pgvector(int declared, PgVectorTableInfo info) {
        knowledgeBase.setStoreType("pgvector");
        knowledgeBase.setStoreParameters(Map.of("dimension", String.valueOf(declared)));
        when(storeFactory.inspectPgVectorTable(eq(ID), any(), anyString())).thenReturn(info);
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
        assertNull(result.tableRecreated(), "only a pgvector table is recreated");

        InOrder order = inOrder(model, storeFactory, guard, stateStore, sources);
        // The probe comes before anything is touched, and the durable step before the
        // removal.
        order.verify(model).embed(anyString());
        order.verify(stateStore).invalidateContent(key(web));
        order.verify(stateStore).invalidateContent(key(upload));
        order.verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
        order.verify(guard).reset(eq(ID), eq(knowledgeBase), anyBoolean());
        order.verify(stateStore).purgeSource(key(web));
        order.verify(sources).runAsync(eq(ID), eq(knowledgeBase), eq(web), any(), any());
        verify(guard).beginMaintenance(ID);
        verify(guard).endMaintenance(ID);
    }

    @Test
    @DisplayName("a re-embed that cannot embed deletes nothing: the probe fails first, with the reason")
    void aFailingProbeDeletesNothing() {
        when(model.embed(anyString())).thenThrow(new AuthenticationException("401 sk-live-secret"));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_EMBEDDING_FAILED, refused.code());
        assertTrue(refused.getMessage().contains("credentials"), refused.getMessage());
        assertFalse(refused.getMessage().contains("sk-live-secret"), refused.getMessage());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(stateStore, never()).invalidateContent(anyString());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("a crash while the vectors are removed leaves every source marked for a rebuild, and the claims released")
    void crashWhileDeleting() {
        doThrow(new IllegalStateException("db down")).when(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);

        assertThrows(KnowledgeBaseReembedService.ReembedIncompleteException.class, () -> service.reembed(ID, knowledgeBase));

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

        assertThrows(KnowledgeBaseReembedService.ReembedIncompleteException.class, () -> service.reembed(ID, knowledgeBase));

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
        verify(stateStore, never()).purgeSource(anyString());
    }

    @Test
    @DisplayName("an unresolvable embedding setting refuses before anything is claimed or removed")
    void unresolvedSettingsRefuseFirst() {
        when(guard.configured(any())).thenThrow(new OperatorFailureException("variable not resolved"));

        assertThrows(OperatorFailureException.class, () -> service.reembed(ID, knowledgeBase));

        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        verify(model, never()).embed(anyString());
    }

    @Test
    @DisplayName("an incomplete scan of the other knowledge bases refuses with its own exception, before anything changes")
    void incompleteScanRefuses() {
        when(storageGuard.sharersOf(anyString(), any(), any())).thenThrow(
                new KnowledgeBaseStorageGuard.SharerScanIncompleteException("1 knowledge base(s) could not be read", List.of("broken-kb")));

        var incomplete = assertThrows(KnowledgeBaseStorageGuard.SharerScanIncompleteException.class,
                () -> service.reembed(ID, knowledgeBase));

        assertEquals(List.of("broken-kb"), incomplete.unreadable());
        verify(model, never()).embed(anyString());
        verify(stateStore, never()).invalidateContent(anyString());
    }

    @Test
    @DisplayName("the other knowledge bases' locations are compared as the guard resolves them")
    void sharersAreFoundByTheGuardsResolvedLocation() {
        service.reembed(ID, knowledgeBase);

        verify(storageGuard).sharersOf(eq(ID), eq(knowledgeBase), any());
    }

    @Test
    @DisplayName("another knowledge base with the same chunk tag in the same store (6.5.0 name layout) refuses the operation, naming it")
    void sameNameSameTableRefuses() {
        knowledgeBase.setStoreNamespace(null);
        when(storageGuard.sharersOf(eq(ID), any(), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer("kb2", "docs"),
                new KnowledgeBaseStorageGuard.Sharer("kb3", "other")));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_SHARED_CHUNKS, refused.code());
        assertEquals(List.of("kb2"), refused.knowledgeBases());
        assertTrue(refused.getMessage().contains("separate tables"), refused.getMessage());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("a shared table with other ids is no obstacle when the size is the same: their chunks carry other tags")
    void sharedTableWithOtherIdsIsFine() {
        when(storageGuard.sharersOf(eq(ID), any(), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer("kb2", "kb2")));

        var result = service.reembed(ID, knowledgeBase);

        assertTrue(!result.refused());
        verify(guard).reset(ID, knowledgeBase, false);
    }

    @Test
    @DisplayName("pgvector: a table that is not an EDDI table is refused at the start, before any source's hashes are wiped")
    void foreignTableIsRefusedFirst() {
        pgvector(NEW_SIZE, new PgVectorTableInfo(true, false, null, false));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_NOT_A_PGVECTOR_TABLE, refused.code());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(stateStore, never()).invalidateContent(anyString());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("pgvector: a model of another size than storeParameters.dimension is refused up front with the setting to change")
    void declaredDimensionMustBeTheNewSize() {
        pgvector(1536, new PgVectorTableInfo(true, true, 1536, false));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_DIMENSION_SETTING, refused.code());
        assertTrue(refused.getMessage().contains("768") && refused.getMessage().contains("storeParameters.dimension"), refused.getMessage());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("pgvector: a size change on a table other knowledge bases keep rows in is refused before anything is deleted")
    void sharedPgVectorTableCannotChangeSize() {
        pgvector(NEW_SIZE, new PgVectorTableInfo(true, true, 1536, true));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_SHARED_TABLE_SIZE, refused.code());
        assertTrue(refused.getMessage().contains("1536") && refused.getMessage().contains("768"), refused.getMessage());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("pgvector: a table of its own that changes size is dropped whole and recreated, and the result says so")
    void ownTableIsRecreated() {
        pgvector(NEW_SIZE, new PgVectorTableInfo(true, true, 1536, false));
        when(storeFactory.dropKnowledgeBaseTable(ID, knowledgeBase)).thenReturn(RemovalOutcome.TABLE_DROPPED);

        var result = service.reembed(ID, knowledgeBase);

        assertEquals(Boolean.TRUE, result.tableRecreated());
        assertTrue(result.warnings().isEmpty());
        verify(guard).reset(ID, knowledgeBase, true);
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
    }

    @Test
    @DisplayName("pgvector: a table that has to change size but stays busy is refused with TABLE_BUSY; nothing is removed, the mark is taken back")
    void busyTableIsRefused() {
        pgvector(NEW_SIZE, new PgVectorTableInfo(true, true, 1536, false));
        when(guard.markRebuilding(ID, knowledgeBase)).thenReturn("v1|openai|old|||");
        when(storeFactory.dropKnowledgeBaseTable(ID, knowledgeBase)).thenReturn(RemovalOutcome.TABLE_BUSY);

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_TABLE_BUSY, refused.code());
        assertTrue(refused.getMessage().contains("Nothing was removed"), refused.getMessage());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        verify(guard).unmarkRebuilding(ID, knowledgeBase, "v1|openai|old|||");
        verify(guard, never()).reset(anyString(), any(), anyBoolean());
        verify(pipeline).releaseClaim(ID, web, "claim");
    }

    @Test
    @DisplayName("pgvector: a table whose size stays is emptied of this knowledge base's rows only; busy or shared is then no obstacle")
    void sameSizeTableKeepsTable() {
        pgvector(NEW_SIZE, new PgVectorTableInfo(true, true, NEW_SIZE, true));
        when(storeFactory.removeKnowledgeBaseVectors(ID, knowledgeBase)).thenReturn(RemovalOutcome.TABLE_KEPT_SHARED);

        var result = service.reembed(ID, knowledgeBase);

        assertEquals(Boolean.FALSE, result.tableRecreated());
        verify(storeFactory, never()).dropKnowledgeBaseTable(anyString(), any());
    }

    @Test
    @DisplayName("the durable rebuild mark is written before any content hash is forgotten or any vector removed")
    void marksBeforeTouchingAnything() {
        service.reembed(ID, knowledgeBase);

        InOrder order = inOrder(guard, stateStore, storeFactory);
        order.verify(guard).markRebuilding(ID, knowledgeBase);
        order.verify(stateStore).invalidateContent(key(web));
        order.verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
        order.verify(guard).reset(eq(ID), eq(knowledgeBase), anyBoolean());
    }

    @Test
    @DisplayName("a failure in the middle of the delete keeps the mark: the knowledge base stays refused until a re-embed completes")
    void midDeleteFailureKeepsTheMark() {
        doThrow(new IllegalStateException("db down")).when(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);

        var incomplete = assertThrows(KnowledgeBaseReembedService.ReembedIncompleteException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals("db down", incomplete.getCause().getMessage());
        verify(guard, never()).unmarkRebuilding(anyString(), any(), any());
    }

    @Test
    @DisplayName("a store that cannot delete by metadata removed nothing: the mark is taken back")
    void unsupportedDeleteTakesTheMarkBack() {
        when(guard.markRebuilding(ID, knowledgeBase)).thenReturn("before");
        doThrow(new UnsupportedOperationException("cannot delete by metadata")).when(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);

        assertThrows(UnsupportedOperationException.class, () -> service.reembed(ID, knowledgeBase));

        verify(guard).unmarkRebuilding(ID, knowledgeBase, "before");
    }

    @Test
    @DisplayName("another store: a collection that rejects a search with the probe vector refuses before anything is touched")
    void collectionRejectingTheProbeRefuses() {
        knowledgeBase.setStoreType("qdrant");
        when(probedStore.search(any())).thenThrow(new RuntimeException("Vector dimension error: expected dim: 1536, got 768"));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_STORE_PROBE_FAILED, refused.code());
        verify(pipeline, never()).claimForMaintenance(anyString(), any());
        verify(stateStore, never()).invalidateContent(anyString());
        verify(guard, never()).markRebuilding(anyString(), any());
    }

    @Test
    @DisplayName("another store: a collection that keeps a different size, alone in it, is refused with what the operator must do")
    void collectionCannotChangeSize() {
        knowledgeBase.setStoreType("qdrant");
        when(guard.recordedSize(eq(ID), any())).thenReturn(1536);

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_STORE_CANNOT_RESIZE, refused.code());
        assertTrue(refused.getMessage().contains("collectionName") && refused.getMessage().contains("1536"), refused.getMessage());
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        verify(stateStore, never()).invalidateContent(anyString());
    }

    @Test
    @DisplayName("another store: a shared collection with a different size is refused as shared, naming the others")
    void sharedCollectionCannotChangeSize() {
        knowledgeBase.setStoreType("qdrant");
        when(guard.recordedSize(eq(ID), any())).thenReturn(1536);
        when(storageGuard.sharersOf(eq(ID), any(), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer("kb2", "kb2")));

        var refused = assertThrows(ReembedRefusedException.class, () -> service.reembed(ID, knowledgeBase));

        assertEquals(KnowledgeBaseReembedService.CODE_SHARED_TABLE_SIZE, refused.code());
        assertEquals(List.of("kb2"), refused.knowledgeBases());
    }

    @Test
    @DisplayName("another store: the same size, or no recorded size, goes ahead")
    void sameOrUnknownSizeGoesAhead() {
        knowledgeBase.setStoreType("qdrant");
        when(guard.recordedSize(eq(ID), any())).thenReturn(NEW_SIZE);
        assertTrue(!service.reembed(ID, knowledgeBase).refused());

        when(guard.recordedSize(eq(ID), any())).thenReturn(null);
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

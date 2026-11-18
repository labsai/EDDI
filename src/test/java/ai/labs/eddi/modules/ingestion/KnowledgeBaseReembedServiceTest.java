/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        service = new KnowledgeBaseReembedService(pipeline, sources, stateStore, storeFactory, guard);

        web = new IngestionSource();
        web.setId("web1");
        web.setName("Docs site");
        upload = new IngestionSource();
        upload.setId("up1");
        upload.setName("Files");
        knowledgeBase = new RagConfiguration();
        knowledgeBase.setSources(List.of(web, upload));
        when(pipeline.claimForMaintenance(eq(ID), any())).thenReturn(Optional.of("claim"));
        when(sources.runAsync(eq(ID), any(), any(), any(), any())).thenReturn(Optional.of("run-1"));
    }

    @Test
    @DisplayName("removes the vectors, records the new space, forgets the state and runs every source")
    void rebuildsEverything() {
        var result = service.reembed(ID, knowledgeBase);

        assertTrue(!result.refused());
        assertEquals(2, result.sources().size());
        assertEquals("run-1", result.sources().get(0).runId());
        // What EDDI cannot rebuild is said, not hidden.
        assertEquals(1, result.notReEmbedded().size());
        assertTrue(result.notReEmbedded().get(0).contains("/ingest"), result.notReEmbedded().get(0));

        InOrder order = inOrder(storeFactory, guard, stateStore, sources);
        order.verify(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);
        order.verify(guard).reset(ID, knowledgeBase);
        order.verify(stateStore).purgeSource(IngestionPipeline.stateKeyForSourceId(ID, "web1"));
        order.verify(sources).runAsync(eq(ID), eq(knowledgeBase), eq(web), any(), any());
    }

    @Test
    @DisplayName("a run in flight refuses the whole operation and changes nothing")
    void aRunInFlightRefuses() {
        when(pipeline.claimForMaintenance(ID, upload)).thenReturn(Optional.empty());

        var result = service.reembed(ID, knowledgeBase);

        assertTrue(result.refused());
        assertEquals("up1", result.busySourceId());
        // The claim already taken on the first source is given back.
        verify(pipeline).releaseClaim(ID, web, "claim");
        verify(storeFactory, never()).removeKnowledgeBaseVectors(anyString(), any());
        verify(guard, never()).reset(anyString(), any());
        verify(stateStore, never()).purgeSource(anyString());
    }

    @Test
    @DisplayName("a store that cannot be emptied leaves the state as it was and releases the claims")
    void storeFailureRollsBack() {
        doThrow(new IllegalStateException("db down")).when(storeFactory).removeKnowledgeBaseVectors(ID, knowledgeBase);

        assertThrows(IllegalStateException.class, () -> service.reembed(ID, knowledgeBase));

        verify(pipeline).releaseClaim(ID, web, "claim");
        verify(pipeline).releaseClaim(ID, upload, "claim");
        verify(guard, never()).reset(anyString(), any());
        verify(stateStore, never()).purgeSource(anyString());
        verify(sources, never()).runAsync(anyString(), any(), any(), any(), any());
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
        verify(guard).reset(ID, knowledgeBase);
    }
}

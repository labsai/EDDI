/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What deleting a knowledge base does to its sources' schedules.
 *
 * <p>
 * Schedules belong to the knowledge base, not to one version of it, so they go
 * only when no current version is left — removing them for any delete stopped a
 * still-deployed knowledge base from crawling. And the check must cost one
 * lookup: an earlier version probed every version number up to the one in the
 * request, so {@code ?version=2000000000} asked the server for two billion
 * reads.
 */
class RestRagStoreScheduleCleanupTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private IRagStore ragStore;
    private RagSourceIngestionService sourceIngestionService;
    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        sourceIngestionService = mock(RagSourceIngestionService.class);
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class), sourceIngestionService);

        when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(knowledgeBaseWithScheduledSource());
    }

    private static RagConfiguration knowledgeBaseWithScheduledSource() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var source = new IngestionSource();
        source.setId("src-1");
        source.setName("docs");
        source.setWeb(web);
        source.setCron("0 2 * * *");

        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setSources(List.of(source));
        return config;
    }

    private static IResourceStore.IResourceId currentVersion(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return KB_ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    @Test
    @DisplayName("deleting the last current version removes the schedules")
    void removesSchedulesWhenNothingIsLeft() throws Exception {
        when(ragStore.getCurrentResourceId(KB_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        restRagStore.deleteRag(KB_ID, 1, false);

        verify(sourceIngestionService).removeSchedules(eq(KB_ID), any(RagConfiguration.class));
    }

    @Test
    @DisplayName("schedules survive while a current version remains")
    void keepsSchedulesWhileACurrentVersionRemains() throws Exception {
        when(ragStore.getCurrentResourceId(KB_ID)).thenReturn(currentVersion(3));

        restRagStore.deleteRag(KB_ID, 1, false);

        verify(sourceIngestionService, never()).removeSchedules(any(), any());
    }

    @Test
    @DisplayName("a huge version number costs a bounded number of reads, not one per version")
    void hugeVersionDoesNotLoopOverVersions() throws Exception {
        // The previous check looped `for candidate <= version + 1`, reading each
        // version. With a request-supplied version that is a denial of service, and
        // CodeQL flagged the arithmetic. It must now be a constant number of reads.
        when(ragStore.getCurrentResourceId(KB_ID)).thenReturn(currentVersion(3));

        restRagStore.deleteRag(KB_ID, Integer.MAX_VALUE - 1, false);

        verify(ragStore, atMost(2)).read(eq(KB_ID), anyInt());
    }
}

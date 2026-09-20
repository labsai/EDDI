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
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a newly created knowledge base's schedules get their id.
 *
 * <p>
 * A create has no id until the store hands one back, so the id and version come
 * out of the response's location. Nothing covered that: schedules for a source
 * created together with its knowledge base would have been synchronised under
 * the wrong id — or not at all — and the failure is silent, because the create
 * still answers 201 and the source still looks scheduled.
 */
class RestRagStoreScheduleSyncTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private IRagStore ragStore;
    private RagSourceIngestionService sourceIngestionService;
    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() {
        ragStore = mock(IRagStore.class);
        sourceIngestionService = mock(RagSourceIngestionService.class);
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class), sourceIngestionService);
    }

    @Test
    @DisplayName("a created knowledge base schedules its sources under the id the store assigned")
    void createSchedulesUnderTheAssignedId() throws Exception {
        when(ragStore.create(any(RagConfiguration.class))).thenReturn(resourceId(KB_ID, 1));

        restRagStore.createRag(knowledgeBaseWithScheduledSource());

        var id = ArgumentCaptor.forClass(String.class);
        var version = ArgumentCaptor.forClass(Integer.class);
        verify(sourceIngestionService).syncSchedules(id.capture(), version.capture(), any(RagConfiguration.class),
                anySet());
        assertEquals(KB_ID, id.getValue(), "the schedule must name the knowledge base the store just created");
        assertEquals(1, version.getValue());
    }

    @Test
    @DisplayName("an update schedules under the id in the path, and says which sources existed before")
    void updateReportsThePreviousSources() throws Exception {
        var previous = knowledgeBaseWithScheduledSource();
        previous.getSources().get(0).setId("src-old");
        when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(previous);
        when(ragStore.update(eq(KB_ID), anyInt(), any(RagConfiguration.class))).thenReturn(2);

        var updated = knowledgeBaseWithScheduledSource();
        updated.getSources().get(0).setId("src-new");
        restRagStore.updateRag(KB_ID, 1, updated);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> previousIds = ArgumentCaptor.forClass(Set.class);
        verify(sourceIngestionService).syncSchedules(eq(KB_ID), any(), any(RagConfiguration.class),
                previousIds.capture());
        assertEquals(Set.of("src-old"), previousIds.getValue(),
                "a source removed by this update must be named, or its schedule keeps firing");
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
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
}

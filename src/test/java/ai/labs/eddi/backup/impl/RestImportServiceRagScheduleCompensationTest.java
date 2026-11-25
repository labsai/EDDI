/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The schedules an imported knowledge base gets are taken back when the import
 * rolls back — also when creating them failed part-way, after the earlier
 * sources' schedules were armed.
 */
@DisplayName("RestImportService — rollback of an imported knowledge base's schedules")
class RestImportServiceRagScheduleCompensationTest {

    private static final String KB_ID = "6a1f0c2b3d4e5f60718293a4";

    private RagSourceIngestionService ingestion;
    private RestImportService importService;

    @BeforeEach
    void setUp() {
        ingestion = mock(RagSourceIngestionService.class);
        importService = new RestImportService(
                mock(IZipArchive.class), mock(IJsonSerialization.class),
                mock(IMigrationManager.class), mock(IDocumentDescriptorStore.class),
                mock(TemplateSyntaxMigrator.class), mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class),
                ingestion, true, false, Optional.empty());
    }

    private static RagConfiguration knowledgeBase() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/docs/");
        var source = new IngestionSource();
        source.setName("public-docs");
        source.setWeb(web);
        source.setCron("0 2 * * *");
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setSources(List.of(source));
        return config;
    }

    @Test
    @DisplayName("a sync that fails part-way still leaves the cleanup on the transaction")
    void failedSyncStillRecordsTheCleanup() {
        var config = knowledgeBase();
        doThrow(new IllegalStateException("second source could not be scheduled")).when(ingestion)
                .syncSchedules(anyString(), anyInt(), any(RagConfiguration.class), any(Set.class));
        List<Runnable> compensations = new ArrayList<>();

        importService.syncImportedRagSchedules(URI.create("eddi://ai.labs.rag/ragstore/rags/" + KB_ID + "?version=1"), config,
                compensations::add);

        assertEquals(1, compensations.size(), "the rollback must remove the schedules the sync armed before it failed");
        compensations.getFirst().run();
        verify(ingestion).removeSchedules(KB_ID, config);
    }
}

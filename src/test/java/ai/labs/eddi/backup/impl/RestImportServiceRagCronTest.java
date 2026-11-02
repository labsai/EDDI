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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * An archive's ingestion crons are held to the same rule as an operator's.
 * <p>
 * A knowledge base that arrives in a ZIP is written through
 * {@code createResourceDirect}, straight to the store, so none of
 * {@code RestRagStore.prepareForWrite} runs. That path assigned source ids and
 * called {@code RagConfiguration.validate()} — which does not look at the cron
 * — so a ZIP could store an expression {@code POST /ragstore/rags} refuses. The
 * schedule built from it either could not be armed at all or was armed and
 * never matched, while every screen went on showing the source as scheduled.
 */
@DisplayName("RestImportService — ingestion crons from an archive")
class RestImportServiceRagCronTest {

    private RestImportService importService;

    @BeforeEach
    void setUp() {
        importService = new RestImportService(
                mock(IZipArchive.class), mock(IJsonSerialization.class),
                mock(IMigrationManager.class), mock(IDocumentDescriptorStore.class),
                mock(TemplateSyntaxMigrator.class), mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class),
                mock(RagSourceIngestionService.class), true, false, Optional.empty());
    }

    private static RagConfiguration knowledgeBaseWithCron(String cron) {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/docs/");

        var source = new IngestionSource();
        source.setName("public-docs");
        source.setWeb(web);
        source.setCron(cron);

        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setSources(List.of(source));
        return config;
    }

    @Test
    @DisplayName("a Quartz cron with a seconds column is refused, as it is at the REST boundary")
    void refusesSixFieldCron() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> importService.prepareImportedRag(knowledgeBaseWithCron("0 0 2 * * *")));

        assertTrue(thrown.getMessage().contains("public-docs"), thrown.getMessage());
    }

    @Test
    @DisplayName("a cron that parses but can never match is refused too")
    void refusesUnsatisfiableCron() {
        assertThrows(IllegalArgumentException.class,
                () -> importService.prepareImportedRag(knowledgeBaseWithCron("0 0 30 2 *")));
    }

    @Test
    @DisplayName("a valid cron still imports, and the source still gets its id")
    void acceptsAValidCron() {
        var config = knowledgeBaseWithCron("0 2 * * *");

        assertDoesNotThrow(() -> importService.prepareImportedRag(config));

        String id = config.getSources().get(0).getId();
        assertNotNull(id, "ingestion state and the schedule name key on this id");
        assertTrue(!id.isBlank());
    }

    @Test
    @DisplayName("a source with no cron is untouched — it runs only when asked")
    void acceptsNoCron() {
        assertDoesNotThrow(() -> importService.prepareImportedRag(knowledgeBaseWithCron(null)));
    }

    @Test
    @DisplayName("chunk settings an earlier release accepted are clamped, not refused, on import")
    void clampsChunkSettingsFromAnEarlierRelease() {
        // A chunk size of 64 with the default overlap of 64 was valid before the
        // overlap was bounded at half the size; refusing it would make such an
        // export impossible to import, since an archive cannot be edited first.
        var config = knowledgeBaseWithCron(null);
        config.setChunkSize(64);
        config.setChunkOverlap(64);

        assertDoesNotThrow(() -> importService.prepareImportedRag(config));

        assertEquals(64, config.getChunkSize());
        assertEquals(32, config.getChunkOverlap());
    }

    @Test
    @DisplayName("a knowledge base without sources has its chunk settings clamped too")
    void clampsChunkSettingsWithoutSources() {
        var config = new RagConfiguration();
        config.setChunkSize(50_000);
        config.setChunkOverlap(null);

        importService.prepareImportedRag(config);

        assertEquals(RagConfiguration.MAX_CHUNK_SIZE, config.getChunkSize());
        assertNull(config.getChunkOverlap());
    }
}

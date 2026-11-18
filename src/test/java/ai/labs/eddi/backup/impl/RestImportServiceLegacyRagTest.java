/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * A knowledge base from an older archive still imports: values an interactive
 * save now refuses are brought into range or kept, with a WARN, because the
 * archive cannot be edited first.
 */
@DisplayName("RestImportService — an older knowledge base configuration")
class RestImportServiceLegacyRagTest {

    private RestImportService importService;

    @BeforeEach
    void setUp() {
        importService = new RestImportService(mock(IZipArchive.class), mock(IJsonSerialization.class), mock(IMigrationManager.class),
                mock(IDocumentDescriptorStore.class), mock(TemplateSyntaxMigrator.class), mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class), mock(ResourceAccessGuard.class),
                mock(SpaceContext.class), mock(RagSourceIngestionService.class), true, false, Optional.empty());
    }

    /**
     * What an older version accepted: every value here is refused by an interactive
     * save.
     */
    private static RagConfiguration old() {
        var config = new RagConfiguration();
        config.setName("  ");
        config.setStoreType("legacy-store");
        config.setStoreParameters(Map.of("host", "db:5432"));
        config.setMinScore(1.5);
        config.setMaxResults(0);
        return config;
    }

    @Test
    @DisplayName("an archive holding host db:5432, minScore 1.5, an unknown storeType and a blank name imports — with or without sources")
    void importAcceptsIt() {
        RagConfiguration withoutSources = old();
        withoutSources.setSources(null);
        RagConfiguration withSources = old();

        assertDoesNotThrow(() -> importService.prepareImportedRag(withoutSources));
        assertDoesNotThrow(() -> importService.prepareImportedRag(withSources));

        assertEquals(1.0, withoutSources.getMinScore());
        assertEquals(5, withoutSources.getMaxResults());
        assertEquals("legacy-store", withoutSources.getStoreType());
        assertEquals("db:5432", withoutSources.getStoreParameters().get("host"));
        assertEquals("  ", withoutSources.getName());
    }

    @Test
    @DisplayName("what it still refuses is what could never have worked: a chunk strategy nothing implements")
    void importStillRefusesTheImpossible() {
        RagConfiguration config = old();
        config.setChunkStrategy("telepathy");

        assertThrows(IllegalArgumentException.class, () -> importService.prepareImportedRag(config));
    }
}

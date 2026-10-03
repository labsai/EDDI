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
import ai.labs.eddi.configs.rag.rest.KnowledgeBaseStorageGuard;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A knowledge base that arrives in an archive and is created is a new knowledge
 * base: it gets a store of its own, whichever layout the archive's source
 * instance used, and may not name another knowledge base's store.
 */
@DisplayName("RestImportService — the store of an imported knowledge base")
class RestImportServiceRagStorageTest {

    private RestImportService importService;
    private KnowledgeBaseStorageGuard guard;

    @BeforeEach
    void setUp() {
        importService = new RestImportService(
                mock(IZipArchive.class), mock(IJsonSerialization.class),
                mock(IMigrationManager.class), mock(IDocumentDescriptorStore.class),
                mock(TemplateSyntaxMigrator.class), mock(StructuralMatcher.class),
                mock(UpgradeExecutor.class), mock(IScheduleStore.class), mock(BackupMetrics.class),
                mock(ResourceAccessGuard.class), mock(SpaceContext.class),
                mock(RagSourceIngestionService.class), true, false, Optional.empty());
        guard = mock(KnowledgeBaseStorageGuard.class);
    }

    private static RagConfiguration archived(String namespace) {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setStoreType("pgvector");
        config.setStoreNamespace(namespace);
        return config;
    }

    @Test
    @DisplayName("the storage guard decides, so an archive cannot keep the 6.5.0 layout")
    void theGuardIsAskedAboutTheNewKnowledgeBase() {
        importService.useKnowledgeBaseStorageGuard(guard);
        var config = archived("name");

        importService.applyStorageRulesToNewRag(config);

        verify(guard).prepareNew(config, false);
    }

    @Test
    @DisplayName("an explicit location the guard refuses fails the import rather than being stored")
    void aRefusedLocationFailsTheImport() {
        var real = new KnowledgeBaseStorageGuard(null, null, null);
        importService.useKnowledgeBaseStorageGuard(real);
        var config = archived(null);
        config.setStoreParameters(Map.of("table", "eddi_kb_other"));

        assertThrows(BadRequestException.class, () -> importService.applyStorageRulesToNewRag(config));
    }

    @Test
    @DisplayName("without a guard (built outside the container) it still gets the id layout")
    void withoutAGuardItStillGetsTheIdLayout() {
        var config = archived("name");

        importService.applyStorageRulesToNewRag(config);

        assertEquals("id", config.getStoreNamespace());
    }
}

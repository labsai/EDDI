/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IZipArchive;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.TemplateSyntaxMigrator;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.rest.RestRagStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * A merge or sync of an archive updates an existing knowledge base: run through
     * the import's own update, against a store whose interactive update refuses
     * what an older version accepted.
     */
    @Test
    @DisplayName("a merge or sync updates an existing knowledge base through the lenient update, never the interactive one")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mergeUpdateIsLenient() throws Exception {
        RestRagStore ragStore = mock(RestRagStore.class);
        when(ragStore.updateRag(anyString(), any(), any())).thenThrow(new BadRequestException("minScore must be between 0 and 1"));
        when(ragStore.updateRagLenient(anyString(), any(), any())).thenReturn(Response.ok().build());
        IRagStore documents = mock(IRagStore.class);
        when(documents.read("kb-1", 3)).thenReturn(new RagConfiguration());
        Class<?> transactionClass = Class.forName(RestImportService.class.getName() + "$ImportTransaction");
        Constructor<?> newTransaction = transactionClass.getDeclaredConstructor();
        newTransaction.setAccessible(true);
        Method updateRag = RestImportService.class.getDeclaredMethod("updateRag", RagConfiguration.class, String.class, Integer.class,
                transactionClass);
        updateRag.setAccessible(true);

        try (MockedStatic<CDI> cdiMock = mockStatic(CDI.class)) {
            CDI cdi = mock(CDI.class);
            cdiMock.when(CDI::current).thenReturn(cdi);
            Instance rest = mock(Instance.class);
            when(cdi.select(IRestRagStore.class)).thenReturn(rest);
            when(rest.get()).thenReturn(ragStore);
            Instance stored = mock(Instance.class);
            when(cdi.select(IRagStore.class)).thenReturn(stored);
            when(stored.get()).thenReturn(documents);

            updateRag.invoke(importService, old(), "kb-1", 3, newTransaction.newInstance());
        }

        verify(ragStore).updateRagLenient(eq("kb-1"), eq(3), any(RagConfiguration.class));
        verify(ragStore, never()).updateRag(anyString(), any(), any());
    }

    @Test
    @DisplayName("what it still refuses is what could never have worked: a chunk strategy nothing implements")
    void importStillRefusesTheImpossible() {
        RagConfiguration config = old();
        config.setChunkStrategy("telepathy");

        assertThrows(IllegalArgumentException.class, () -> importService.prepareImportedRag(config));
    }
}
